using System.Buffers;
using System.Collections.Concurrent;
using System.Net;

namespace Orbit.Core;

/// <summary>
/// Owns transfer sessions: approval, name reservation, streaming bytes to disk, pause/cancel, resume.
/// UI-agnostic; the WPF layer subscribes to events and polls progress.
/// </summary>
public sealed class TransferManager
{
    public const string PartSuffix = ".orbitpart";
    private const int BufferSize = 256 * 1024;
    private const int MaxHistory = 100;

    private readonly ConcurrentDictionary<string, TransferSession> _sessions = new();
    private readonly HashSet<string> _reservedPaths = new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _reserveLock = new();
    private readonly Func<ReceiverSettings> _settings;

    public TransferManager(Func<ReceiverSettings> settings) { _settings = settings; }

    /// <summary>Raised when a session needs the user to accept or decline it.</summary>
    public event Action<TransferSession>? ApprovalRequested;
    /// <summary>Raised on any session status change (created, accepted, completed, failed...).</summary>
    public event Action<TransferSession>? SessionChanged;
    public event Action<TransferSession, TransferFile>? FileCompleted;

    public IReadOnlyList<TransferSession> Sessions => _sessions.Values.OrderByDescending(s => s.CreatedAt).ToList();
    public TransferSession? Find(string id) => _sessions.TryGetValue(id, out var s) ? s : null;

    public string DownloadFolder
    {
        get
        {
            var folder = _settings().DownloadFolder;
            if (string.IsNullOrWhiteSpace(folder))
                folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Orbit");
            return folder;
        }
    }

    // ---------------------------------------------------------------- sessions

    public TransferSession CreateSession(string sender, IPAddress remote, IReadOnlyList<IncomingFile> files, bool adHoc = false)
    {
        var settings = _settings();
        if (!settings.ReceivingEnabled) throw new TransferException(503, ErrorCodes.ReceivingDisabled, "Receiving is turned off.");
        if (!adHoc && files.Count == 0) throw new TransferException(400, ErrorCodes.InvalidRequest, "No files.");
        if (files.Count > ProtocolConstants.MaxFilesPerSession) throw new TransferException(400, ErrorCodes.InvalidRequest, "Too many files.");
        if (files.Any(f => f.Size < 0 || f.Size > 4L * 1024 * 1024 * 1024 * 1024)) throw new TransferException(400, ErrorCodes.InvalidRequest, "Invalid file size.");

        EnsureFreeSpace(files.Sum(f => f.Size));

        var session = new TransferSession
        {
            Id = Guid.NewGuid().ToString("N")[..16],
            Sender = Truncate(string.IsNullOrWhiteSpace(sender) ? "Unknown device" : sender.Trim(), 40),
            Remote = remote,
            AdHoc = adHoc,
        };
        for (var i = 0; i < files.Count; i++)
            session.AddFile(NewFile(i, files[i].Name, files[i].Size, files[i].Type));

        _sessions[session.Id] = session;
        TrimHistory();

        if (settings.RequireApproval)
        {
            session.Status = SessionStatus.Pending;
            _ = ExpireLaterAsync(session);
            SessionChanged?.Invoke(session);
            ApprovalRequested?.Invoke(session);
        }
        else
        {
            Accept(session.Id);
        }
        return session;
    }

    /// <summary>Shortcuts sends one request per item; group requests from one address into one session.</summary>
    public TransferSession GetOrCreateAdHocSession(string sender, IPAddress remote)
    {
        var existing = _sessions.Values
            .Where(s => s.AdHoc && s.Remote.Equals(remote) && s.Status is SessionStatus.Accepted or SessionStatus.Active or SessionStatus.Completed or SessionStatus.Pending)
            .Where(s => DateTimeOffset.Now - s.LastActivity < ProtocolConstants.AdHocTrustWindow)
            .OrderByDescending(s => s.LastActivity)
            .FirstOrDefault();
        if (existing is not null)
        {
            if (existing.Status == SessionStatus.Completed) SetStatus(existing, SessionStatus.Active);
            existing.LastActivity = DateTimeOffset.Now;
            return existing;
        }
        return CreateSession(sender, remote, [], adHoc: true);
    }

