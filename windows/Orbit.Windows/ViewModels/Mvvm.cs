using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;

namespace Orbit.Windows.ViewModels;

public abstract class ObservableObject : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected void Raise([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(name);
        return true;
    }
}

public sealed class RelayCommand(Action<object?> execute, Func<object?, bool>? canExecute = null) : ICommand
{
    public RelayCommand(Action execute) : this(_ => execute()) { }

    public event EventHandler? CanExecuteChanged
    {
        add => CommandManager.RequerySuggested += value;
        remove => CommandManager.RequerySuggested -= value;
    }

    public bool CanExecute(object? parameter) => canExecute?.Invoke(parameter) ?? true;
    public void Execute(object? parameter) => execute(parameter);
}

public static class Format
{
    public static string Bytes(double b)
    {
        if (b < 1024) return $"{b:0} B";
        string[] units = ["KB", "MB", "GB", "TB"];
        var i = -1;
        do { b /= 1024; i++; } while (b >= 1024 && i < units.Length - 1);
        return (b >= 100 ? b.ToString("0") : b >= 10 ? b.ToString("0.0") : b.ToString("0.00")) + " " + units[i];
    }

    public static string Duration(double seconds)
    {
        if (double.IsNaN(seconds) || double.IsInfinity(seconds) || seconds < 0) return "—";
        var t = TimeSpan.FromSeconds(Math.Round(seconds));
        if (t.TotalSeconds < 60) return $"{t.Seconds}s";
        if (t.TotalMinutes < 60) return $"{t.Minutes}m {t.Seconds:00}s";
        return $"{(int)t.TotalHours}h {t.Minutes:00}m";
    }
}
