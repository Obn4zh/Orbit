using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;

namespace Orbit.Windows;

public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) => value is true ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

public sealed class NullToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture)
    {
        var visible = value is not null && (value is not string s || s.Length > 0);
        if (parameter as string == "invert") visible = !visible;
        return visible ? Visibility.Visible : Visibility.Collapsed;
    }
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Maps a status string or enum name to a brush resource; parameter is "key=Resource;key=Resource;*=Resource".</summary>
public sealed class StateToBrushConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object parameter, CultureInfo culture)
    {
        var key = value?.ToString() ?? "";
        string? fallback = null;
        foreach (var pair in ((string)parameter).Split(';'))
        {
            var kv = pair.Split('=');
            if (kv[0] == key) return Application.Current.TryFindResource(kv[1]) as Brush;
            if (kv[0] == "*") fallback = kv[1];
        }
        return fallback is null ? null : Application.Current.TryFindResource(fallback) as Brush;
    }
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Visible when the enum/string value is one of the '|' separated names in the parameter.</summary>
public sealed class StateToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object parameter, CultureInfo culture) =>
        ((string)parameter).Split('|').Contains(value?.ToString()) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}
