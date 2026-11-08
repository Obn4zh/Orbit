using System.Collections.ObjectModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Orbit.Core;
using Orbit.Windows.Services;

namespace Orbit.Windows.ViewModels;

public sealed class MainViewModel : ObservableObject, IDisposable
{
    private readonly ReceiverSettings _settings;
    private readonly SettingsStore _store;
    private readonly TransferManager _manager;
    private readonly ReceiverServer _server;
    private readonly MdnsAdvertiser _mdns = new();
    private readonly NetworkTrust _trust = new();
    private readonly DispatcherTimer _timer;
    private int _ticks;
    private IReadOnlyList<IPAddress> _addresses = [];
    private int _addressIndex;

    /// <summary>Raised on the UI thread: (title, message, isApproval).</summary>
    public event Action<string, string, bool>? Notify;

    public MainViewModel(ReceiverSettings settings, SettingsStore store, TransferManager manager, ReceiverServer server)
    {
        _settings = settings;
        _store = store;
        _manager = manager;
        _server = server;

        EditNameCommand = new RelayCommand(() => { EditName = DeviceName; IsEditingName = true; });
        SaveNameCommand = new RelayCommand(SaveName);
        CancelEditNameCommand = new RelayCommand(() => IsEditingName = false);
        ChangeFolderCommand = new RelayCommand(ChangeFolder);
        OpenFolderCommand = new RelayCommand(() => Platform.OpenFolder(DownloadFolder));
        NewKeyCommand = new RelayCommand(NewKey);
        CopyUrlCommand = new RelayCommand(() => { try { Clipboard.SetText(ShareUrl); CopyLabel = "Copied"; } catch { CopyLabel = "Copy failed"; } RestoreCopyLabel(); });
        NextNetworkCommand = new RelayCommand(() => { if (_addresses.Count > 0) { _addressIndex = (_addressIndex + 1) % _addresses.Count; UpdateAddress(); } });
        ShowGuideCommand = new RelayCommand(() => ShowGuide = true);
        CloseGuideCommand = new RelayCommand(() => ShowGuide = false);
        FinishOnboardingCommand = new RelayCommand(() => { ShowOnboarding = false; _settings.OnboardingDone = true; Save(); });
        ClearFinishedCommand = new RelayCommand(() => { _manager.ClearFinished(); Sync(); });
        AllowFirewallCommand = new RelayCommand(() =>
        {
            var ok = Platform.AllowThroughFirewall();
            Notify?.Invoke(ok ? "Firewall rule added" : "Firewall unchanged",
                ok ? "Orbit can now receive on private networks." : "The rule was not added (administrator approval is required).", false);
        });

        OpenNetworkSettingsCommand = new RelayCommand(() =>
        {
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:network-status") { UseShellExecute = true }); } catch { }
        });

        _showOnboarding = !_settings.OnboardingDone;

        // Uploads are refused on connections that arrive through a Windows "Public" network profile.
        _server.IsTrustedNetwork = _trust.IsTrusted;

        _manager.ApprovalRequested += s => Dispatch(() =>
        {
            Sync();
            Notify?.Invoke($"{s.Sender} wants to send {Plural(s.Files.Count)}", "Open Orbit to accept or decline.", true);
        });
        _manager.SessionChanged += s => Dispatch(() =>
        {
            if (s.Status == SessionStatus.Completed && !s.AdHoc)
                Notify?.Invoke($"Received {Plural(s.Files.Count)}", $"From {s.Sender} · saved to {Path.GetFileName(DownloadFolder.TrimEnd('\\'))}", false);
            else if (s.Status == SessionStatus.Failed)
                Notify?.Invoke("Transfer failed", s.Error ?? "Something went wrong.", false);
        });
        _manager.FileCompleted += (s, f) =>
        {
            if (s.AdHoc) Dispatch(() => Notify?.Invoke("Received " + f.SafeName, "From " + s.Sender, false));
        };

        NetworkChange.NetworkAddressChanged += (_, _) => Dispatch(RefreshNetwork);

        _timer = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Background, (_, _) => Sync(), Application.Current.Dispatcher);
        _timer.Start();
    }

    public async Task StartAsync()
    {
        try
        {
            await _server.StartAsync(_settings.Port);
            ServerError = null;
        }
        catch (Exception ex)
        {
            ServerError = "Could not start the receiver: " + ex.Message;
        }
        RefreshNetwork();
    }

    // ------------------------------------------------------------------ device

    public string DeviceName => _settings.DeviceName;

    private bool _isEditingName;
    public bool IsEditingName { get => _isEditingName; set => Set(ref _isEditingName, value); }

    private string _editName = "";
    public string EditName { get => _editName; set => Set(ref _editName, value); }

    public ICommand EditNameCommand { get; }
    public ICommand SaveNameCommand { get; }
    public ICommand CancelEditNameCommand { get; }

    private void SaveName()
    {
        var name = EditName.Trim();
        if (name.Length is > 0 and <= 40)
        {
            _settings.DeviceName = name;
            Save();
            Raise(nameof(DeviceName));
            RegisterMdns();
        }
        IsEditingName = false;
    }

    // ------------------------------------------------------------------ receiving

    public bool ReceivingEnabled
    {
        get => _settings.ReceivingEnabled;
        set
        {
            if (_settings.ReceivingEnabled == value) return;
            _settings.ReceivingEnabled = value;
            Save();
            Raise();
            RaiseStatus();
        }
    }

    public bool RequireApproval
    {
        get => _settings.RequireApproval;
        set { if (_settings.RequireApproval == value) return; _settings.RequireApproval = value; Save(); Raise(); RaiseStatus(); }
    }

    private string? _serverError;
    public string? ServerError { get => _serverError; private set { Set(ref _serverError, value); RaiseStatus(); } }

    public string StatusText => ServerError is not null ? "Not available"
        : !ReceivingEnabled ? "Receiving is off"
        : _addresses.Count == 0 ? "No network connection"
        : IsPublicNetwork ? "Paused on a public network"
        : "Ready to receive";

    /// <summary>"ok", "off" or "error" — drives the status dot color.</summary>
    public string StatusKind => ServerError is not null || (ReceivingEnabled && (_addresses.Count == 0 || IsPublicNetwork)) ? "error" : ReceivingEnabled ? "ok" : "off";

    public bool IsBroadcasting => ReceivingEnabled && ServerError is null && _addresses.Count > 0 && !IsPublicNetwork;

    /// <summary>The selected adapter is on a network Windows marks as "Public" — receiving is refused there.</summary>
    public bool IsPublicNetwork => _addresses.Count > 0 && !_trust.IsTrusted(_addresses[_addressIndex]);

    public ICommand OpenNetworkSettingsCommand { get; }

    private void RaiseStatus() { Raise(nameof(StatusText)); Raise(nameof(StatusKind)); Raise(nameof(IsBroadcasting)); Raise(nameof(IsPublicNetwork)); }

    // ------------------------------------------------------------------ storage

    public string DownloadFolder => _manager.DownloadFolder;
    public ICommand ChangeFolderCommand { get; }
    public ICommand OpenFolderCommand { get; }

    private void ChangeFolder()
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { Title = "Choose where Orbit saves received files", InitialDirectory = Directory.Exists(DownloadFolder) ? DownloadFolder : null };
        if (dialog.ShowDialog() != true) return;
        if (!Platform.IsWritable(dialog.FolderName, out var error))
        {
            Notify?.Invoke("Can't save to that folder", error ?? "The folder is not writable.", false);
            return;
        }
        _settings.DownloadFolder = dialog.FolderName;
        Save();
        Raise(nameof(DownloadFolder));
    }

    // ------------------------------------------------------------------ pairing / sharing

    public string PairingCode => _settings.PairingKey.Length == 8 ? _settings.PairingKey[..4] + " " + _settings.PairingKey[4..] : _settings.PairingKey;
    public ICommand NewKeyCommand { get; }

    private void NewKey()
    {
        _settings.PairingKey = ReceiverSettings.NewPairingKey();
        Save();
        Raise(nameof(PairingCode));
        UpdateAddress();
    }

    private string _shareUrl = "";
    public string ShareUrl { get => _shareUrl; private set => Set(ref _shareUrl, value); }

    private string _shortcutUrl = "";
    public string ShortcutUrl { get => _shortcutUrl; private set => Set(ref _shortcutUrl, value); }

    private Geometry? _qr;
    public Geometry? QrCode { get => _qr; private set => Set(ref _qr, value); }

    private string _networkLabel = "";
    public string NetworkLabel { get => _networkLabel; private set => Set(ref _networkLabel, value); }

    public bool HasMultipleNetworks => _addresses.Count > 1;

    private string _copyLabel = "Copy link";
    public string CopyLabel { get => _copyLabel; private set => Set(ref _copyLabel, value); }

    public ICommand CopyUrlCommand { get; }
    public ICommand NextNetworkCommand { get; }
    public ICommand AllowFirewallCommand { get; }

    private void RefreshNetwork()
    {
        _trust.Refresh();
        // Prefer adapters on Private/Domain networks (e.g. home Wi-Fi over a VPN tunnel marked Public).
        _addresses = NetworkUtil.GetLanAddresses().OrderBy(a => _trust.IsTrusted(a) ? 0 : 1).ToList();
        if (_addressIndex >= _addresses.Count) _addressIndex = 0;
        Raise(nameof(HasMultipleNetworks));
        UpdateAddress();
        RaiseStatus();
    }

    private void UpdateAddress()
    {
        if (_addresses.Count == 0 || !_server.IsRunning)
        {
            ShareUrl = ""; ShortcutUrl = ""; QrCode = null;
            NetworkLabel = _server.IsRunning ? "Connect this PC to Wi-Fi or Ethernet" : "";
            return;
        }
        var ip = _addresses[_addressIndex];
        RaiseStatus();
        if (!_trust.IsTrusted(ip))
        {
            ShareUrl = ""; ShortcutUrl = ""; QrCode = null;
            NetworkLabel = "Public network — set it to Private in Windows Settings";
            _mdns.Unregister();
            return;
        }
        var host = $"{ip}:{_server.Port}";
        ShareUrl = $"http://{host}/?k={_settings.PairingKey}";
        ShortcutUrl = $"http://{host}/api/v1/upload?k={_settings.PairingKey}";
        QrCode = Platform.QrGeometry(ShareUrl);
        NetworkLabel = host;
        RegisterMdns();
    }

    private void RegisterMdns()
    {
        if (!_server.IsRunning) return;
        var ip = _addresses.Count > 0 ? _addresses[_addressIndex] : null;
        _mdns.Register(_settings.DeviceName, _settings.DeviceId, _server.Port, ip);
    }

    private async void RestoreCopyLabel() { await Task.Delay(1600); CopyLabel = "Copy link"; }

    // ------------------------------------------------------------------ activity

    public ObservableCollection<SessionViewModel> Sessions { get; } = [];
    public bool HasSessions => Sessions.Count > 0;
    public bool HasFinished => Sessions.Any(s => s.Kind is SessionKind.Completed or SessionKind.Canceled or SessionKind.Failed);
    public ICommand ClearFinishedCommand { get; }

    private SessionViewModel? _pendingApproval;
    public SessionViewModel? PendingApproval { get => _pendingApproval; private set => Set(ref _pendingApproval, value); }

    private void Sync()
    {
        // The network profile can change without an address change (user flips Public/Private): recheck every ~10 s.
        if (++_ticks % 40 == 0)
        {
            var wasPublic = IsPublicNetwork;
            _trust.Refresh();
            if (wasPublic != IsPublicNetwork) UpdateAddress();
        }

        var current = _manager.Sessions.Where(s => !(s.AdHoc && s.FileCount() == 0 && s.Status != SessionStatus.Pending)).ToList();
        var ids = current.Select(s => s.Id).ToHashSet();
        for (var i = Sessions.Count - 1; i >= 0; i--)
            if (!ids.Contains(Sessions[i].Session.Id)) Sessions.RemoveAt(i);
        foreach (var s in current.OrderBy(s => s.CreatedAt))
        {
            var existing = Sessions.FirstOrDefault(v => v.Session.Id == s.Id);
            if (existing is null) Sessions.Insert(0, new SessionViewModel(s, _manager));
            else existing.Update();
        }
        PendingApproval = Sessions.LastOrDefault(v => v.Kind == SessionKind.Pending);
        Raise(nameof(HasSessions));
        Raise(nameof(HasFinished));
    }

    // ------------------------------------------------------------------ overlays

    private bool _showGuide;
    public bool ShowGuide { get => _showGuide; set => Set(ref _showGuide, value); }

    private bool _showOnboarding;
    public bool ShowOnboarding { get => _showOnboarding; set => Set(ref _showOnboarding, value); }

    public ICommand ShowGuideCommand { get; }
    public ICommand CloseGuideCommand { get; }
    public ICommand FinishOnboardingCommand { get; }

    // ------------------------------------------------------------------ helpers

    private void Save()
    {
        try { _store.Save(_settings); } catch (Exception ex) { Notify?.Invoke("Settings not saved", ex.Message, false); }
    }

    private static void Dispatch(Action a) => Application.Current?.Dispatcher.BeginInvoke(a);
    private static string Plural(int n) => n == 1 ? "1 item" : $"{n} items";

    public void Dispose()
    {
        _timer.Stop();
        _mdns.Dispose();
    }
}

internal static class SessionExtensions
{
    public static int FileCount(this TransferSession s) => s.Files.Count;
}
