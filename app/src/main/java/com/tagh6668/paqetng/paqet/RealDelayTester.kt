package com.tagh6668.paqetng.paqet

import android.content.Context
import android.util.Log
import com.tagh6668.paqetng.data.DefaultNetworkInfoProvider
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.toPaqetYaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Parallel "real delay" tester that runs paqet binaries in isolated temp dirs.
 * Does NOT touch the main PaqetRunner, so the active VPN/SOCKS session is never interrupted.
 * Uses shared paqet binary execution and direct SOCKS5 remote DNS probing to bypass
 * local DNS poisoning (RFC 2544 unroutable fake IPs).
 */
class RealDelayTester(
    private val context: Context,
    private val networkInfoProvider: DefaultNetworkInfoProvider? = null
) {
    companion object {
        private const val TAG = "RealDelayTester"
        const val CONCURRENCY = 3
        const val TEST_PORT_START = 28000
    }

    private val _results = MutableStateFlow<Map<String, Int>>(emptyMap())
    val results: StateFlow<Map<String, Int>> = _results.asStateFlow()

    private val _testingIds = MutableStateFlow<Set<String>>(emptySet())
    val testingIds: StateFlow<Set<String>> = _testingIds.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    @Volatile private var cancelled = false
    private val activeHandles = Collections.synchronizedList(mutableListOf<TesterHandle>())

    data class TesterHandle(
        val id: String,
        val port: Int,
        val dir: File,
        val process: Process?,
        val antiRstPort: Int?,
        val cfgFile: File
    )

    fun cancel() {
        cancelled = true
        _isTesting.value = false
        synchronized(activeHandles) {
            for (h in activeHandles.toList()) {
                try { killHandle(h) } catch (_: Exception) {}
            }
            activeHandles.clear()
            try { cleanupOrphanedDirs() } catch (_: Exception) {}
        }
        _testingIds.value = emptySet()
        Log.d(TAG, "cancelled")
    }

    fun clear() {
        cancel()
        _results.value = emptyMap()
    }

    suspend fun testAll(
        configs: List<PaqetConfig>,
        checkUrlRaw: String,
        timeoutMs: Int,
        logLevel: String,
        allowLan: Boolean
    ) {
        if (configs.isEmpty()) return
        if (_isTesting.value) return
        cancelled = false
        _isTesting.value = true
        _results.value = emptyMap()
        _testingIds.value = emptySet()
        activeHandles.clear()

        val checkUrl = normalizeCheckUrl(checkUrlRaw)
        val timeout = timeoutMs.coerceIn(3000, 60000)
        Log.d(TAG, "testAll count=${configs.size} url=$checkUrl timeout=${timeout}ms concurrency=$CONCURRENCY allowLan=$allowLan")

        val netInfo = try { networkInfoProvider?.getDefaultNetworkInfo() } catch (_: Exception) { null }
        val ports = allocatePorts(configs.size)

        val semaphore = Semaphore(CONCURRENCY)
        try {
            coroutineScope {
                val jobs = configs.mapIndexed { idx, cfg ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (cancelled) return@withPermit
                            _testingIds.update { it + cfg.id }
                            val port = ports[idx]
                            val ms = runOne(cfg, port, netInfo, checkUrl, timeout, logLevel, allowLan)
                            if (!cancelled) _results.update { it + (cfg.id to ms) }
                            _testingIds.update { it - cfg.id }
                            Log.d(TAG, "${cfg.serverAddr}:$port -> ${ms}ms")
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            _isTesting.value = false
            _testingIds.value = emptySet()
            Log.d(TAG, "done results=${_results.value}")
        }
    }

    private fun normalizeCheckUrl(raw: String): String {
        var u = raw.trim()
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.isEmpty()) u = "https://www.gstatic.com/generate_204"
        return u
    }

    private fun allocatePorts(n: Int): List<Int> {
        val out = mutableListOf<Int>()
        var candidate = TEST_PORT_START
        while (out.size < n && candidate < TEST_PORT_START + 3000) {
            if (isPortFree(candidate)) out.add(candidate)
            candidate++
            if (candidate == 1284) candidate = 1285
        }
        while (out.size < n) {
            var p = TEST_PORT_START + (out.size * 7 % 2000)
            var tries = 0
            while (!isPortFree(p) && tries < 50) { p++; tries++ }
            out.add(p)
        }
        return out
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)); true }
        } catch (_: Exception) { false }
    }

    private fun runOne(
        config: PaqetConfig,
        port: Int,
        netInfo: com.tagh6668.paqetng.data.DefaultNetworkInfo?,
        checkUrl: String,
        timeoutMs: Int,
        logLevel: String,
        allowLan: Boolean
    ): Int {
        var handle: TesterHandle? = null
        return try {
            val socksListen = if (allowLan) "0.0.0.0:$port" else "127.0.0.1:$port"
            val withNet = if (netInfo != null) config.copy(
                networkInterface = netInfo.interfaceName,
                ipv4Addr = netInfo.ipv4Addr,
                ipv6Addr = netInfo.ipv6Addr,
                routerMac = netInfo.routerMac,
                socksListen = socksListen
            ) else config.copy(socksListen = socksListen)
            val yaml = withNet.toPaqetYaml(logLevel = logLevel)
            handle = startIsolatedPaqet(withNet, yaml, port) ?: return -1
            synchronized(activeHandles) { activeHandles.add(handle) }
            if (cancelled) return -1
            if (!waitPortReady(port, 4000)) {
                Log.w(TAG, "SOCKS not ready ${config.serverAddr}:$port")
                return -1
            }
            if (cancelled) return -1
            try { Thread.sleep(180) } catch (_: Exception) {}
            probeViaSocks(port, checkUrl, timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "runOne ${config.serverAddr}:$port", e)
            -1
        } finally {
            if (handle != null) {
                synchronized(activeHandles) { activeHandles.remove(handle) }
                try { killHandle(handle) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Public helper to probe SOCKS port directly using remote DNS resolution.
     * Can be reused by HomeViewModel.testLatency without code duplication.
     */
    fun probeViaSocksDirect(port: Int, checkUrl: String, timeoutMs: Int): Int {
        return probeViaSocks(port, checkUrl, timeoutMs)
    }

    private fun probeViaSocks(port: Int, checkUrl: String, timeoutMs: Int): Int {
        val uri = try { java.net.URI(checkUrl) } catch (_: Exception) { null }
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: "www.gstatic.com"
        val path = if (!uri?.rawPath.isNullOrEmpty()) {
            if (uri?.rawQuery != null) "${uri.rawPath}?${uri.rawQuery}" else uri.rawPath
        } else "/generate_204"
        val isExplicitHttps = uri?.scheme.equals("https", ignoreCase = true)
        val explicitPort = if (uri != null && uri.port > 0) uri.port else null
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

        fun tryConnect(useHttps: Boolean, targetPort: Int, currentTimeout: Int): Int {
            return try {
                val start = System.currentTimeMillis()
                val rawSocket = Socket(proxy)
                rawSocket.soTimeout = currentTimeout
                // Critical: InetSocketAddress.createUnresolved forces SOCKS5 client to send ATYP=0x03
                // (domain name) instead of ATYP=0x01 (IPv4). This completely circumvents local Android
                // DNS poisoning which returns unroutable RFC 2544 fake IPs (e.g. 198.19.x.x) on cellular.
                rawSocket.connect(InetSocketAddress.createUnresolved(host, targetPort), currentTimeout)

                val socket = if (useHttps) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = sslFactory.createSocket(rawSocket, host, targetPort, true) as SSLSocket
                    sslSocket.soTimeout = currentTimeout
                    sslSocket.startHandshake()
                    sslSocket
                } else {
                    rawSocket
                }

                socket.use { s ->
                    val out = s.getOutputStream()
                    val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                    out.write(req.toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    val statusLine = reader.readLine()
                    if (statusLine != null && (statusLine.startsWith("HTTP/1.") || statusLine.startsWith("HTTP/2"))) {
                        val delay = (System.currentTimeMillis() - start).toInt()
                        Log.d(TAG, "probe :$port success [https=$useHttps, port=$targetPort] ($statusLine) in ${delay}ms")
                        delay
                    } else {
                        Log.w(TAG, "probe :$port invalid response: $statusLine")
                        -1
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probe :$port [https=$useHttps, port=$targetPort] failed: ${e.message}")
                -1
            }
        }

        return if (isExplicitHttps) {
            val targetPort = explicitPort ?: 443
            val res = tryConnect(true, targetPort, timeoutMs)
            if (res > 0) res
            else if (targetPort == 443) {
                val fallbackTimeout = (timeoutMs / 2).coerceAtLeast(2000)
                val fallbackRes = tryConnect(false, 80, fallbackTimeout)
                if (fallbackRes > 0) {
                    Log.d(TAG, "probe :$port HTTP fallback succeeded in ${fallbackRes}ms")
                    fallbackRes
                } else -1
            } else -1
        } else {
            val targetPort = explicitPort ?: 80
            tryConnect(false, targetPort, timeoutMs)
        }
    }

    private fun waitPortReady(port: Int, maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (cancelled) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); return true }
            } catch (_: Exception) {}
            try { Thread.sleep(140) } catch (_: Exception) {}
        }
        return false
    }

    private fun getSharedBinary(): File? {
        val srcBin = File(File(context.filesDir, "paqet"), "paqet")
        if (srcBin.exists() && srcBin.canExecute()) return srcBin
        val dir = File(context.filesDir, "paqet")
        dir.mkdirs()
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val candidates = listOf("$abi/paqet", "paqet")
        for (asset in candidates) {
            try {
                context.assets.open(asset).use { input ->
                    srcBin.outputStream().use { out -> input.copyTo(out) }
                }
                srcBin.setExecutable(true)
                return srcBin
            } catch (_: Exception) {}
        }
        return if (srcBin.exists() && srcBin.canExecute()) srcBin else null
    }

    private fun startIsolatedPaqet(config: PaqetConfig, yaml: String, port: Int): TesterHandle? {
        val dir = File(context.filesDir, "paqet_test_${port}_${System.currentTimeMillis()}")
        dir.mkdirs()
        val cfgFile = File(dir, "config.yaml")
        try { cfgFile.writeText(yaml) } catch (_: Exception) { return null }
        val bin = getSharedBinary() ?: run { try { dir.deleteRecursively() } catch (_: Exception) {}; return null }
        val rawPort = parseServerPortFromYaml(yaml)
        if (rawPort != null) installAntiRst(rawPort)
        val proc = try {
            ProcessBuilder("su", "-c", "${bin.absolutePath} run -c ${cfgFile.absolutePath}")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "start failed :$port", e)
            if (rawPort != null) removeAntiRst(rawPort)
            try { dir.deleteRecursively() } catch (_: Exception) {}
            return null
        }
        Thread { try { proc.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        return TesterHandle(config.id, port, dir, proc, rawPort, cfgFile)
    }

    private fun killHandle(h: TesterHandle) {
        try { h.process?.destroy() } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) h.process?.waitFor(500, TimeUnit.MILLISECONDS)
            else { val t = Thread { h.process?.waitFor() }; t.start(); t.join(500) }
        } catch (_: Exception) {}
        try { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) h.process?.destroyForcibly() } catch (_: Exception) {}
        try {
            val cfgPath = h.cfgFile.absolutePath.replace("'", "'\\''")
            ProcessBuilder("su", "-c", "pkill -9 -f '$cfgPath' || true").redirectErrorStream(true).start().let { p ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) p.waitFor(500, TimeUnit.MILLISECONDS) else { val t = Thread { p.waitFor() }; t.start(); t.join(500) }
                p.destroy()
            }
        } catch (_: Exception) {}
        if (h.antiRstPort != null) removeAntiRst(h.antiRstPort)
        try { h.dir.deleteRecursively() } catch (_: Exception) {}
    }

    // Ref-counted anti-RST so parallel testers sharing same server port don't delete each other's rule
    private val antiRstRefCount = mutableMapOf<Int, Int>()
    private val antiRstLock = Any()

    private fun installAntiRst(port: Int) {
        val shouldInstall = synchronized(antiRstLock) {
            val c = (antiRstRefCount[port] ?: 0) + 1
            antiRstRefCount[port] = c
            c == 1
        }
        if (shouldInstall) {
            val cmd = "iptables -w 5 -t filter -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || iptables -w 5 -t filter -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP ; " +
                      "iptables -w 5 -t mangle -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || iptables -w 5 -t mangle -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP ; " +
                      "ip6tables -w 5 -t filter -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || ip6tables -w 5 -t filter -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP ; " +
                      "ip6tables -w 5 -t mangle -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || ip6tables -w 5 -t mangle -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP"
            runSu(cmd)
        }
    }

    private fun removeAntiRst(port: Int) {
        val shouldRemove = synchronized(antiRstLock) {
            val c = (antiRstRefCount[port] ?: 1) - 1
            if (c <= 0) { antiRstRefCount.remove(port); true } else { antiRstRefCount[port] = c; false }
        }
        if (shouldRemove) {
            val app = context.applicationContext as? com.tagh6668.paqetng.PaqetNGApplication
            if (app?.paqetRunner?.activeAntiRstPort == port) {
                return
            }
            val cmd = "iptables -w 5 -t filter -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || true ; " +
                      "iptables -w 5 -t mangle -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || true ; " +
                      "ip6tables -w 5 -t filter -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || true ; " +
                      "ip6tables -w 5 -t mangle -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || true"
            runSu(cmd)
        }
    }

    private fun runSu(cmd: String) {
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = java.io.ByteArrayOutputStream()
            val t = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }
            t.start()
            val exited = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                p.waitFor(5, TimeUnit.SECONDS)
            } else {
                val waiter = Thread { p.waitFor() }
                waiter.start()
                waiter.join(5000)
                !waiter.isAlive
            }
            if (!exited) {
                try { p.destroyForcibly() } catch (_: Exception) { p.destroy() }
                t.interrupt()
            } else {
                p.destroy()
            }
        } catch (_: Exception) {}
    }

    private fun parseServerPortFromYaml(yaml: String): Int? {
        val m = Regex("""(?m)^\s*server:\s*(?:\r?\n)\s*addr:\s*["']?([^"'\r\n]+)["']?""").find(yaml)
            ?: Regex("""(?m)^\s*addr:\s*["']?([^"'\r\n]+)["']?""").find(yaml)
            ?: return null
        val addr = m.groupValues[1].trim()
        if (addr.isEmpty()) return null
        return try {
            when {
                addr.startsWith("[") -> {
                    val close = addr.lastIndexOf(']')
                    if (close < 0) null else addr.substring(close + 1).trim().removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
                }
                else -> addr.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
            }
        } catch (_: Exception) { null }
    }

    /** Delete orphaned paqet_test_* dirs left from previous crash. Call on app start if needed. */
    fun cleanupOrphanedDirs() {
        try {
            context.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("paqet_test_") }?.forEach {
                try { it.deleteRecursively() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
