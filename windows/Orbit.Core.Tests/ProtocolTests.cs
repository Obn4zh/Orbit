using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Orbit.Core;

namespace Orbit.Core.Tests;

/// <summary>End-to-end tests: a real Kestrel server on loopback and an HttpClient sender.</summary>
public sealed class ProtocolTests : IAsyncLifetime
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "orbit-tests-" + Guid.NewGuid().ToString("N"));
    private readonly ReceiverSettings _settings = new() { RequireApproval = false, PairingKey = "TESTKEY2" };
    private TransferManager _manager = null!;
    private ReceiverServer _server = null!;
    private HttpClient _http = null!;

    public async Task InitializeAsync()
    {
        _settings.DownloadFolder = _folder;
        _manager = new TransferManager(() => _settings);
        _server = new ReceiverServer(_manager, () => _settings);
        await _server.StartAsync(0); // OS-assigned port: no collisions with other processes or test runs
        _http = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{_server.Port}"), Timeout = TimeSpan.FromMinutes(5) };
        _http.DefaultRequestHeaders.Add(ProtocolConstants.KeyHeader, _settings.PairingKey);
    }

    public async Task DisposeAsync()
    {
        _http.Dispose();
        await _server.DisposeAsync();
        try { Directory.Delete(_folder, true); } catch { }
    }

    private static byte[] RandomBytes(int n) { var b = new byte[n]; Random.Shared.NextBytes(b); return b; }

    private async Task<JsonElement> CreateSession(params (string name, long size)[] files)
    {
        var res = await _http.PostAsJsonAsync("/api/v1/sessions", new { sender = "Test", files = files.Select(f => new { f.name, f.size }) });
        Assert.Equal(HttpStatusCode.OK, res.StatusCode);
        return await res.Content.ReadFromJsonAsync<JsonElement>();
    }

    private Task<HttpResponseMessage> Put(string sid, int index, byte[] data, long offset = 0) =>
        _http.PutAsync($"/api/v1/sessions/{sid}/files/{index}?offset={offset}", new ByteArrayContent(data));

    [Fact]
    public async Task Info_RequiresKey()
    {
        using var anon = new HttpClient { BaseAddress = _http.BaseAddress };
        var res = await anon.GetAsync("/api/v1/info");
        Assert.Equal(HttpStatusCode.Unauthorized, res.StatusCode);
        var ok = await _http.GetFromJsonAsync<JsonElement>("/api/v1/info");
        Assert.Equal("windows", ok.GetProperty("platform").GetString());
    }

    [Fact]
    public async Task SenderPage_IsServed()
    {
        var html = await _http.GetStringAsync("/");
        Assert.Contains("/api/v1/sessions", html);
    }

    [Fact]
    public async Task MultipleFiles_ArrivePreservingBytesAndNames()
    {
        var a = RandomBytes(1_234_567); var b = RandomBytes(10); var c = Array.Empty<byte>();
        var s = await CreateSession(("IMG_0001.HEIC", a.Length), ("notes.txt", b.Length), ("empty.bin", 0));
        var sid = s.GetProperty("sessionId").GetString()!;
        Assert.Equal("accepted", s.GetProperty("status").GetString());

        Assert.True((await Put(sid, 0, a)).IsSuccessStatusCode);
        Assert.True((await Put(sid, 1, b)).IsSuccessStatusCode);
        Assert.True((await Put(sid, 2, c)).IsSuccessStatusCode);

        Assert.Equal(SHA256.HashData(a), SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(_folder, "IMG_0001.HEIC"))));
        Assert.Equal(b, await File.ReadAllBytesAsync(Path.Combine(_folder, "notes.txt")));
        Assert.True(File.Exists(Path.Combine(_folder, "empty.bin")));
        Assert.Equal(SessionStatus.Completed, _manager.Find(sid)!.Status);
        Assert.Empty(Directory.GetFiles(_folder, "*" + TransferManager.PartSuffix));
    }

    [Fact]
    public async Task ExistingFile_IsNeverOverwritten()
    {
        Directory.CreateDirectory(_folder);
        await File.WriteAllTextAsync(Path.Combine(_folder, "photo.jpg"), "original");
        var data = RandomBytes(100);
        var sid = (await CreateSession(("photo.jpg", 100))).GetProperty("sessionId").GetString()!;
        var res = await (await Put(sid, 0, data)).Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal("photo (1).jpg", res.GetProperty("savedAs").GetString());
        Assert.Equal("original", await File.ReadAllTextAsync(Path.Combine(_folder, "photo.jpg")));
    }

    [Fact]
    public async Task TraversalName_StaysInsideFolder()
    {
        var sid = (await CreateSession(("../../../outside.txt", 3))).GetProperty("sessionId").GetString()!;
        Assert.True((await Put(sid, 0, [1, 2, 3])).IsSuccessStatusCode);
        Assert.True(File.Exists(Path.Combine(_folder, "outside.txt")));
        Assert.False(File.Exists(Path.Combine(_folder, "..", "..", "..", "outside.txt")));
    }

    [Fact]
    public async Task ShortBody_ThenResumeAtOffset_ProducesIdenticalFile()
    {
        var data = RandomBytes(3_000_000);
        var sid = (await CreateSession(("video.mov", data.Length))).GetProperty("sessionId").GetString()!;

        var first = await Put(sid, 0, data[..1_000_000]);
        Assert.Equal(HttpStatusCode.BadRequest, first.StatusCode); // incomplete

        var status = await _http.GetFromJsonAsync<JsonElement>($"/api/v1/sessions/{sid}/files/0");
        Assert.Equal(1_000_000, status.GetProperty("received").GetInt64());

        var wrong = await Put(sid, 0, data[500_000..], offset: 500_000);
        Assert.Equal(HttpStatusCode.Conflict, wrong.StatusCode);

        Assert.True((await Put(sid, 0, data[1_000_000..], offset: 1_000_000)).IsSuccessStatusCode);
        Assert.Equal(SHA256.HashData(data), SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(_folder, "video.mov"))));
    }

    [Fact]
    public async Task DroppedConnection_KeepsPartialData_AndResumes()
    {
        var data = RandomBytes(8 * 1024 * 1024);
        var sid = (await CreateSession(("big.bin", data.Length))).GetProperty("sessionId").GetString()!;

        // Stream that sends ~3 MB, then fails like a lost Wi-Fi connection.
        var content = new StreamContent(new FailingStream(data, failAfter: 3 * 1024 * 1024));
        await Assert.ThrowsAnyAsync<Exception>(() => _http.PutAsync($"/api/v1/sessions/{sid}/files/0?offset=0", content));

        long received = 0;
        for (var i = 0; i < 50; i++)
        {
            var st = await _http.GetFromJsonAsync<JsonElement>($"/api/v1/sessions/{sid}/files/0");
            received = st.GetProperty("received").GetInt64();
            if (st.GetProperty("status").GetString() != "receiving") break;
            await Task.Delay(100);
        }
        Assert.InRange(received, 1, data.Length - 1);

        var res = await Put(sid, 0, data[(int)received..], offset: received);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        Assert.Equal(SHA256.HashData(data), SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(_folder, "big.bin"))));
    }

    [Fact]
    public async Task ReceivingDisabled_Rejects()
    {
        _settings.ReceivingEnabled = false;
        var res = await _http.PostAsJsonAsync("/api/v1/sessions", new { sender = "x", files = new[] { new { name = "a", size = 1 } } });
        Assert.Equal(HttpStatusCode.ServiceUnavailable, res.StatusCode);
        Assert.Contains("receiving_disabled", await res.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Approval_DeclineBlocksUpload()
    {
        _settings.RequireApproval = true;
        TransferSession? requested = null;
        _manager.ApprovalRequested += s => requested = s;

        var s = await CreateSession(("a.txt", 1));
        var sid = s.GetProperty("sessionId").GetString()!;
        Assert.Equal("pending", s.GetProperty("status").GetString());
        Assert.NotNull(requested);

        _manager.Decline(sid);
        var st = await _http.GetFromJsonAsync<JsonElement>($"/api/v1/sessions/{sid}");
        Assert.Equal("declined", st.GetProperty("status").GetString());
        Assert.Equal(HttpStatusCode.Forbidden, (await Put(sid, 0, [1])).StatusCode);
    }

    [Fact]
    public async Task Approval_AcceptAllowsUpload()
    {
        _settings.RequireApproval = true;
        _manager.ApprovalRequested += s => _manager.Accept(s.Id);
        var sid = (await CreateSession(("a.txt", 1))).GetProperty("sessionId").GetString()!;
        Assert.True((await Put(sid, 0, [42])).IsSuccessStatusCode);
    }

    [Fact]
    public async Task Cancel_DuringTransfer_RemovesPartialFile()
    {
        var data = RandomBytes(4 * 1024 * 1024);
        var sid = (await CreateSession(("cancel.bin", data.Length))).GetProperty("sessionId").GetString()!;
        _manager.Pause(sid);
        var upload = Put(sid, 0, data);
        await Task.Delay(300);
        _manager.Cancel(sid);
        var res = await upload;
        Assert.False(res.IsSuccessStatusCode);
        await Task.Delay(200);
        Assert.Empty(Directory.GetFiles(_folder));
        Assert.Equal(SessionStatus.Canceled, _manager.Find(sid)!.Status);
    }

    [Fact]
    public async Task PauseAndResume_CompletesTransfer()
    {
        var data = RandomBytes(2 * 1024 * 1024);
        var sid = (await CreateSession(("paused.bin", data.Length))).GetProperty("sessionId").GetString()!;
        _manager.Pause(sid);
        var upload = Put(sid, 0, data);
        await Task.Delay(500);
        Assert.False(upload.IsCompleted);
        _manager.Resume(sid);
        Assert.True((await upload).IsSuccessStatusCode);
        Assert.Equal(data, await File.ReadAllBytesAsync(Path.Combine(_folder, "paused.bin")));
    }

    [Fact]
    public async Task ShortcutsMultipartUpload_SavesWithOriginalName()
    {
        var a = RandomBytes(50_000);
        var b = RandomBytes(70_000);
        using var form = new MultipartFormDataContent
        {
            { new ByteArrayContent(a), "file", "IMG_4242.HEIC" },
            { new ByteArrayContent(b), "file", "Clip.MOV" },
        };
        var res = await _http.PostAsync($"/api/v1/upload?k={_settings.PairingKey}", form);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        Assert.Equal(a, await File.ReadAllBytesAsync(Path.Combine(_folder, "IMG_4242.HEIC")));
        Assert.Equal(b, await File.ReadAllBytesAsync(Path.Combine(_folder, "Clip.MOV")));

        // A second request from the same device joins the same transfer.
        var res2 = await _http.PostAsync("/api/v1/upload?name=second.txt", new ByteArrayContent([1, 2, 3]));
        var j1 = await res.Content.ReadFromJsonAsync<JsonElement>();
        var j2 = await res2.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(j1.GetProperty("sessionId").GetString(), j2.GetProperty("sessionId").GetString());
    }

    [Fact]
    public async Task QrUrl_AcceptsShortcutUpload_AndRequiresKey()
    {
        var data = RandomBytes(1000);
        using var anon = new HttpClient { BaseAddress = _http.BaseAddress };
        using var form1 = new MultipartFormDataContent { { new ByteArrayContent(data), "file", "IMG_9000.MOV" } };
        Assert.Equal(HttpStatusCode.Unauthorized, (await anon.PostAsync("/?k=WRONGKEY", form1)).StatusCode);

        using var form2 = new MultipartFormDataContent { { new ByteArrayContent(data), "file", "IMG_9000.MOV" } };
        var res = await anon.PostAsync($"/?k={_settings.PairingKey}", form2);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        Assert.Equal(data, await File.ReadAllBytesAsync(Path.Combine(_folder, "IMG_9000.MOV")));
    }

    [Fact]
    public async Task ReceivedFile_GetsMarkOfTheWeb()
    {
        var sid = (await CreateSession(("setup.exe", 4))).GetProperty("sessionId").GetString()!;
        Assert.True((await Put(sid, 0, [0x4D, 0x5A, 0, 0])).IsSuccessStatusCode);
        var zone = await File.ReadAllTextAsync(Path.Combine(_folder, "setup.exe") + ":Zone.Identifier");
        Assert.Contains("ZoneId=3", zone);
    }

    [Fact]
    public async Task UntrustedNetwork_BlocksApi()
    {
        // Loopback is always trusted, so connect via a LAN address of this machine.
        if (Environment.GetEnvironmentVariable("CI") == "true") return; // hosted runners: no usable LAN address
        var lan = NetworkUtil.GetLanAddresses().FirstOrDefault();
        if (lan is null) return; // no network adapter in this environment
        _server.IsTrustedNetwork = _ => false;
        using var client = new HttpClient { BaseAddress = new Uri($"http://{lan}:{_server.Port}") };
        client.DefaultRequestHeaders.Add(ProtocolConstants.KeyHeader, _settings.PairingKey);
        var res = await client.GetAsync("/api/v1/info");
        Assert.Equal(HttpStatusCode.Forbidden, res.StatusCode);
        Assert.Contains(ErrorCodes.UntrustedNetwork, await res.Content.ReadAsStringAsync());

        _server.IsTrustedNetwork = _ => true;
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/v1/info")).StatusCode);
    }

    [Fact]
    public async Task MoreDataThanDeclared_IsRejected()
    {
        var sid = (await CreateSession(("small.bin", 10))).GetProperty("sessionId").GetString()!;
        // The server stops reading mid-body: the client sees either the 400 or a reset connection.
        try
        {
            var res = await Put(sid, 0, RandomBytes(100));
            Assert.Equal(HttpStatusCode.BadRequest, res.StatusCode);
        }
        catch (HttpRequestException) { }
        Assert.False(File.Exists(Path.Combine(_folder, "small.bin")));
    }

    private sealed class FailingStream(byte[] data, int failAfter) : Stream
    {
        private int _pos;
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_pos >= failAfter) throw new IOException("Simulated Wi-Fi drop");
            var n = Math.Min(Math.Min(count, failAfter - _pos), 64 * 1024);
            Array.Copy(data, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }
        public override bool CanRead => true; public override bool CanSeek => false; public override bool CanWrite => false;
        public override long Length => data.Length; public override long Position { get => _pos; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
    }
}
