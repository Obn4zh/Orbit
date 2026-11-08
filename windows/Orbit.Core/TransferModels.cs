using System.Net;

namespace Orbit.Core;

public enum SessionStatus { Pending, Accepted, Active, Completed, Declined, Expired, Canceled, Failed }

public enum FileStatus { Queued, Receiving, Done, Failed, Canceled }

public sealed class TransferFile
{
    private long _received;

    public required int Index { get; init; }
    public required string OriginalName { get; init; }
    /// <summary>Sanitized leaf name requested by the sender.</summary>
    public required string SafeName { get; init; }
    /// <summary>Declared size in bytes, or -1 when unknown (multipart uploads from Shortcuts).</summary>
    public long Size { get; internal set; }
    public string MimeType { get; init; } = "";
    public string? FinalPath { get; internal set; }
    public string? PartPath { get; internal set; }
    public FileStatus Status { get; internal set; } = FileStatus.Queued;
    public string? Error { get; internal set; }

    public long Received => Interlocked.Read(ref _received);
    internal void AddReceived(long n) => Interlocked.Add(ref _received, n);
    internal void SetReceived(long n) => Interlocked.Exchange(ref _received, n);

    /// <summary>Serializes writers: a retried PUT must wait for a stale one to finish.</summary>
    internal SemaphoreSlim WriteLock { get; } = new(1, 1);
}

public sealed class TransferSession
{
    private readonly List<TransferFile> _files = [];
    internal readonly Lock Sync = new();

    public required string Id { get; init; }
    public required string Sender { get; init; }
    public required IPAddress Remote { get; init; }
    public bool AdHoc { get; init; }
    public DateTimeOffset CreatedAt { get; } = DateTimeOffset.Now;
    public DateTimeOffset LastActivity { get; internal set; } = DateTimeOffset.Now;
    public DateTimeOffset? FinishedAt { get; internal set; }
    public SessionStatus Status { get; internal set; } = SessionStatus.Pending;
    public bool IsPaused => !Gate.IsOpen;
    public string? Error { get; internal set; }

    internal TaskCompletionSource<bool> Decision { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    internal CancellationTokenSource Cancellation { get; } = new();
    internal AsyncGate Gate { get; } = new();

    public IReadOnlyList<TransferFile> Files { get { lock (Sync) return _files.ToList(); } }
    internal void AddFile(TransferFile f) { lock (Sync) _files.Add(f); }
    internal TransferFile? GetFile(int index) { lock (Sync) return index >= 0 && index < _files.Count ? _files[index] : null; }
    internal int FileCount { get { lock (Sync) return _files.Count; } }

    public long TotalBytes => Files.Sum(f => f.Size >= 0 ? f.Size : f.Received);
    public long ReceivedBytes => Files.Sum(f => f.Received);
    public bool IsFinished => Status is SessionStatus.Completed or SessionStatus.Declined or SessionStatus.Expired or SessionStatus.Canceled or SessionStatus.Failed;
}

/// <summary>A manual-reset gate that async code can await. Used for pausing transfers.</summary>
public sealed class AsyncGate
{
    private volatile TaskCompletionSource _tcs = Completed();

    private static TaskCompletionSource Completed()
    {
        var t = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        t.SetResult();
        return t;
    }

    public bool IsOpen => _tcs.Task.IsCompleted;

    public void Close()
    {
        if (_tcs.Task.IsCompleted) _tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    public void Open() => _tcs.TrySetResult();

    public ValueTask WaitAsync(CancellationToken ct)
    {
        var task = _tcs.Task;
        return task.IsCompleted ? ValueTask.CompletedTask : new ValueTask(task.WaitAsync(ct));
    }
}

public sealed class TransferException(int httpStatus, string code, string message) : Exception(message)
{
    public int HttpStatus { get; } = httpStatus;
    public string Code { get; } = code;
}

public sealed record IncomingFile(string Name, long Size, string? Type);
