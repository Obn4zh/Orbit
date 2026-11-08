using System.Windows.Input;
using Orbit.Core;
using Orbit.Windows.Services;

namespace Orbit.Windows.ViewModels;

public enum SessionKind { Pending, Active, Paused, Completed, Failed, Canceled }

/// <summary>One transfer card in the Activity list. Refreshed by a UI timer from the core session.</summary>
public sealed class SessionViewModel : ObservableObject
{
    private readonly TransferManager _manager;
    private readonly Queue<(DateTime t, long bytes)> _samples = new();

    public SessionViewModel(TransferSession session, TransferManager manager)
    {
        Session = session;
        _manager = manager;
        PauseCommand = new RelayCommand(() => { _manager.Pause(Session.Id); Update(); });
        ResumeCommand = new RelayCommand(() => { _manager.Resume(Session.Id); Update(); });
        CancelCommand = new RelayCommand(() => { _manager.Cancel(Session.Id); Update(); });
        AcceptCommand = new RelayCommand(() => { _manager.Accept(Session.Id); Update(); });
        DeclineCommand = new RelayCommand(() => { _manager.Decline(Session.Id); Update(); });
        OpenCommand = new RelayCommand(() =>
        {
            var last = Session.Files.LastOrDefault(f => f.Status == FileStatus.Done);
            Platform.OpenFolder(_manager.DownloadFolder, last?.FinalPath);
        });
        Update();
    }

    public TransferSession Session { get; }
    public ICommand PauseCommand { get; }
    public ICommand ResumeCommand { get; }
    public ICommand CancelCommand { get; }
    public ICommand AcceptCommand { get; }
    public ICommand DeclineCommand { get; }
    public ICommand OpenCommand { get; }

    private SessionKind _kind;
    public SessionKind Kind { get => _kind; private set => Set(ref _kind, value); }

    private string _title = "";
    public string Title { get => _title; private set => Set(ref _title, value); }

    private string _subtitle = "";
    public string Subtitle { get => _subtitle; private set => Set(ref _subtitle, value); }

    private string _detail = "";
    public string Detail { get => _detail; private set => Set(ref _detail, value); }

    private double _progress;
    public double Progress { get => _progress; private set => Set(ref _progress, value); }

    private string _glyph = "";
    public string Glyph { get => _glyph; private set => Set(ref _glyph, value); }

    private string _approvalSummary = "";
    public string ApprovalSummary { get => _approvalSummary; private set => Set(ref _approvalSummary, value); }

    private string _approvalFiles = "";
    public string ApprovalFiles { get => _approvalFiles; private set => Set(ref _approvalFiles, value); }

    private string _countdown = "";
    public string Countdown { get => _countdown; private set => Set(ref _countdown, value); }

    public bool CanPause => Kind == SessionKind.Active;
    public bool CanResume => Kind == SessionKind.Paused;
    public bool CanCancel => Kind is SessionKind.Active or SessionKind.Paused;
    public bool CanOpen => Session.Files.Any(f => f.Status == FileStatus.Done);
    public bool ShowProgress => Kind is SessionKind.Active or SessionKind.Paused;

