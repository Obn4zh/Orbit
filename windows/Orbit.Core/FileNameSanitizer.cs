using System.Text;

namespace Orbit.Core;

/// <summary>
/// Turns untrusted, sender-supplied file names into safe leaf names.
/// Never returns a path: separators, traversal segments, device names and control characters are removed.
/// </summary>
public static class FileNameSanitizer
{
    private const int MaxLength = 180;
    private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    public static string Sanitize(string? raw)
    {
        var name = raw ?? string.Empty;

        // Keep only the last path segment, whichever separator the sender used.
        var cut = name.LastIndexOfAny(['/', '\\']);
        if (cut >= 0) name = name[(cut + 1)..];

        var sb = new StringBuilder(name.Length);
        foreach (var ch in name.Normalize(NormalizationForm.FormC))
        {
            if (char.IsControl(ch) || ch is '<' or '>' or ':' or '"' or '|' or '?' or '*' or '‮' or '‭')
                continue;
            sb.Append(ch);
        }
        name = sb.ToString().Trim().TrimEnd('.', ' ').TrimStart('.');

        if (name.Length == 0) name = "file";

        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        if (Reserved.Contains(stem) || Reserved.Contains(stem.Split('.')[0])) stem = "_" + stem;
        if (ext.Length > 16) { stem += ext; ext = string.Empty; }
        if (stem.Length + ext.Length > MaxLength) stem = stem[..Math.Max(1, MaxLength - ext.Length)];
        if (stem.Length == 0) stem = "file";
        return stem + ext;
    }

    /// <summary>Returns "name.ext", "name (1).ext", ... such that <paramref name="isTaken"/> is false.</summary>
    public static string MakeUnique(string fileName, Func<string, bool> isTaken)
    {
        if (!isTaken(fileName)) return fileName;
        var stem = Path.GetFileNameWithoutExtension(fileName);
        var ext = Path.GetExtension(fileName);
        for (var i = 1; i < 100_000; i++)
        {
            var candidate = $"{stem} ({i}){ext}";
            if (!isTaken(candidate)) return candidate;
        }
        return $"{stem} ({Guid.NewGuid():N}){ext}";
    }
}
