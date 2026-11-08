using System.Security.Cryptography;
using System.Text.Json;

namespace Orbit.Core;

public sealed class ReceiverSettings
{
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public string DeviceName { get; set; } = Environment.MachineName;
    public string DownloadFolder { get; set; } = string.Empty;
    public bool ReceivingEnabled { get; set; } = true;
    public bool RequireApproval { get; set; } = true;
    public string PairingKey { get; set; } = NewPairingKey();
    public int Port { get; set; } = ProtocolConstants.DefaultPort;
    public bool OnboardingDone { get; set; }

    private const string Alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /// <summary>8 characters from a 31-symbol alphabet (~39 bits), embedded in the QR code.</summary>
    public static string NewPairingKey() =>
        string.Create(8, 0, (span, _) =>
        {
            for (var i = 0; i < span.Length; i++) span[i] = Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)];
        });
}

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private readonly string _path;
    private readonly Lock _lock = new();

    public SettingsStore(string path) { _path = path; }

    public ReceiverSettings Load()
    {
        try
        {
            if (File.Exists(_path))
                return JsonSerializer.Deserialize<ReceiverSettings>(File.ReadAllText(_path)) ?? new ReceiverSettings();
        }
        catch (Exception) { /* corrupt settings: fall back to defaults */ }
        return new ReceiverSettings();
    }

    public void Save(ReceiverSettings settings)
    {
        lock (_lock)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(settings, Json));
            File.Move(tmp, _path, overwrite: true);
        }
    }
}
