using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Media;
using QRCoder;

namespace Orbit.Windows.Services;

public static class Platform
{
    private static readonly Guid DownloadsFolderId = new("374DE290-123F-4565-9164-39C4925E467B");

    public static string DefaultDownloadFolder()
    {
        string downloads;
        try
        {
            SHGetKnownFolderPath(DownloadsFolderId, 0, IntPtr.Zero, out var ptr);
            downloads = Marshal.PtrToStringUni(ptr) ?? throw new InvalidOperationException();
            Marshal.FreeCoTaskMem(ptr);
        }
        catch
        {
            downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }
        return Path.Combine(downloads, "Orbit");
    }

    public static bool IsWritable(string folder, out string? error)
    {
        try
        {
            Directory.CreateDirectory(folder);
            var probe = Path.Combine(folder, ".orbit-write-test-" + Guid.NewGuid().ToString("N"));
            File.WriteAllBytes(probe, []);
            File.Delete(probe);
            error = null;
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    public static void OpenFolder(string folder, string? selectFile = null)
    {
        try
        {
            Directory.CreateDirectory(folder);
            if (selectFile is not null && File.Exists(selectFile))
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{selectFile}\"") { UseShellExecute = true });
            else
                Process.Start(new ProcessStartInfo(folder) { UseShellExecute = true });
        }
        catch { /* Explorer unavailable */ }
    }

    /// <summary>
    /// Adds an inbound firewall rule for this executable on private and domain networks (asks for elevation).
    /// Public networks stay blocked on purpose.
    /// </summary>
    public static bool AllowThroughFirewall()
    {
        var exe = Environment.ProcessPath!;
        var args = $"advfirewall firewall add rule name=\"Orbit\" dir=in action=allow program=\"{exe}\" enable=yes profile=private,domain";
        try
        {
            using var p = Process.Start(new ProcessStartInfo("netsh.exe", args) { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
            p?.WaitForExit(15000);
            return p?.ExitCode == 0;
        }
        catch { return false; } // user declined UAC
    }

    /// <summary>Builds a crisp, DPI-independent QR code geometry (1 unit per module, quiet zone included).</summary>
    public static Geometry QrGeometry(string text)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var matrix = data.ModuleMatrix;
        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            for (var y = 0; y < matrix.Count; y++)
            {
                var row = matrix[y];
                for (var x = 0; x < row.Length; x++)
                {
                    if (!row[x]) continue;
                    var start = x;
                    while (x + 1 < row.Length && row[x + 1]) x++;
                    ctx.BeginFigure(new System.Windows.Point(start, y), true, true);
                    ctx.LineTo(new System.Windows.Point(x + 1, y), false, false);
                    ctx.LineTo(new System.Windows.Point(x + 1, y + 1), false, false);
                    ctx.LineTo(new System.Windows.Point(start, y + 1), false, false);
                }
            }
        }
        geometry.Freeze();
        return geometry;
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr ppszPath);
}