    public void Update()
    {
        var s = Session;
        var files = s.Files;
        var done = files.Count(f => f.Status == FileStatus.Done);
        var total = s.TotalBytes;
        var received = s.ReceivedBytes;

        Kind = s.Status switch
        {
            SessionStatus.Pending => SessionKind.Pending,
            SessionStatus.Completed => SessionKind.Completed,
            SessionStatus.Failed => SessionKind.Failed,
            SessionStatus.Declined or SessionStatus.Expired or SessionStatus.Canceled => SessionKind.Canceled,
            _ => s.IsPaused ? SessionKind.Paused : SessionKind.Active,
        };

        var items = files.Count == 1 ? "1 item" : $"{files.Count} items";
        Title = files.Count == 1 ? files[0].SafeName : $"{items} from {s.Sender}";
        Glyph = files.Count == 1 ? GlyphFor(files[0]) : "";
        Progress = total > 0 ? Math.Clamp(received * 100.0 / total, 0, 100) : (Kind == SessionKind.Completed ? 100 : 0);

        // Speed over a sliding ~3 s window.
        var now = DateTime.UtcNow;
        _samples.Enqueue((now, received));
        while (_samples.Count > 2 && (now - _samples.Peek().t).TotalSeconds > 3) _samples.Dequeue();
        var first = _samples.Peek();
        var span = (now - first.t).TotalSeconds;
        var speed = span > 0.3 ? (received - first.bytes) / span : 0;

        switch (Kind)
        {
            case SessionKind.Pending:
                Subtitle = $"{s.Sender} · waiting for you to accept";
                Detail = $"{items} · {Format.Bytes(total)}";
                break;
            case SessionKind.Active:
                var current = files.FirstOrDefault(f => f.Status == FileStatus.Receiving) ?? files.FirstOrDefault(f => f.Status == FileStatus.Queued);
                Subtitle = files.Count == 1 ? $"From {s.Sender}" : $"{done} of {files.Count} · {current?.SafeName ?? "finishing"}";
                Detail = speed > 0
                    ? $"{Format.Bytes(received)} of {Format.Bytes(total)} · {Format.Bytes(speed)}/s · {Format.Duration((total - received) / speed)} left"
                    : $"{Format.Bytes(received)} of {Format.Bytes(total)} · waiting for data";
                break;
            case SessionKind.Paused:
                Subtitle = "Paused";
                Detail = $"{Format.Bytes(received)} of {Format.Bytes(total)}";
                break;
            case SessionKind.Completed:
                var elapsed = ((s.FinishedAt ?? DateTimeOffset.Now) - s.CreatedAt).TotalSeconds;
                Subtitle = files.Count == 1 ? $"From {s.Sender} · {Format.Bytes(total)}" : $"{Format.Bytes(total)} · saved to your folder";
                Detail = $"Received {s.FinishedAt?.ToLocalTime():HH:mm} · {Format.Duration(elapsed)}";
                break;
            case SessionKind.Failed:
                Subtitle = s.Error ?? "Transfer failed";
                Detail = $"{done} of {files.Count} saved";
                break;
            default:
                Subtitle = s.Status switch
                {
                    SessionStatus.Declined => "Declined",
                    SessionStatus.Expired => "Not accepted in time",
                    _ => "Canceled",
                };
                Detail = done > 0 ? $"{done} of {files.Count} saved" : $"From {s.Sender}";
                break;
        }

        if (s.AdHoc && files.Count == 0)
        {
            // Shortcuts upload: the files arrive only after approval.
            ApprovalSummary = $"{s.Sender} wants to send files";
            ApprovalFiles = "From the iPhone Share Sheet. Accepting receives the items of this share; a later share asks again.";
        }
        else
        {
            ApprovalSummary = $"{s.Sender} wants to send {items} ({Format.Bytes(total)})";
            var names = files.Take(4).Select(f => "•  " + f.SafeName).ToList();
            if (files.Count > 4) names.Add($"   and {files.Count - 4} more");
            ApprovalFiles = string.Join("\n", names);
        }
        var left = ProtocolConstants.ApprovalTimeout - (DateTimeOffset.Now - s.CreatedAt);
        Countdown = $"Expires in {Math.Max(0, (int)left.TotalSeconds)}s";

        Raise(nameof(CanPause)); Raise(nameof(CanResume)); Raise(nameof(CanCancel)); Raise(nameof(CanOpen)); Raise(nameof(ShowProgress));
    }

    private static string GlyphFor(TransferFile f)
    {
        var ext = Path.GetExtension(f.SafeName).ToLowerInvariant();
        return ext switch
        {
            ".jpg" or ".jpeg" or ".png" or ".heic" or ".heif" or ".gif" or ".webp" or ".dng" or ".tif" or ".tiff" or ".bmp" => "",
            ".mov" or ".mp4" or ".m4v" or ".avi" or ".mkv" or ".webm" => "",
            ".mp3" or ".m4a" or ".wav" or ".aac" or ".flac" => "",
            ".zip" or ".7z" or ".rar" => "",
            _ => "",
        };
    }
}
