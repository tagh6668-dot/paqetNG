package com.tagh6668.paqetng.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address

/**
 * Detected default network info: interface name, device IPv4/IPv6 (with :0 for paqet), and gateway MAC.
 * [ipv4Addr] is "IP:0" (e.g. "10.0.0.2:0"), [ipv6Addr] is "[IP]:0" bracketed (e.g. "[2a03:...]:0") or "" if no global IPv6.
 */
data class DefaultNetworkInfo(
    val interfaceName: String,
    val ipv4Addr: String,
    val ipv6Addr: String = "",
    val routerMac: String
)

/**
 * Fetches the current default network's interface name, IPv4/IPv6 addresses, and gateway MAC.
 * Uses ConnectivityManager + LinkProperties; gateway MAC is read from /proc/net/arp.
 * For IPv6: scans linkAddresses for a non-link-local Inet6Address and returns it as "[IP]:0".
 */
class DefaultNetworkInfoProvider(private val context: Context) {

    private companion object {
        private const val TAG = "DefaultNetworkInfo"
    }

    suspend fun getDefaultNetworkInfo(): DefaultNetworkInfo? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext null
        val network = cm.activeNetwork ?: return@withContext null
        // Prefer LinkProperties (works well for WiFi); on cellular it often returns null.
        cm.getLinkProperties(network)?.let { lp ->
            val interfaceName = lp.interfaceName?.takeIf { it.isNotBlank() } ?: "wlan0"
            if (interfaceName.startsWith("tun") || interfaceName.startsWith("dummy") || interfaceName == "lo") {
                Log.d(TAG, "Active network interface is virtual ($interfaceName) - delegating to root physical probe")
                return@withContext getDefaultNetworkInfoViaRoot()
            }
            val ipv4 = lp.linkAddresses
                .mapNotNull { la -> la.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
                ?: return@withContext getDefaultNetworkInfoViaRoot()
            val ipv4Addr = "$ipv4:0"
            // IPv6 on same interface: first global (non-link-local) Inet6Address
            val ipv6Host = lp.linkAddresses
                .mapNotNull { la -> la.address as? java.net.Inet6Address }
                .firstOrNull { !it.isLinkLocalAddress }?.hostAddress
            val ipv6Addr = if (ipv6Host != null) "[$ipv6Host]:0" else resolveIpv6ForInterface(interfaceName)
            val gateway = lp.routes
                .firstOrNull { it.gateway != null && it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?: lp.routes.firstOrNull { it.gateway != null && it.gateway is Inet4Address }?.gateway
            var gatewayIp = gateway?.hostAddress ?: ""
            Log.d(TAG, "gatewayIp=$gatewayIp ipv6Addr=$ipv6Addr")
            if (gatewayIp.isEmpty() && isWifiInterface(interfaceName)) {
                gatewayIp = resolveGatewayViaRoot(interfaceName) ?: ""
                Log.d(TAG, "gatewayIp fallback WiFi " + interfaceName + ": " + gatewayIp)
                if (gatewayIp.isEmpty()) {
                    Log.w(TAG, "WiFi " + interfaceName + " no gateway — delegate to root")
                    return@withContext getDefaultNetworkInfoViaRoot()
                }
            }
            val routerMacFromFile = readMacFromArp(gatewayIp)
            val routerMacFromRoot = if (routerMacFromFile == null) readMacFromArpViaRoot(gatewayIp) else null
            var routerMac = routerMacFromFile ?: routerMacFromRoot ?: ""
            if (routerMac.isEmpty() && isWifiInterface(interfaceName)) {
                val neighFallback = runSuCommand("ip neigh show dev " + interfaceName)?.lines()?.firstNotNullOfOrNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    val ll = parts.indexOf("lladdr")
                    if (ll >= 0 && ll + 1 < parts.size) parts[ll + 1].takeIf { it.contains(":") && it != "00:00:00:00:00:00" } else null
                }
                if (neighFallback != null) { routerMac = neighFallback; Log.d(TAG, "routerMac fallback neigh " + interfaceName + ": " + routerMac) } else { Log.w(TAG, "WiFi " + interfaceName + " no MAC — delegate to root"); return@withContext getDefaultNetworkInfoViaRoot() }
            }
            Log.d(TAG, "routerMac: fromFile=$routerMacFromFile fromRoot=$routerMacFromRoot final=$routerMac")
            DefaultNetworkInfo(interfaceName, ipv4Addr, ipv6Addr, routerMac)
        } ?: getDefaultNetworkInfoViaRoot()
    }