    public TransferFile AddAdHocFile(TransferSession session, string name, long size, string? type)
    {
        if (size > 0) EnsureFreeSpace(size);
        TransferFile file;
        lock (session.Sync)
        {
            file = NewFile(session.FileCount, name, size, type);
            session.AddFile(file);
        }
        if (session.Status is SessionStatus.Accepted or SessionStatus.Active) Reserve(file);
        session.LastActivity = DateTimeOffset.Now;
        SessionChanged?.Invoke(session);
        return file;
    }

    public async Task<bool> WaitForDecisionAsync(TransferSession session, CancellationToken ct)
    {
        if (session.Status != SessionStatus.Pending) return session.Status is SessionStatus.Accepted or SessionStatus.Active or SessionStatus.Completed;
        try { return await session.Decision.Task.WaitAsync(ProtocolConstants.ApprovalTimeout, ct); }
        catch (TimeoutException) { Expire(session); return false; }
    }

    public void Accept(string id)
    {
        var s = Find(id);
        if (s is null || (s.Status != SessionStatus.Pending && s.Status != SessionStatus.Accepted)) return;
        foreach (var f in s.Files) Reserve(f);
        SetStatus(s, SessionStatus.Accepted);
        s.Decision.TrySetResult(true);
    }

    public void Decline(string id)
    {
        var s = Find(id);
        if (s is null || s.Status != SessionStatus.Pending) return;
        SetStatus(s, SessionStatus.Declined);
        s.FinishedAt = DateTimeOffset.Now;
        s.Decision.TrySetResult(false);
    }

    public void Pause(string id) { var s = Find(id); if (s is { IsFinished: false }) { s.Gate.Close(); SessionChanged?.Invoke(s); } }
    public void Resume(string id) { var s = Find(id); if (s is not null) { s.Gate.Open(); SessionChanged?.Invoke(s); } }

    public void Cancel(string id)
    {
        var s = Find(id);
        if (s is null || s.IsFinished) return;
        if (s.Status == SessionStatus.Pending) s.Decision.TrySetResult(false);
        SetStatus(s, SessionStatus.Canceled);
        s.FinishedAt = DateTimeOffset.Now;
        s.Cancellation.Cancel();
        s.Gate.Open();
        foreach (var f in s.Files.Where(f => f.Status != FileStatus.Done))
        {
            f.Status = FileStatus.Canceled;
            // Writers delete their own .part on cancellation; clean up files that no writer holds.
            if (f.WriteLock.Wait(0))
            {
                try { DeletePart(f); } finally { f.WriteLock.Release(); }
            }
        }
    }

    /// <summary>Removes finished sessions from the activity list.</summary>
    public void ClearFinished()
    {
        foreach (var s in _sessions.Values.Where(s => s.IsFinished).ToList()) _sessions.TryRemove(s.Id, out _);
    }

    // ---------------------------------------------------------------- streaming

    public enum ReceiveResult { Done, Incomplete }

    /// <summary>Streams <paramref name="body"/> into the file starting at <paramref name="offset"/>.</summary>
    public async Task<ReceiveResult> ReceiveAsync(TransferSession session, TransferFile file, Stream body, long offset, CancellationToken requestAborted)
    {
        ThrowIfNotWritable(session);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(requestAborted, session.Cancellation.Token);
        var ct = linked.Token;

        await file.WriteLock.WaitAsync(ct);
        try
        {
            ThrowIfNotWritable(session);
            if (file.Status == FileStatus.Done) return ReceiveResult.Done;
            if (file.Status == FileStatus.Canceled) throw new TransferException(410, ErrorCodes.Canceled, "Canceled.");
            if (offset != file.Received)
                throw new TransferException(409, ErrorCodes.OffsetMismatch, $"Expected offset {file.Received}.");
            if (file.PartPath is null) Reserve(file);

            if (session.Status == SessionStatus.Accepted) SetStatus(session, SessionStatus.Active);
            file.Status = FileStatus.Receiving;
            file.Error = null;
            session.LastActivity = DateTimeOffset.Now;

            var buffer = ArrayPool<byte>.Shared.Rent(BufferSize);
            try
            {
                var fs = Local(() => new FileStream(file.PartPath!, offset == 0 ? FileMode.Create : FileMode.OpenOrCreate,
                                 FileAccess.Write, FileShare.Read, 1 << 20, FileOptions.Asynchronous | FileOptions.SequentialScan));
                await using (fs)
                {
                    if (offset > 0) Local(() => { fs.SetLength(offset); fs.Seek(offset, SeekOrigin.Begin); return 0; });
                    while (true)
                    {
                        await session.Gate.WaitAsync(ct);
                        var n = await body.ReadAsync(buffer.AsMemory(0, BufferSize), ct);
                        if (n == 0) break;
                        if (file.Size >= 0 && file.Received + n > file.Size)
                            throw new TransferException(400, ErrorCodes.InvalidRequest, "More data than declared.");
                        try { await fs.WriteAsync(buffer.AsMemory(0, n), ct); }
                        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new LocalIoException(ex); }
                        file.AddReceived(n);
                        session.LastActivity = DateTimeOffset.Now;
                    }
                    try { await fs.FlushAsync(CancellationToken.None); }
                    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new LocalIoException(ex); }
                }
            }
            finally { ArrayPool<byte>.Shared.Return(buffer); }

