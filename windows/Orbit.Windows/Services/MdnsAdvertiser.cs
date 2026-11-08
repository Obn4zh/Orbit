using System.Net;
using System.Runtime.InteropServices;
using Orbit.Core;

namespace Orbit.Windows.Services;

/// <summary>
/// Advertises "_orbit._tcp" via the Windows DNS-SD API (dnsapi.dll, Windows 10 1809+).
/// iOS Safari cannot browse services, but a future native companion app and other receivers can.
/// </summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private const uint DnsRequestPending = 9506;
    private const uint DnsQueryRequestVersion1 = 1;

    private IntPtr _instance;
    private IntPtr _ip4;
    private DnsServiceRegisterRequest _request;
    private RegisterComplete? _callback; // keep the delegate alive while registered
    private bool _registered;

    public string? LastError { get; private set; }

    public void Register(string deviceName, string deviceId, int port, IPAddress? address)
    {
        Unregister();
        try
        {
            var label = string.Concat(deviceName.Where(c => c != '.' && !char.IsControl(c))).Trim();
            if (label.Length == 0) label = "Orbit";
            if (label.Length > 60) label = label[..60];
            var serviceName = $"{label}.{ProtocolConstants.ServiceType}.local";
            var host = Environment.MachineName + ".local";

            if (address is not null && address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            {
                _ip4 = Marshal.AllocHGlobal(4);
                Marshal.Copy(address.GetAddressBytes(), 0, _ip4, 4);
            }

            string[] keys = ["id", "v", "platform", "path"];
            string[] values = [deviceId, ProtocolConstants.Version.ToString(), "windows", "/"];
            _instance = DnsServiceConstructInstance(serviceName, host, _ip4, IntPtr.Zero, (ushort)port, 0, 0, (uint)keys.Length, keys, values);
            if (_instance == IntPtr.Zero) { LastError = "DnsServiceConstructInstance failed"; return; }

            _callback = (status, _, instance) =>
            {
                if (status != 0) LastError = $"mDNS registration status {status}";
                if (instance != IntPtr.Zero) DnsServiceFreeInstance(instance);
            };
            _request = new DnsServiceRegisterRequest
            {
                Version = DnsQueryRequestVersion1,
                InterfaceIndex = 0,
                pServiceInstance = _instance,
                pRegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback),
            };
            var result = DnsServiceRegister(ref _request, IntPtr.Zero);
            _registered = result == DnsRequestPending;
            if (!_registered) LastError = $"DnsServiceRegister returned {result}";
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            LastError = "mDNS is not available on this Windows version.";
        }
    }

    public void Unregister()
    {
        try
        {
            if (_registered) DnsServiceDeRegister(ref _request, IntPtr.Zero);
        }
        catch { /* ignore */ }
        _registered = false;
        if (_instance != IntPtr.Zero) { DnsServiceFreeInstance(_instance); _instance = IntPtr.Zero; }
        if (_ip4 != IntPtr.Zero) { Marshal.FreeHGlobal(_ip4); _ip4 = IntPtr.Zero; }
    }

    public void Dispose() => Unregister();

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void RegisterComplete(uint status, IntPtr queryContext, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private struct DnsServiceRegisterRequest
    {
        public uint Version;
        public uint InterfaceIndex;
        public IntPtr pServiceInstance;
        public IntPtr pRegisterCompletionCallback;
        public IntPtr pQueryContext;
        public IntPtr hCredentials;
        public int unicastEnabled;
    }

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern IntPtr DnsServiceConstructInstance(string pServiceName, string pHostName, IntPtr pIp4, IntPtr pIp6,
        ushort wPort, ushort wPriority, ushort wWeight, uint dwPropertiesCount,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] keys,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] values);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern uint DnsServiceRegister(ref DnsServiceRegisterRequest request, IntPtr cancel);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern uint DnsServiceDeRegister(ref DnsServiceRegisterRequest request, IntPtr cancel);

    [DllImport("dnsapi.dll", ExactSpelling = true)]
    private static extern void DnsServiceFreeInstance(IntPtr instance);
}
