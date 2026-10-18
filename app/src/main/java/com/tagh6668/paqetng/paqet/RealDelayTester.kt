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
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Parallel "real delay" tester that runs paqet binaries in isolated temp dirs.
 * Does NOT touch the main PaqetRunner, so the active VPN/SOCKS session is never interrupted.
 * Concurrency = 10. Each tester gets its own SOCKS port and temp dir/binary copy.
 */
class RealDelayTester(
    private val context: Context,
    private val networkInfoProvider: DefaultNetworkInfoProvider? = null
) {
    companion object {
        private const val TAG = "RealDelayTester"
        const val CONCURRENCY = 10
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
        val antiRstPort: Int?
    )

    fun cancel() {
        cancelled = true
        _isTesting.value = false
        synchronized(activeHandles) {
            for (h in activeHandles.toList()) {
                try { killHandle(h) } catch (_: Exception) {}
            }
            activeHandles.clear()
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
        _isTesting.value = false
        Log.d(TAG, "done results=${_results.value}")
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
            // avoid reusing same port within this batch
            // isPortFree already ensures not bound; but also avoid duplicates in out
        }
        while (out.size < n) {
            // fallback: find random free
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

    private fun probeViaSocks(port: Int, checkUrl: String, timeoutMs: Int): Int {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val url = URL(checkUrl)
            val conn = url.openConnection(proxy) as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                val start = System.currentTimeMillis()
                conn.connect()
                val code = conn.responseCode
                Log.d(TAG, "probe :$port code=$code")
                (System.currentTimeMillis() - start).toInt()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed :$port", e)
            -1
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

    private fun ensureBinaryCopy(dstDir: File): File? {
        val srcBin = File(File(context.filesDir, "paqet"), "paqet")
        return try {
            val dst = File(dstDir, "paqet")
            if (srcBin.exists() && srcBin.canExecute()) {
                dst.parentFile?.mkdirs()
                srcBin.copyTo(dst, overwrite = true)
                dst.setExecutable(true)
                dst
            } else {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
                val candidates = listOf("$abi/paqet", "paqet")
                var ok = false
                for (asset in candidates) {
                    try {
                        context.assets.open(asset).use { input ->
                            dst.parentFile?.mkdirs()
                            dst.outputStream().use { out -> input.copyTo(out) }
                        }
                        dst.setExecutable(true); ok = true; break
                    } catch (_: Exception) {}
                }
                if (ok) dst else null
            }
        } catch (_: Exception) { null }
    }

    private fun startIsolatedPaqet(config: PaqetConfig, yaml: String, port: Int): TesterHandle? {
        val dir = File(context.filesDir, "paqet_test_${port}_${System.currentTimeMillis()}")
        dir.mkdirs()
        val cfgFile = File(dir, "config.yaml")
        try { cfgFile.writeText(yaml) } catch (_: Exception) { return null }
        val bin = ensureBinaryCopy(dir) ?: run { try { dir.deleteRecursively() } catch (_: Exception) {}; return null }
        val rawPort = Regex("""addr:\s*"[^"]*:(\d+)"""").find(yaml)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
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
        return TesterHandle(config.id, port, dir, proc, rawPort)
    }

    private fun killHandle(h: TesterHandle) {
        try { h.process?.destroy() } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) h.process?.waitFor(800, TimeUnit.MILLISECONDS)
            else { val t = Thread { h.process?.waitFor() }; t.start(); t.join(800) }
        } catch (_: Exception) {}
        try { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) h.process?.destroyForcibly() } catch (_: Exception) {}
        try {
            val binPath = File(h.dir, "paqet").absolutePath.replace("'", "'\\''")
            ProcessBuilder("su", "-c", "pkill -9 -f '$binPath' || true").redirectErrorStream(true).start().let { p ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) p.waitFor(700, TimeUnit.MILLISECONDS) else { val t = Thread { p.waitFor() }; t.start(); t.join(700) }
                p.destroy()
            }
        } catch (_: Exception) {}
        if (h.antiRstPort != null) removeAntiRst(h.antiRstPort)
        try { h.dir.deleteRecursively() } catch (_: Exception) {}
        try { Thread.sleep(60) } catch (_: Exception) {}
    }

    private fun installAntiRst(port: Int) {
        runSu("iptables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || iptables -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP")
        runSu("ip6tables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || ip6tables -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP")
    }
    private fun removeAntiRst(port: Int) {
        runSu("iptables -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null; true")
        runSu("ip6tables -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null; true")
    }
    private fun runSu(cmd: String) {
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) p.waitFor(4, TimeUnit.SECONDS) else { val t = Thread { p.waitFor() }; t.start(); t.join(4000) }
            p.destroy()
        } catch (_: Exception) {}
    }
}
