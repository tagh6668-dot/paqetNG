package com.tagh6668.paqetng.data

/**
 * Builds paqet client YAML from [PaqetConfig]. Matches structure expected by paqet.
 * @param logLevel paqet log level: none, debug, info, warn, error, fatal
 */
/** Format TCP flag list for paqet YAML (e.g. ["PA", "S"]). */
private fun List<String>.toTcpFlagYaml(): String =
    if (isEmpty()) "[ \"$DEFAULT_TCP_FLAGS\" ]"
    else joinToString(", ") { "\"${it.trim()}\"" }.let { "[ $it ]" }

/** True when server is IPv6 (brackets). Host part contains ':'. */
private fun isIpv6Server(serverAddr: String): Boolean = serverAddr.trim().startsWith("[")

fun PaqetConfig.toPaqetYaml(logLevel: String = "debug"): String {
    val localList = localFlag?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_TCP_FLAGS)
    val remoteList = remoteFlag?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_TCP_FLAGS)
    val localFlagYaml = localList.toTcpFlagYaml()
    val remoteFlagYaml = remoteList.toTcpFlagYaml()
    val isManualMode = kcpMode == "manual"
    val manualParams = buildString {
        if (isManualMode) {
            val nodelay = kcpNodelay ?: KcpManualDefaults.NO_DELAY
            val interval = kcpInterval ?: KcpManualDefaults.INTERVAL
            val resend = kcpResend ?: KcpManualDefaults.RESEND
            val nocongestion = kcpNocongestion ?: KcpManualDefaults.NO_CONGESTION
            val wdelay = kcpWdelay ?: KcpManualDefaults.W_DELAY
            val acknodelay = kcpAcknodelay ?: KcpManualDefaults.ACK_NO_DELAY
            append("\n    nodelay: $nodelay")
            append("\n    interval: $interval")
            append("\n    resend: $resend")
            append("\n    nocongestion: $nocongestion")
            append("\n    wdelay: $wdelay")
            append("\n    acknodelay: $acknodelay")
        }
    }
    // Emit ipv6 block when server is IPv6, otherwise ipv4. Paqet validates that the matching
    // family is configured: "server address is IPv6, but the IPv6 interface is not configured".
    val useIpv6 = isIpv6Server(serverAddr)
    val networkBlock = if (useIpv6) {
        // Prefer ipv6Addr from provider; fall back to bracketed ipv4Addr if caller stashed it there
        val ipv6Source = when {
            !ipv6Addr.isNullOrBlank() -> ipv6Addr!!.trim()
            ipv4Addr.trim().startsWith("[") -> ipv4Addr.trim()
            else -> ""
        }
        // If still empty (e.g. old cached config), keep placeholder so error is explicit; caller should refresh.
        val addrVal = if (ipv6Source.isNotEmpty()) ipv6Source else "[::]:0"
        """  interface: "$networkInterface"
  ipv6:
    addr: "$addrVal"
    router_mac: "$routerMac""""
    } else {
        """  interface: "$networkInterface"
  ipv4:
    addr: "$ipv4Addr"
    router_mac: "$routerMac""""
    }
    return """
role: "client"
log:
  level: "$logLevel"
socks5:
  - listen: "$socksListen"
    username: ""
    password: ""
network:
$networkBlock
  tcp:
    local_flag: $localFlagYaml
    remote_flag: $remoteFlagYaml
server:
  addr: "$serverAddr"
transport:
  protocol: "kcp"
  conn: $conn
  kcp:
    mode: "$kcpMode"
    mtu: $mtu
    rcvwnd: 512
    sndwnd: 512
    block: "$kcpBlock"
    key: "$kcpKey"$manualParams
""".trimIndent()
}
