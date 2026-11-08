using System.Collections.Concurrent;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.Logging;
using Microsoft.Net.Http.Headers;

namespace Orbit.Core;

/// <summary>
/// Orbit protocol v1 over plain HTTP/1.1 on the LAN (see docs/PROTOCOL.md).
/// </summary>
public sealed class ReceiverServer : IAsyncDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly TransferManager _manager;
    private readonly Func<ReceiverSettings> _settings;
    private readonly string _platform;
    private readonly ConcurrentDictionary<IPAddress, (int Count, DateTimeOffset Since)> _authFailures = new();
    private WebApplication? _app;

    public ReceiverServer(TransferManager manager, Func<ReceiverSettings> settings, string platform = "windows")
    {
        _manager = manager;
        _settings = settings;
        _platform = platform;
    }

    public int Port { get; private set; }
    public bool IsRunning => _app is not null;

    /// <summary>
    /// Decides whether the network a request arrived on is trusted (e.g. a Windows "Private" network, not "Public").
    /// Called with the local address the connection was accepted on; loopback is always trusted.
    /// </summary>
    public Func<IPAddress, bool>? IsTrustedNetwork { get; set; }

    /// <summary>Binds the preferred port, falling back to the next ten ports, then to any free port.</summary>
    public async Task StartAsync(int preferredPort)
    {
        // preferredPort 0 = let the OS pick a free port.
        var candidates = preferredPort == 0 ? [0] : Enumerable.Range(preferredPort, 10).Append(0);
        Exception? last = null;
        foreach (var port in candidates)
        {
            var app = Build(port);
            try
            {
                await app.StartAsync();
                _app = app;
                Port = port;
                if (Port == 0)
                {
                    var server = (Microsoft.AspNetCore.Hosting.Server.IServer)app.Services.GetService(typeof(Microsoft.AspNetCore.Hosting.Server.IServer))!;
                    var feature = server.Features.Get<Microsoft.AspNetCore.Hosting.Server.Features.IServerAddressesFeature>()!;
                    Port = new Uri(feature.Addresses.First().Replace("[::]", "localhost").Replace("0.0.0.0", "localhost")).Port;
                }
                return;
            }
            // Port in use (IOException) or inside a Windows-excluded range, e.g. Hyper-V/WSL reservations (SocketException: access denied).
            catch (Exception ex) when (ex is IOException or System.Net.Sockets.SocketException) { last = ex; await app.DisposeAsync(); }
        }
        throw new InvalidOperationException("Could not bind a TCP port.", last);
    }

    public async Task StopAsync()
    {
        if (_app is null) return;
        var app = _app;
        _app = null;
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        try { await app.StopAsync(cts.Token); } catch { /* ignore */ }
        await app.DisposeAsync();
    }

    public ValueTask DisposeAsync() => new(StopAsync());

    // ------------------------------------------------------------------ pipeline

    private WebApplication Build(int port)
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(k =>
        {
            k.ListenAnyIP(port);
            k.Limits.MaxRequestBodySize = null;          // multi-GB videos
            k.Limits.MinRequestBodyDataRate = null;      // receiver-side pause stops reading the body
            k.Limits.KeepAliveTimeout = TimeSpan.FromMinutes(2);
            k.AddServerHeader = false;
        });
        var app = builder.Build();

        app.Use(async (ctx, next) =>
        {
            var remote = ctx.Connection.RemoteIpAddress;
            if (!NetworkUtil.IsLocalNetwork(remote))
            {
                await Error(ctx, 403, ErrorCodes.NotLocal, "Only devices on the local network may connect.");
                return;
            }
            ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
            ctx.Response.Headers["Referrer-Policy"] = "no-referrer";
            // The QR URL itself ("/?k=…") also accepts uploads, so an iOS Shortcut can POST to whatever it scanned.
            var isRootUpload = ctx.Request.Path == "/" && !HttpMethods.IsGet(ctx.Request.Method) && !HttpMethods.IsHead(ctx.Request.Method);
            if (ctx.Request.Path.StartsWithSegments("/api") || isRootUpload)
            {
                ctx.Response.Headers.CacheControl = "no-store";
                var local = ctx.Connection.LocalIpAddress;
                if (IsTrustedNetwork is { } trusted && local is not null && !IPAddress.IsLoopback(local) && !trusted(local.IsIPv4MappedToIPv6 ? local.MapToIPv4() : local))
                {
                    await Error(ctx, 403, ErrorCodes.UntrustedNetwork, "The receiver is on a public network, receiving is paused.");
                    return;
                }
                if (IsRateLimited(remote!)) { await Error(ctx, 429, ErrorCodes.RateLimited, "Too many attempts."); return; }
                if (!IsAuthorized(ctx)) { RegisterAuthFailure(remote!); await Error(ctx, 401, ErrorCodes.Unauthorized, "Invalid pairing code."); return; }
            }
            try { await next(ctx); }
            catch (TransferException ex) { if (!ctx.Response.HasStarted) await Error(ctx, ex.HttpStatus, ex.Code, ex.Message); }
            catch (JsonException) { if (!ctx.Response.HasStarted) await Error(ctx, 400, ErrorCodes.InvalidRequest, "Malformed JSON."); }
            catch (Exception) when (ctx.RequestAborted.IsCancellationRequested) { /* client went away */ }
            catch (Exception) when (!ctx.Response.HasStarted) { await Error(ctx, 400, ErrorCodes.InvalidRequest, "Upload interrupted."); }
        });

        app.MapGet("/", ctx => Resource(ctx, "web/index.html", "text/html; charset=utf-8"));
        app.MapGet("/icon.svg", ctx => Resource(ctx, "web/icon.svg", "image/svg+xml"));
        app.MapGet("/icon.png", ctx => Resource(ctx, "web/icon.png", "image/png"));

        app.MapGet("/api/v1/info", () =>
        {
            var s = _settings();
            return Results.Json(new { name = s.DeviceName, deviceId = s.DeviceId, platform = _platform, version = ProtocolConstants.Version, receiving = s.ReceivingEnabled, requiresApproval = s.RequireApproval }, Json);
        });

        app.MapPost("/api/v1/sessions", async (HttpContext ctx) =>
        {
            var req = await JsonSerializer.DeserializeAsync<CreateSessionRequest>(ctx.Request.Body, Json, ctx.RequestAborted)
                      ?? throw new TransferException(400, ErrorCodes.InvalidRequest, "Empty request.");
            var files = (req.Files ?? []).Select(f => new IncomingFile(f.Name ?? "", f.Size, f.Type)).ToList();
            var session = _manager.CreateSession(req.Sender ?? "", ctx.Connection.RemoteIpAddress!, files);
            return Results.Json(Dto(session), Json);
        });

        app.MapGet("/api/v1/sessions/{id}", (string id) =>
            _manager.Find(id) is { } s ? Results.Json(Dto(s), Json) : NotFound());

        app.MapDelete("/api/v1/sessions/{id}", (string id) =>
        {
            if (_manager.Find(id) is null) return NotFound();
            _manager.Cancel(id);
            return Results.Json(Dto(_manager.Find(id)!), Json);
        });

        app.MapGet("/api/v1/sessions/{id}/files/{index:int}", (string id, int index) =>
        {
            var s = _manager.Find(id);
            var f = s?.Files.ElementAtOrDefault(index);
            return f is null ? NotFound() : Results.Json(FileDto(f), Json);
        });

        app.MapPut("/api/v1/sessions/{id}/files/{index:int}", async (HttpContext ctx, string id, int index) =>
        {
            var s = _manager.Find(id) ?? throw new TransferException(404, ErrorCodes.NotFound, "Unknown session.");
            if (!s.Remote.Equals(ctx.Connection.RemoteIpAddress) && !IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress!))
                throw new TransferException(403, ErrorCodes.Unauthorized, "Session belongs to another device.");
            var f = s.Files.ElementAtOrDefault(index) ?? throw new TransferException(404, ErrorCodes.NotFound, "Unknown file.");
            long.TryParse(ctx.Request.Query["offset"], out var offset);
            DisableBodyLimits(ctx);
            var result = await _manager.ReceiveAsync(s, f, ctx.Request.Body, offset, ctx.RequestAborted);
            if (result == TransferManager.ReceiveResult.Incomplete)
                return Results.Json(new { error = "incomplete", message = "Body ended early.", received = f.Received }, Json, statusCode: 400);
            return Results.Json(FileDto(f), Json);
        });

        // Single-request upload for the iOS Shortcuts "Get Contents of URL" action (multipart or raw body).
        app.MapMethods("/api/v1/upload", ["POST", "PUT"], Upload);
        app.MapMethods("/", ["POST", "PUT"], Upload);

        return app;
    }

    // Writes its own response: a method group taking HttpContext binds as a RequestDelegate, which would drop an IResult.
    private async Task Upload(HttpContext ctx)
        {
            DisableBodyLimits(ctx);
            var remote = ctx.Connection.RemoteIpAddress!;
            var sender = ctx.Request.Query["from"].FirstOrDefault() ?? GuessSender(ctx.Request.Headers.UserAgent.ToString());
            var session = _manager.GetOrCreateAdHocSession(sender, remote);
            if (!await _manager.WaitForDecisionAsync(session, ctx.RequestAborted))
                throw new TransferException(403, session.Status == SessionStatus.Expired ? ErrorCodes.Expired : ErrorCodes.Declined, "The transfer was not accepted.");

            var saved = new List<string>();
            var contentType = ctx.Request.ContentType ?? "";
            if (contentType.StartsWith("multipart/", StringComparison.OrdinalIgnoreCase))
            {
                var boundary = HeaderUtilities.RemoveQuotes(MediaTypeHeaderValue.Parse(contentType).Boundary).Value
                               ?? throw new TransferException(400, ErrorCodes.InvalidRequest, "Missing multipart boundary.");
                var reader = new MultipartReader(boundary, ctx.Request.Body) { HeadersLengthLimit = 64 * 1024 };
                MultipartSection? section;
                while ((section = await reader.ReadNextSectionAsync(ctx.RequestAborted)) is not null)
                {
                    if (!ContentDispositionHeaderValue.TryParse(section.ContentDisposition, out var cd)) continue;
                    var fileName = cd.FileNameStar.HasValue ? cd.FileNameStar.Value : HeaderUtilities.RemoveQuotes(cd.FileName).Value;
                    if (string.IsNullOrEmpty(fileName) && string.IsNullOrEmpty(section.ContentType)) continue; // plain text field
                    fileName = WithExtension(string.IsNullOrEmpty(fileName) ? HeaderUtilities.RemoveQuotes(cd.Name).Value ?? "file" : fileName, section.ContentType);
                    var file = _manager.AddAdHocFile(session, fileName, -1, section.ContentType);
                    await _manager.ReceiveAsync(session, file, section.Body, 0, ctx.RequestAborted);
                    saved.Add(Path.GetFileName(file.FinalPath!));
                }
            }
            else
            {
                var name = ctx.Request.Query["name"].FirstOrDefault() ?? ctx.Request.Headers["X-File-Name"].FirstOrDefault() ?? "file";
                name = WithExtension(Uri.UnescapeDataString(name), contentType);
                var file = _manager.AddAdHocFile(session, name, ctx.Request.ContentLength ?? -1, contentType);
                var result = await _manager.ReceiveAsync(session, file, ctx.Request.Body, 0, ctx.RequestAborted);
                if (result == TransferManager.ReceiveResult.Incomplete)
                    throw new TransferException(400, "incomplete", "Upload ended early.");
                saved.Add(Path.GetFileName(file.FinalPath!));
            }
            if (saved.Count == 0) throw new TransferException(400, ErrorCodes.InvalidRequest, "No file in request.");
            await Results.Json(new { saved, sessionId = session.Id }, Json).ExecuteAsync(ctx);
        }

    // ------------------------------------------------------------------ helpers

    private bool IsAuthorized(HttpContext ctx)
    {
        var expected = _settings().PairingKey;
        var provided = ctx.Request.Headers[ProtocolConstants.KeyHeader].FirstOrDefault()
                       ?? ctx.Request.Query[ProtocolConstants.KeyQuery].FirstOrDefault() ?? "";
        var a = Encoding.UTF8.GetBytes(provided.Trim().ToUpperInvariant());
        var b = Encoding.UTF8.GetBytes(expected);
        return a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);
    }

    private bool IsRateLimited(IPAddress remote) =>
        _authFailures.TryGetValue(remote, out var e) && e.Count >= 20 && DateTimeOffset.Now - e.Since < TimeSpan.FromMinutes(1);

    private void RegisterAuthFailure(IPAddress remote) =>
        _authFailures.AddOrUpdate(remote, _ => (1, DateTimeOffset.Now),
            (_, e) => DateTimeOffset.Now - e.Since > TimeSpan.FromMinutes(1) ? (1, DateTimeOffset.Now) : (e.Count + 1, e.Since));

    private static void DisableBodyLimits(HttpContext ctx)
    {
        var f = ctx.Features.Get<IHttpMaxRequestBodySizeFeature>();
        if (f is { IsReadOnly: false }) f.MaxRequestBodySize = null;
    }

    private static async Task Resource(HttpContext ctx, string name, string contentType)
    {
        await using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
        if (stream is null) { ctx.Response.StatusCode = 404; return; }
        ctx.Response.ContentType = contentType;
        ctx.Response.Headers.CacheControl = "no-cache";
        await stream.CopyToAsync(ctx.Response.Body);
    }

    private static Task Error(HttpContext ctx, int status, string code, string message)
    {
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "application/json";
        return ctx.Response.WriteAsync(JsonSerializer.Serialize(new { error = code, message }, Json));
    }

    private static IResult NotFound() => Results.Json(new { error = ErrorCodes.NotFound, message = "Not found." }, Json, statusCode: 404);

    private static object Dto(TransferSession s) => new
    {
        sessionId = s.Id,
        status = s.Status.ToString().ToLowerInvariant(),
        paused = s.IsPaused,
        files = s.Files.Select(FileDto).ToList(),
    };

    private static object FileDto(TransferFile f) => new
    {
        index = f.Index,
        name = f.SafeName,
        size = f.Size,
        received = f.Received,
        status = f.Status.ToString().ToLowerInvariant(),
        savedAs = f.Status == FileStatus.Done && f.FinalPath is not null ? Path.GetFileName(f.FinalPath) : null,
    };

    private static string GuessSender(string ua) =>
        ua.Contains("iPhone") ? "iPhone" : ua.Contains("iPad") ? "iPad" : ua.Contains("Mac") ? "Mac" : ua.Contains("Android") ? "Android" : "Shortcut";

    private static readonly Dictionary<string, string> MimeExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ["image/jpeg"] = ".jpg", ["image/heic"] = ".heic", ["image/heif"] = ".heif", ["image/png"] = ".png", ["image/gif"] = ".gif",
        ["image/webp"] = ".webp", ["video/quicktime"] = ".mov", ["video/mp4"] = ".mp4", ["application/pdf"] = ".pdf",
        ["text/plain"] = ".txt", ["application/zip"] = ".zip", ["audio/mpeg"] = ".mp3", ["audio/x-m4a"] = ".m4a",
    };

    internal static string WithExtension(string name, string? contentType)
    {
        if (Path.HasExtension(name) || string.IsNullOrEmpty(contentType)) return name;
        var mime = contentType.Split(';')[0].Trim();
        return MimeExtensions.TryGetValue(mime, out var ext) ? name + ext : name;
    }

    private sealed record CreateSessionRequest(string? Sender, List<FileEntry>? Files);
    private sealed record FileEntry(string? Name, long Size, string? Type);
}