            if (file.Size >= 0 && file.Received < file.Size) return ReceiveResult.Incomplete;

            if (file.Size < 0) file.Size = file.Received;
            Local(() => { Finalize(file); return 0; });
            file.Status = FileStatus.Done;
            FileCompleted?.Invoke(session, file);
            CompleteIfDone(session);
            return ReceiveResult.Done;
        }
        catch (OperationCanceledException) when (session.Cancellation.IsCancellationRequested)
        {
            file.Status = FileStatus.Canceled;
            DeletePart(file);
            throw new TransferException(410, ErrorCodes.Canceled, "Canceled by the receiver.");
        }
        catch (LocalIoException ex) when (ex.InnerException is IOException io && IsDiskFull(io))
        {
            file.Status = FileStatus.Failed;
            file.Error = "Not enough disk space";
            Fail(session, file.Error);
            throw new TransferException(507, ErrorCodes.InsufficientStorage, "Not enough disk space.");
        }
        catch (LocalIoException ex)
        {
            // Local file-system failure (permissions, locked file...), not a network problem.
            file.Status = FileStatus.Failed;
            file.Error = ex.InnerException!.Message;
            Fail(session, file.Error);
            throw new TransferException(500, ErrorCodes.WriteFailed, "Could not write the file.");
        }
        catch (TransferException)
        {
            throw;
        }
        catch (Exception)
        {
            // Sender disconnected or network dropped: keep the .part so the sender can resume.
            if (file.Status == FileStatus.Receiving) file.Status = FileStatus.Queued;
            throw;
        }
        finally
        {
            file.WriteLock.Release();
        }
    }

    // ---------------------------------------------------------------- helpers

    private TransferFile NewFile(int index, string name, long size, string? type) => new()
    {
        Index = index,
        OriginalName = Truncate(name ?? "", 255),
        SafeName = FileNameSanitizer.Sanitize(name),
        Size = size,
        MimeType = type ?? "",
    };

    private void Reserve(TransferFile file)
    {
        if (file.PartPath is not null) return;
        var folder = DownloadFolder;
        try { Directory.CreateDirectory(folder); }
        catch (Exception ex) { throw new TransferException(500, ErrorCodes.WriteFailed, "Download folder is not writable: " + ex.Message); }

        lock (_reserveLock)
        {
            var name = FileNameSanitizer.MakeUnique(file.SafeName, candidate =>
            {
                var p = Path.Combine(folder, candidate);
                return _reservedPaths.Contains(p) || File.Exists(p) || File.Exists(p + PartSuffix) || Directory.Exists(p);
            });
            var final = Path.GetFullPath(Path.Combine(folder, name));
            // Defense in depth: the resolved path must stay inside the download folder.
            if (!final.StartsWith(Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new TransferException(400, ErrorCodes.InvalidRequest, "Invalid file name.");
            _reservedPaths.Add(final);
            file.FinalPath = final;
            file.PartPath = final + PartSuffix;
        }
    }

    private void Finalize(TransferFile file)
    {
        lock (_reserveLock)
        {
            var target = file.FinalPath!;
            if (File.Exists(target))
            {
                // Something appeared at that name meanwhile: never overwrite, pick a fresh name.
                var dir = Path.GetDirectoryName(target)!;
                var name = FileNameSanitizer.MakeUnique(Path.GetFileName(target), c => File.Exists(Path.Combine(dir, c)) || _reservedPaths.Contains(Path.Combine(dir, c)));
                target = Path.Combine(dir, name);
            }
            File.Move(file.PartPath!, target, overwrite: false);
            _reservedPaths.Remove(file.FinalPath!);
            file.FinalPath = target;
        }
        MarkAsFromInternet(file.FinalPath!);
    }

    /// <summary>
    /// Adds the Windows "Mark of the Web" (Zone.Identifier, Internet zone) like a browser download does, so
    /// SmartScreen, Office Protected View and script hosts warn before running a received file.
    /// </summary>
    public static void MarkAsFromInternet(string path)
    {
        if (!OperatingSystem.IsWindows()) return;
        try { File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=about:internet\r\n"); }
        catch { /* non-NTFS volumes (FAT/exFAT USB drives) have no alternate data streams */ }
    }

    private void DeletePart(TransferFile file)
    {
        try { if (file.PartPath is not null && File.Exists(file.PartPath)) File.Delete(file.PartPath); } catch { /* best effort */ }
        lock (_reserveLock) { if (file.FinalPath is not null) _reservedPaths.Remove(file.FinalPath); }
    }

    private void CompleteIfDone(TransferSession s)
    {
        if (s.Files.All(f => f.Status == FileStatus.Done) && s.Status is SessionStatus.Active or SessionStatus.Accepted)
        {
            s.FinishedAt = DateTimeOffset.Now;
            SetStatus(s, SessionStatus.Completed);
        }
    }

    private void Fail(TransferSession s, string error)
    {
        s.Error = error;
        s.FinishedAt = DateTimeOffset.Now;
        SetStatus(s, SessionStatus.Failed);
    }

    private void Expire(TransferSession s)
    {
        if (s.Status != SessionStatus.Pending) return;
        SetStatus(s, SessionStatus.Expired);
        s.FinishedAt = DateTimeOffset.Now;
        s.Decision.TrySetResult(false);
    }

    private async Task ExpireLaterAsync(TransferSession s)
    {
        await Task.Delay(ProtocolConstants.ApprovalTimeout);
        Expire(s);
    }

    private void SetStatus(TransferSession s, SessionStatus status)
    {
        s.Status = status;
        s.LastActivity = DateTimeOffset.Now;
        SessionChanged?.Invoke(s);
    }

    private static void ThrowIfNotWritable(TransferSession s)
    {
        switch (s.Status)
        {
            case SessionStatus.Accepted or SessionStatus.Active or SessionStatus.Completed: return;
            case SessionStatus.Pending: throw new TransferException(409, "pending", "Waiting for approval.");
            case SessionStatus.Declined: throw new TransferException(403, ErrorCodes.Declined, "Declined.");
            case SessionStatus.Expired: throw new TransferException(403, ErrorCodes.Expired, "Approval timed out.");
            case SessionStatus.Canceled: throw new TransferException(410, ErrorCodes.Canceled, "Canceled.");
            default: throw new TransferException(410, ErrorCodes.Canceled, s.Error ?? "Transfer failed.");
        }
    }

    private void EnsureFreeSpace(long bytes)
    {
        if (bytes <= 0) return;
        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(DownloadFolder));
            if (string.IsNullOrEmpty(root)) return;
            var drive = new DriveInfo(root);
            if (drive.IsReady && drive.AvailableFreeSpace < bytes + 64L * 1024 * 1024)
                throw new TransferException(507, ErrorCodes.InsufficientStorage, "Not enough disk space.");
        }
        catch (TransferException) { throw; }
        catch { /* network drives etc.: let the write fail later if needed */ }
    }

    private static bool IsDiskFull(IOException ex) => (ex.HResult & 0xFFFF) is 0x70 or 0x27;

    private sealed class LocalIoException(Exception inner) : Exception(inner.Message, inner);

    private static T Local<T>(Func<T> action)
    {
        try { return action(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new LocalIoException(ex); }
    }

    private void TrimHistory()
    {
        var finished = _sessions.Values.Where(s => s.IsFinished).OrderByDescending(s => s.CreatedAt).Skip(MaxHistory).ToList();
        foreach (var s in finished) _sessions.TryRemove(s.Id, out _);
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..max];
}
