using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using Orbit.Windows.ViewModels;

namespace Orbit.Windows;

public partial class MainWindow : Window
{
    private readonly List<(Ellipse ring, Storyboard sb)> _rings = [];

    public MainWindow()
    {
        InitializeComponent();
        Loaded += (_, _) => { BuildRadar(); UpdateRadar(); };
        DataContextChanged += (_, e) =>
        {
            if (e.OldValue is INotifyPropertyChanged o) o.PropertyChanged -= OnVmChanged;
            if (e.NewValue is INotifyPropertyChanged n) n.PropertyChanged += OnVmChanged;
        };
    }

    private MainViewModel? Vm => DataContext as MainViewModel;

    private void OnVmChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(MainViewModel.IsBroadcasting):
                UpdateRadar();
                break;
            case nameof(MainViewModel.IsEditingName) when Vm?.IsEditingName == true:
                Dispatcher.BeginInvoke(() => { NameBox.Focus(); NameBox.SelectAll(); });
                break;
            case nameof(MainViewModel.PendingApproval) when Vm?.PendingApproval is not null:
                Dispatcher.BeginInvoke(() => AcceptButton.Focus(), System.Windows.Threading.DispatcherPriority.Input);
                break;
        }
    }

    private void NameBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) Vm?.SaveNameCommand.Execute(null);
        else if (e.Key == Key.Escape) Vm?.CancelEditNameCommand.Execute(null);
    }

    /// <summary>Three rings expanding outward, staggered — the "discoverable" signal.</summary>
    private void BuildRadar()
    {
        if (_rings.Count > 0) return;
        Ellipse[] rings = [Ring1, Ring2, Ring3];
        for (var i = 0; i < rings.Length; i++)
        {
            var sb = new Storyboard { RepeatBehavior = RepeatBehavior.Forever, Duration = TimeSpan.FromSeconds(3.6) };
            var begin = TimeSpan.FromSeconds(1.2 * i);
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };

            foreach (var axis in new[] { "ScaleX", "ScaleY" })
            {
                var scale = new DoubleAnimation(0.42, 1.0, TimeSpan.FromSeconds(3.6)) { BeginTime = begin, EasingFunction = ease };
                Storyboard.SetTarget(scale, rings[i]);
                Storyboard.SetTargetProperty(scale, new PropertyPath($"RenderTransform.{axis}"));
                sb.Children.Add(scale);
            }
            var fade = new DoubleAnimationUsingKeyFrames { BeginTime = begin, Duration = TimeSpan.FromSeconds(3.6) };
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromPercent(0)));
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.6, KeyTime.FromPercent(0.15)));
            fade.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromPercent(1), new QuadraticEase()));
            Storyboard.SetTarget(fade, rings[i]);
            Storyboard.SetTargetProperty(fade, new PropertyPath(OpacityProperty));
            sb.Children.Add(fade);
            _rings.Add((rings[i], sb));
        }
    }

    private void UpdateRadar()
    {
        if (_rings.Count == 0) return;
        var on = Vm?.IsBroadcasting == true;
        var animate = on && SystemParameters.ClientAreaAnimation;
        foreach (var (ring, sb) in _rings)
        {
            if (animate) sb.Begin(this, true);
            else { sb.Stop(this); ring.Opacity = on ? 0.25 : 0; }
        }
        Core.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        CoreOff.Visibility = on ? Visibility.Collapsed : Visibility.Visible;
        CoreGlyph.Foreground = on ? Brushes.White : (Brush)FindResource("TextFillColorSecondaryBrush");
    }

    public void Flash()
    {
        var info = new FlashInfo
        {
            cbSize = (uint)Marshal.SizeOf<FlashInfo>(),
            hwnd = new WindowInteropHelper(this).Handle,
            dwFlags = 3 | 12, // FLASHW_ALL | FLASHW_TIMERNOFG
            uCount = 5,
        };
        FlashWindowEx(ref info);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FlashInfo { public uint cbSize; public IntPtr hwnd; public uint dwFlags; public uint uCount; public uint dwTimeout; }

    [DllImport("user32.dll")]
    private static extern bool FlashWindowEx(ref FlashInfo pwfi);
}