    /**
     * Fallback for when LinkProperties is null (e.g. on cellular/mobile network).
     * Uses root to run `ip route get 8.8.8.8` and `ip -4 addr show dev <iface>` to get
     * the default route interface and local IPv4; then tries to get gateway MAC via ip neigh.
     */
    private fun getDefaultNetworkInfoViaRoot(): DefaultNetworkInfo? {
        // ip route get 8.8.8.8 → e.g. "8.8.8.8 from 10.0.0.1 dev rmnet_data0 src 10.0.0.2 uid 0"
        // When VPN (tun0) is up, this returns tun0 with the VPN IP — which is wrong for paqet's
        // raw socket (needs the underlying physical interface like ccmni/rmnet/wlan).
        // So we detect virtual interfaces and probe the real egress interface.
        var routeOut = runSuCommand("ip route get 8.8.8.8")
        if (routeOut.isNullOrBlank()) {
            Log.w(TAG, "getDefaultNetworkInfoViaRoot: ip route get 8.8.8.8 returned empty")
            return null
        }
        Log.d(TAG, "getDefaultNetworkInfoViaRoot: route output=$routeOut")
        var devMatch = Regex("""\bdev\s+(\S+)""").find(routeOut)
        var srcMatch = Regex("""\bsrc\s+(\S+)""").find(routeOut)
        var viaMatch = Regex("""\bvia\s+(\S+)""").find(routeOut)
        var iface = devMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val isVirtual = iface != null && (iface == "tun0" || iface.startsWith("tun") || iface.startsWith("dummy") || iface == "lo")
        if (isVirtual || iface.isNullOrBlank()) {
            Log.d(TAG, "getDefaultNetworkInfoViaRoot: route dev is virtual ($iface), probing physical interfaces")
            val probed = probePhysicalInterface()
            if (probed != null) {
                Log.d(TAG, "getDefaultNetworkInfoViaRoot: probed physical iface=${probed.first} ip=${probed.second}")
                iface = probed.first
                srcMatch = Regex("""\bsrc\s+(\S+)""").find("src ${probed.second}")
                val probeRoute = runSuCommand("ip route get 8.8.8.8 from ${probed.second} oif $iface")
                Log.d(TAG, "getDefaultNetworkInfoViaRoot: probe route=$probeRoute")
                viaMatch = probeRoute?.let { Regex("""\bvia\s+(\S+)""").find(it) }
            } else {
                val defaultRoute = runSuCommand("ip route show default")
                iface = defaultRoute?.lines()?.firstNotNullOfOrNull { line ->
                    Regex("""default\s+via\s+\S+\s+dev\s+(\S+)""").find(line)?.groupValues?.get(1)?.takeIf { it != "tun0" && !it.startsWith("tun") }
                } ?: iface
            }
        }
        if (iface.isNullOrBlank()) {
            Log.w(TAG, "getDefaultNetworkInfoViaRoot: could not parse interface from route")
            return null
        }
        var srcIp = srcMatch?.groupValues?.get(1)
        if (srcIp.isNullOrBlank()) {
            val addrOut = runSuCommand("ip -4 addr show dev $iface")
            addrOut?.let { output ->
                Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)""").find(output)?.let { m ->
                    srcIp = m.groupValues[1]
                }
            }
        }
        // Validate: iface we picked must actually have that IP; stale ccmni0 has no IP
        if (!srcIp.isNullOrBlank()) {
            val validateAddr = runSuCommand("ip -4 addr show dev $iface")
            val hasIp = validateAddr?.contains(srcIp!!) == true
            if (!hasIp) {
                Log.w(TAG, "getDefaultNetworkInfoViaRoot: stale iface $iface does not have ip $srcIp, re-probing")
                val probed = probePhysicalInterface()
                if (probed != null) {
                    iface = probed.first
                    srcIp = probed.second
                }
            }
        }
        if (srcIp.isNullOrBlank()) {
            Log.w(TAG, "getDefaultNetworkInfoViaRoot: could not get IPv4 for $iface")
            return null
        }
        val ipv4Addr = "$srcIp:0"
        // Cellular (rmnet/ccmni) has no gateway MAC (Raw IP / layer-3 only).
        // Return empty so YAML emits router_mac: "" and paqet runs in RawIP mode.
        // Never emit a placeholder MAC here: ParseMAC("00:00:...") succeeds and
        // would wrongly disable RawIP mode in the core.
        val gatewayIp = viaMatch?.groupValues?.get(1) ?: ""
        val routerMac = if (gatewayIp.isNotBlank()) {
            readMacFromArpViaRoot(gatewayIp) ?: ""
        } else {
            ""
        }
        val ipv6Addr = resolveIpv6ViaRoot(iface)
        Log.d(TAG, "getDefaultNetworkInfoViaRoot: iface=$iface ipv4=$ipv4Addr ipv6=$ipv6Addr routerMac=$routerMac (cellular fallback)")
        return DefaultNetworkInfo(iface, ipv4Addr, ipv6Addr, routerMac)
    }

    private fun resolveIpv6ForInterface(iface: String): String {
        val cmdOut = runSuCommand("ip -6 addr show dev $iface") ?: return ""
        return parseIpv6FromAddrShow(cmdOut)
    }

    private fun resolveIpv6ViaRoot(iface: String): String {
        val out = runSuCommand("ip -6 addr show dev $iface") ?: return ""
        return parseIpv6FromAddrShow(out)
    }

    private fun parseIpv6FromAddrShow(output: String): String {
        // lines like: "    inet6 2a03:ef43:b:8464:1:1:df66:94b5/64 scope global ..."
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("inet6")) continue
            // skip link-local fe80:: and scope link
            if (trimmed.contains("scope link")) continue
            val m = Regex("""inet6\s+([0-9a-fA-F:]+)""").find(trimmed) ?: continue
            val ip = m.groupValues[1]
            if (ip.startsWith("fe80:")) continue
            if (ip == "::1") continue
            // Must be global
            return "[$ip]:0"
        }
        return ""
    }

    /** Probe physical egress interfaces (ccmni/rmnet/wlan) that can actually reach 8.8.8.8. */
    private fun probePhysicalInterface(): Pair<String, String>? {
        val addrShow = runSuCommand("ip -o -4 addr show") ?: return null
        Log.d(TAG, "probePhysicalInterface: addrShow=${addrShow.take(800)}")
        val candidates = mutableListOf<Pair<String, String>>()
        for (line in addrShow.lines()) {
            val m = Regex("""\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)""").find(line) ?: continue
            val iface = m.groupValues[1]
            val ip = m.groupValues[2]
            if (iface.startsWith("tun") || iface.startsWith("dummy") || iface == "lo") continue
            candidates.add(iface to ip)
        }
        val sorted = candidates.sortedWith(compareBy(
            { when {
                it.first.startsWith("ccmni") || it.first.startsWith("rmnet") -> 0
                it.first.startsWith("wlan") -> 1
                else -> 2
            }},
            { it.first }
        ))
        for ((iface, ip) in sorted) {
            val out = runSuCommand("ip route get 8.8.8.8 from $ip oif $iface")
            if (!out.isNullOrBlank() && !out.contains("No route to host") && !out.contains("Network is unreachable")) {
                Log.d(TAG, "probePhysicalInterface: candidate $iface $ip reachable: $out")
                return iface to ip
            } else {
                Log.d(TAG, "probePhysicalInterface: candidate $iface $ip not reachable: ${out?.take(200)}")
            }
        }
        return null
    }

    private fun isWifiInterface(iface: String): Boolean { val n = iface.lowercase(); return n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("ap") }
    private fun resolveGatewayViaRoot(iface: String): String? { val rs = runSuCommand("ip route show dev " + iface); rs?.lines()?.forEach { line -> Regex("\\bvia\\s+(\\S+)").find(line)?.let { return it.groupValues[1] } }; val g = runSuCommand("ip route get 8.8.8.8"); g?.let { Regex("\\bvia\\s+(\\S+)").find(it)?.let { m -> return m.groupValues[1] } }; return null }
    private fun readMacFromArp(ip: String): String? {
        if (ip.isEmpty()) return null
        val arpFile = File("/proc/net/arp")
        if (!arpFile.canRead()) {
            Log.d(TAG, "readMacFromArp: /proc/net/arp not readable")
            return null
        }
        val text = arpFile.readText()
        Log.d(TAG, "readMacFromArp: arp content (first 500 chars)=${text.take(500)}")
        return parseArpOutput(text, ip)
    }

    /**
     * Run `su -c "cat /proc/net/arp"` then fallback to `ip neigh show` to get gateway MAC (root required).
     */
    private fun readMacFromArpViaRoot(ip: String): String? {
        if (ip.isEmpty()) return null
        val arpOut = runSuCommand("cat /proc/net/arp")
        Log.d(TAG, "readMacFromArpViaRoot: su cat /proc/net/arp exit, length=${arpOut?.length}, preview=${arpOut?.take(400)}")
        arpOut?.let { output ->
            parseArpOutput(output, ip)?.let { mac ->
                Log.d(TAG, "readMacFromArpViaRoot: parsed MAC from arp: $mac")
                return mac
            }
        }
        val neighOut = runSuCommand("ip neigh show")
        Log.d(TAG, "readMacFromArpViaRoot: su ip neigh show length=${neighOut?.length}, preview=${neighOut?.take(400)}")
        neighOut?.lines()?.forEach { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.isNotEmpty() && parts[0] == ip) {
                val lladdrIdx = parts.indexOf("lladdr")
                if (lladdrIdx >= 0 && lladdrIdx + 1 < parts.size) {
                    val mac = parts[lladdrIdx + 1]
                    if (mac.contains(":")) {
                        Log.d(TAG, "readMacFromArpViaRoot: parsed MAC from ip neigh: $mac")
                        return mac
                    }
                }
            }
        }
        Log.d(TAG, "readMacFromArpViaRoot: no MAC found for $ip")
        return null
    }

    private fun runSuCommand(cmd: String): String? {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "runSuCommand: cmd=$cmd exitCode=$exitCode output=${output.take(200)}")
                return null
            }
            output
        } catch (e: Exception) {
            Log.w(TAG, "runSuCommand: cmd=$cmd exception=${e.message}", e)
            null
        }
    }

    /**
     * Parse /proc/net/arp: columns are IP(0), HW type(1), Flags(2), HW address(3), Mask(4), Device(5).
     */
    private fun parseArpOutput(arpText: String, targetIp: String): String? {
        val lines = arpText.lines()
        if (lines.isEmpty()) return null
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4 && parts[0] == targetIp) {
                val mac = parts[3]
                if (mac.contains(":") && mac != "00:00:00:00:00:00") return mac
            }
        }
        return null
    }
}
