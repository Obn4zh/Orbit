using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Orbit.Core;

public static class NetworkUtil
{
    /// <summary>True for loopback, RFC 1918, link-local and IPv6 unique-local / link-local addresses.</summary>
    public static bool IsLocalNetwork(IPAddress? address)
    {
        if (address is null) return false;
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (IPAddress.IsLoopback(address)) return true;

        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = address.GetAddressBytes();
            return b[0] == 10
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 169 && b[1] == 254);
        }
        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            var b = address.GetAddressBytes();
            return address.IsIPv6LinkLocal || (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    /// <summary>Best-guess LAN IPv4 addresses, physical Wi-Fi/Ethernet adapters with a gateway first.</summary>
    public static IReadOnlyList<IPAddress> GetLanAddresses()
    {
        var result = new List<(IPAddress ip, int score)>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var desc = (nic.Description + " " + nic.Name).ToLowerInvariant();
            var isVirtual = desc.Contains("virtual") || desc.Contains("vethernet") || desc.Contains("hyper-v")
                || desc.Contains("vmware") || desc.Contains("virtualbox") || desc.Contains("wsl")
                || desc.Contains("tap-") || desc.Contains("wireguard") || desc.Contains("tailscale") || desc.Contains("zerotier");

            IPInterfaceProperties props;
            try { props = nic.GetIPProperties(); } catch { continue; }
            var hasGateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));

            foreach (var ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (!IsLocalNetwork(ua.Address) || IPAddress.IsLoopback(ua.Address)) continue;
                var b = ua.Address.GetAddressBytes();
                var score = (hasGateway ? 100 : 0) + (isVirtual ? -200 : 0)
                    + (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 10 : 0)
                    + (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 8 : 0)
                    + (b[0] == 169 ? -150 : 0);
                result.Add((ua.Address, score));
            }
        }
        return result.OrderByDescending(r => r.score).Select(r => r.ip).ToList();
    }
}
