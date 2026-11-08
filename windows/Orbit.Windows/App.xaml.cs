using System.Windows;
using Orbit.Core;
using Orbit.Windows.Services;
using Orbit.Windows.ViewModels;
using Forms = System.Windows.Forms;

namespace Orbit.Windows;

public partial class App : Application
{
    private const string InstanceName = "Orbit.Windows.SingleInstance";
    private Mutex? _mutex;
    private EventWaitHandle? _showSignal;
    private Forms.NotifyIcon? _tray;
    private Forms.ToolStripMenuItem? _receivingItem;
    private MainViewModel? _vm;
    private MainWindow? _window;
    private ReceiverServer? _server;
    private bool _hintShown;
    private bool _quitting;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Single instance: a second launch just brings the first window forward.
        _mutex = new Mutex(true, InstanceName, out var isFirst);
        if (!isFirst)
        {
            try { EventWaitHandle.OpenExisting(InstanceName + ".Show").Set(); } catch { }
            Shutdown();
            return;
        }
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Show");
        new Thread(() =>
        {
            while (_showSignal.WaitOne()) Dispatcher.BeginInvoke(new Action(ShowMainWindow));
        }) { IsBackground = true }.Start();

        var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Orbit");
        var store = new SettingsStore(Path.Combine(dataDir, "settings.json"));
        var settings = store.Load();
        if (string.IsNullOrWhiteSpace(settings.DownloadFolder)) settings.DownloadFolder = Platform.DefaultDownloadFolder();
        try { store.Save(settings); } catch { }

        var manager = new TransferManager(() => settings);
        _server = new ReceiverServer(manager, () => settings);
        _vm = new MainViewModel(settings, store, manager, _server);
        _vm.Notify += OnNotify;
        _vm.PropertyChanged += (_, args) => { if (args.PropertyName == nameof(MainViewModel.ReceivingEnabled)) UpdateTrayMenu(); };

        CreateTray();
        _window = new MainWindow { DataContext = _vm };
        _window.Closing += (_, args) =>
        {
            if (_quitting) return;
            args.Cancel = true; // keep receiving in the background
            _window.Hide();
            if (!_hintShown)
            {
                _hintShown = true;
                _tray?.ShowBalloonTip(3000, "Orbit is still receiving", "Orbit keeps running in the notification area. Right-click the icon to quit.", Forms.ToolTipIcon.None);
            }
        };
        if (!e.Args.Contains("--minimized")) _window.Show();

        await _vm.StartAsync();
    }

    private void CreateTray()
    {
        var iconStream = GetResourceStream(new Uri("pack://application:,,,/Assets/orbit.ico"))!.Stream;
        _receivingItem = new Forms.ToolStripMenuItem("Receiving", null, (_, _) => { if (_vm is not null) _vm.ReceivingEnabled = !_vm.ReceivingEnabled; });
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open Orbit", null, (_, _) => ShowMainWindow());
        menu.Items.Add(_receivingItem);
        menu.Items.Add("Open received files", null, (_, _) => { if (_vm is not null) Platform.OpenFolder(_vm.DownloadFolder); });
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Quit Orbit", null, (_, _) => Quit());

        _tray = new Forms.NotifyIcon
        {
            Icon = new System.Drawing.Icon(iconStream),
            Text = "Orbit",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.MouseClick += (_, args) => { if (args.Button == Forms.MouseButtons.Left) ShowMainWindow(); };
        _tray.BalloonTipClicked += (_, _) => ShowMainWindow();
        UpdateTrayMenu();
    }

    private void UpdateTrayMenu()
    {
        if (_receivingItem is null || _vm is null || _tray is null) return;
        _receivingItem.Checked = _vm.ReceivingEnabled;
        _tray.Text = _vm.ReceivingEnabled ? "Orbit · ready to receive" : "Orbit · receiving off";
    }

    private void OnNotify(string title, string message, bool isApproval)
    {
        if (isApproval) ShowMainWindow(flash: true);
        var windowActive = _window is { IsVisible: true, IsActive: true };
        if (!windowActive || isApproval)
            _tray?.ShowBalloonTip(5000, title, message, Forms.ToolTipIcon.None);
    }

    private void ShowMainWindow() => ShowMainWindow(false);

    private void ShowMainWindow(bool flash)
    {
        if (_window is null) return;
        if (!_window.IsVisible) _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
        if (flash) _window.Flash();
    }

    private async void Quit()
    {
        _quitting = true;
        if (_tray is not null) { _tray.Visible = false; _tray.Dispose(); }
        _vm?.Dispose();
        if (_server is not null) await _server.StopAsync();
        _window?.Close();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
