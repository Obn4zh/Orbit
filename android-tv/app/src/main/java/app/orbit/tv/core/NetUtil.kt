package app.orbit.tv.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtil {
    /** Loopback, RFC 1918, link-local and IPv6 ULA/link-local only. Parses literals; never does DNS. */
    fun isLocal(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val literal = address.trim().removePrefix("/").substringBefore('%')
        val looksNumeric = literal.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        if (!looksNumeric) return false
        val ip = try { InetAddress.getByName(literal) } catch (e: Exception) { return false }
        return isLocal(ip)
    }

    fun isLocal(ip: InetAddress): Boolean {
        if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
        if (ip is Inet6Address) {
            val b = ip.address
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
            // IPv4-mapped ::ffff:a.b.c.d
            if (b.take(10).all { it.toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) {
                return isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            }
        }
        return false
    }

    /**
     * True when the active network is a Wi-Fi without encryption (no password). Ethernet, WPA/WPA2/WPA3 and
     * Enhanced Open (OWE) count as protected. Android < 12 cannot tell without location permission: treated as protected.
     */
    fun isOpenWifi(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val info = caps.transportInfo as? android.net.wifi.WifiInfo ?: return false
        return info.currentSecurityType == android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN
    }

    /** IPv4 address of the active Wi-Fi/Ethernet network, falling back to any site-local interface address. */
    fun lanAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            cm.getLinkProperties(active)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.let { return it.hostAddress }
        }
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }
    }
}
