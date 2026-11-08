using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace Orbit.Core;

public enum NetworkCategory { Unknown = -1, Public = 0, Private = 1, Domain = 2 }

/// <summary>
/// Maps local IP addresses to the Windows network profile of their adapter (Network List Manager).
/// Receiving is only allowed on Private/Domain networks; a "Public" profile (café, hotel, unknown Wi-Fi) pauses it.
/// </summary>
public sealed class NetworkTrust
{
    private volatile Dictionary<IPAddress, NetworkCategory> _byAddress = new();

    public string? LastError { get; private set; }

    public void Refresh()
    {
        var map = new Dictionary<IPAddress, NetworkCategory>();
        if (OperatingSystem.IsWindows())
        {
            try
            {
                var byAdapter = new Dictionary<Guid, NetworkCategory>();
                var nlm = (INetworkListManager)new NetworkListManagerClass();
                var connections = nlm.GetNetworkConnections();
                var batch = new INetworkConnection[1];
                while (connections.Next(1, batch, out var fetched) == 0 && fetched == 1)
                {
                    var connection = batch[0];
                    byAdapter[connection.GetAdapterId()] = (NetworkCategory)connection.GetNetwork().GetCategory();
                }
                foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (!Guid.TryParse(nic.Id, out var id) || !byAdapter.TryGetValue(id, out var category)) continue;
                    foreach (var ua in nic.GetIPProperties().UnicastAddresses) map[ua.Address] = category;
                }
                LastError = null;
            }
            catch (Exception ex)
            {
                LastError = ex.Message; // NLM unavailable: addresses stay "unknown", which is allowed
            }
        }
        _byAddress = map;
    }

    public NetworkCategory CategoryOf(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        return _byAddress.TryGetValue(address, out var c) ? c : NetworkCategory.Unknown;
    }

    /// <summary>Public networks are untrusted; Private, Domain and undeterminable ones are allowed.</summary>
    public bool IsTrusted(IPAddress address) => CategoryOf(address) != NetworkCategory.Public;

    // ---------------------------------------------------------------- Network List Manager COM interop (netlistmgr.h)

    [ComImport, Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B")]
    private class NetworkListManagerClass;

    [ComImport, Guid("DCB00000-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkListManager
    {
        object GetNetworks(int flags);
        INetwork GetNetwork(Guid networkId);
        IEnumNetworkConnections GetNetworkConnections();
    }

    [ComImport, Guid("DCB00006-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface IEnumNetworkConnections
    {
        [return: MarshalAs(UnmanagedType.Interface)] object get__NewEnum();
        [PreserveSig] int Next(uint celt, [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] INetworkConnection[] rgelt, out uint pceltFetched);
    }

    [ComImport, Guid("DCB00005-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkConnection
    {
        INetwork GetNetwork();
        [return: MarshalAs(UnmanagedType.VariantBool)] bool get_IsConnectedToInternet();
        [return: MarshalAs(UnmanagedType.VariantBool)] bool get_IsConnected();
        int GetConnectivity();
        Guid GetConnectionId();
        Guid GetAdapterId();
    }

    [ComImport, Guid("DCB00002-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetwork
    {
        [return: MarshalAs(UnmanagedType.BStr)] string GetName();
        void SetName([MarshalAs(UnmanagedType.BStr)] string name);
        [return: MarshalAs(UnmanagedType.BStr)] string GetDescription();
        void SetDescription([MarshalAs(UnmanagedType.BStr)] string description);
        Guid GetNetworkId();
        int GetDomainType();
        IEnumNetworkConnections GetNetworkConnections();
        void GetTimeCreatedAndConnected(out uint createdLow, out uint createdHigh, out uint connectedLow, out uint connectedHigh);
        [return: MarshalAs(UnmanagedType.VariantBool)] bool get_IsConnectedToInternet();
        [return: MarshalAs(UnmanagedType.VariantBool)] bool get_IsConnected();
        int GetConnectivity();
        int GetCategory();
    }
}
