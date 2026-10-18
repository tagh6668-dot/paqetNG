package com.tagh6668.paqetng.paqet

import android.content.Context
import android.os.Build
import com.tagh6668.paqetng.data.AppLogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Extracts the paqet binary from assets, checks root, and runs/stops paqet with a given config path.
 * Captures paqet stdout/stderr into [logBuffer] when provided.
 */
class PaqetRunner(
    private val context: Context,
    private val logBuffer: AppLogBuffer? = null
) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    private var process: Process? = null

    /** When true, process exit is due to [stop()]; do not treat as crash. */
    @Volatile
    private var stopRequested = false

    /** Last config used for start; kept so we can restart on crash. Cleared on [stop()]. */
    private var lastConfigYaml: String? = null
    private var lastSessionSummary: String? = null

    /** Server port for anti-RST iptables rule; cleared on [stop()]. */
    private var lastRawPort: Int? = null

    /**
     * Serial executor for firewall ops: install/remove run off the main thread
     * (each `su` can take ~200ms) but strictly in call order, so a
     * remove-then-install sequence during restart cannot be reordered.
     */
    private val fwExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "paqet-fw").apply { isDaemon = true }
    }

    /**
     * Installs an iptables rule that drops kernel-generated TCP RST packets
     * for [port]: the kernel sees paqet's raw SYN-ACKs as unsolicited and
     * RSTs them, killing connections. Requires root. Best-effort: logs on failure.
     */
    private fun installAntiRstRuleBlocking(port: Int) {
        val r4 = runSuBlocking(
            "iptables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || " +
                "iptables -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP",
            tag = "anti-rst install (port=$port)"
        )
        if (r4 == null) {
            android.util.Log.e("PaqetRunner", "anti-rst install FAILED v4 port=$port — RST storm will kill raw-TCP! Check root / iptables.")
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "anti-rst install FAILED v4 port=$port — no protection! Check root/iptables")
        }
        val r6 = runSuBlocking(
            "ip6tables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null || " +
                "ip6tables -I OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP",
            tag = "anti-rst install v6 (port=$port)"
        )
        if (r6 == null) {
            android.util.Log.w("PaqetRunner", "anti-rst install FAILED v6 port=$port (ip6tables missing?)")
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "anti-rst install FAILED v6 port=$port (ip6tables missing?)")
        }
        // Verify: log current state of rules after install attempt
        val verifyCmd = "iptables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>&1; echo v4:$?; ip6tables -C OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>&1; echo v6:$?"
        val verify = runSuBlocking(verifyCmd, tag = "anti-rst verify (port=$port)")
        if (verify != null) {
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "anti-rst verify port=$port: $verify")
        }
    }

    /** Removes the anti-RST iptables rule installed by [installAntiRstRuleBlocking]. */
    private fun removeAntiRstRuleBlocking(port: Int) {
        runSuBlocking(
            "iptables -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null; true",
            tag = "anti-rst remove (port=$port)"
        )
        runSuBlocking(
            "ip6tables -D OUTPUT -p tcp --dport $port --tcp-flags RST RST -j DROP 2>/dev/null; true",
            tag = "anti-rst remove v6 (port=$port)"
        )
    }

    /** Parse server port from generated YAML: server:\n  addr: "host:port" . Supports [ipv6]:port and bare ipv6. */
    private fun parseServerPortFromYaml(yaml: String): Int? {
        val m = Regex("""(?m)^server:\\s*\\n\\s*addr:\\s*"([^"]+)""""").find(yaml) ?: return null
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

    /** Runs a root shell command on a background-safe blocking call; returns stdout or null. */
    private fun runSuBlocking(cmd: String, tag: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val timedOut: Boolean
            val success: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                success = p.waitFor(5, TimeUnit.SECONDS)
                timedOut = !success
            } else {
                val t = Thread { p.waitFor() }
                t.start()
                t.join(5000)
                val alive = t.isAlive
                timedOut = alive
                success = !alive && p.exitValue() == 0
                // For old branch we must check exit code via trick above; if wait succeeded we need to verify code
                // Wait already returned, check exit code via available method
                if (!alive) {
                    try {
                        val code = p.exitValue()
                        if (code != 0) {
                            android.util.Log.w("PaqetRunner", "$tag failed exit=$code out=${out.take(400)}")
                            logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag: FAILED exit=$code out=${out.take(400)}")
                        } else {
                            logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag: exit=true ${out.take(200)}")
                        }
                        p.destroy()
                        return if (code == 0) out else null
                    } catch (_: Exception) {}
                }
            }
            if (timedOut) {
                android.util.Log.w("PaqetRunner", "$tag timeout after 5s cmd=$cmd")
                logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag: TIMEOUT 5s cmd=$cmd out=${out.take(200)}")
                try { p.destroyForcibly() } catch (_: Exception) { p.destroy() }
                return null
            }
            val code = try { p.exitValue() } catch (_: Exception) { -1 }
            if (code != 0) {
                android.util.Log.w("PaqetRunner", "$tag failed exit=$code out=${out.take(400)}")
                logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag: FAILED exit=$code out=${out.take(400)}")
                p.destroy()
                return null
            }
            p.destroy()
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag: exit=true ${out.take(200)}")
            out
        } catch (e: Exception) {
            android.util.Log.w("PaqetRunner", "$tag exception", e)
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "$tag failed: ${e.message} ${e.stackTraceToString().take(400)}")
            null
        }
    }

    /** Called when paqet exits unexpectedly (crash). Arguments are last config for restart. */
    var onCrashed: ((configYaml: String, sessionSummary: String?) -> Unit)? = null

    private val paqetDir: File
        get() = File(context.filesDir, "paqet").also { if (!it.exists()) it.mkdirs() }

    private val binaryFile: File
        get() = File(paqetDir, "paqet")

    /**
     * Asset path for the paqet binary: ABI-specific (e.g. arm64-v8a/paqet) if built by buildPaqet, else "paqet".
     */
    private fun getPaqetAssetPath(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return ASSET_BINARY_NAME
        val abiPath = "$abi/$ASSET_BINARY_NAME"
        return try {
            context.assets.open(abiPath).close()
            abiPath
        } catch (_: IOException) {
            ASSET_BINARY_NAME
        }
    }

    /**
     * Copies the paqet binary from assets to app filesDir and makes it executable.
     * Prefers ABI-specific asset (arm64-v8a/paqet, armeabi-v7a/paqet) when built from submodule; else uses "paqet".
     * Always ensures the on-disk binary matches the APK asset (fixes stale binary after APK update).
     * Uses streaming SHA-256 compare to avoid 16 MB allocation on Main thread (Bug #4).
     */
    fun ensureBinary(): Boolean {
        return try {
            val assetPath = getPaqetAssetPath()
            if (!binaryFile.exists() || !binaryFile.canExecute()) {
                copyAssetToFile(assetPath, binaryFile)
                return true
            }
            // Size check via AssetFileDescriptor when available
            val assetLen = try { context.assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
            if (assetLen != -1L && binaryFile.length() != assetLen) {
                copyAssetToFile(assetPath, binaryFile)
                return true
            }
            // Streaming digest compare
            val assetDigest = sha256OfAsset(assetPath)
            val fileDigest = sha256OfFile(binaryFile)
            if (assetDigest != null && fileDigest != null && assetDigest.contentEquals(fileDigest)) {
                return true
            }
            if (assetLen == -1L) {
                // No length info — fall back to digest mismatch => rewrite
                // If digest failed (e.g. asset not readable), rewrite anyway
                if (assetDigest == null || fileDigest == null) {
                    copyAssetToFile(assetPath, binaryFile)
                    return true
                }
            }
            copyAssetToFile(assetPath, binaryFile)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun copyAssetToFile(assetPath: String, dst: File) {
        context.assets.open(assetPath).use { input ->
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst.setExecutable(true, false)
    }

    private fun sha256OfAsset(assetPath: String): ByteArray? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest()
    } catch (_: Exception) { null }

    private fun sha256OfFile(file: File): ByteArray? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest()
    } catch (_: Exception) { null }

    /**
     * Checks whether root (su) is available. Runs on a background thread; result via [rootAvailable].
     */
    fun checkRoot() {
        Thread {
            val available = isRootAvailableBlocking()
            _rootAvailable.value = available
        }.start()
    }

    /**
     * Blocking check: runs `su -c "id"` and returns true if exit code is 0.
     */
    fun isRootAvailableBlocking(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(5, TimeUnit.SECONDS)
            } else {
                // For API < 26, use a thread with timeout
                var exited = false
                val thread = Thread {
                    exited = p.waitFor() == 0
                }
                thread.start()
                thread.join(5000)
                exited
            }
            p.destroy()
            exit
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Kills any running paqet binary process (e.g. orphaned from a previous run).
     * Uses the binary path so only the extracted paqet executable is killed, not the app process.
     * Safe to call when no paqet is running.
     */
    private fun killAnyExistingPaqetBlocking() {
        if (!binaryFile.exists()) return
        try {
            val path = binaryFile.absolutePath
            val escapedPath = path.replace("'", "'\\''")
            val cmd = "pkill -9 -f '$escapedPath' || true"
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(1, TimeUnit.SECONDS)
            } else {
                // For API < 26, use a thread with timeout
                val thread = Thread {
                    p.waitFor()
                }
                thread.start()
                thread.join(1000)
            }
            p.destroy()
            try {
                val sweep = ProcessBuilder("su", "-c", "pkill -9 -f 'paqet.*run -c' || true").redirectErrorStream(true).start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sweep.waitFor(1, TimeUnit.SECONDS) else { val tt = Thread { sweep.waitFor() }; tt.start(); tt.join(1000) }
                sweep.destroy()
            } catch (_: Exception) {}
            Thread.sleep(150)
        } catch (e: Exception) {
            // ignore
        }
    }

    private var outputReaderThread: Thread? = null

    /**
     * Writes [configYaml] to a file and starts paqet as root with that config.
     * @param configYaml full YAML content for paqet client
     * @param sessionSummary optional one-line summary to log (e.g. server, interface, router MAC)
     * @return true if the process was started (root + binary available)
     */
    fun startWithYaml(configYaml: String, sessionSummary: String? = null): Boolean {
        if (!ensureBinary()) return false
        if (!isRootAvailableBlocking()) return false
        stop()
        outputReaderThread?.join(500)
        outputReaderThread = null
        killAnyExistingPaqetBlocking()
        lastConfigYaml = configYaml
        lastSessionSummary = sessionSummary
        // Parse server port for the anti-RST iptables rule (Bug #1: was parsing network ipv4 :0)
        lastRawPort = parseServerPortFromYaml(configYaml)
        val fwPort = lastRawPort
        if (fwPort != null) {
            // Execute synchronously once before queuing to catch immediate su/iptables failure early
            fwExecutor.execute { installAntiRstRuleBlocking(fwPort) }
        } else {
            android.util.Log.e("PaqetRunner", "parseServerPortFromYaml returned null — anti-RST NOT installed! yaml preview=${configYaml.take(800)}")
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "anti-rst SKIP: could not parse server port from YAML — RST protection disabled! preview=${configYaml.take(400)}")
        }
        val configFile = File(paqetDir, "config_run.yaml")
        return try {
            configFile.writeText(configYaml)
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "=== paqet start ===")
            sessionSummary?.let { logBuffer?.append(AppLogBuffer.TAG_SESSION, it) }
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "config: $configFile")
            val binaryPath = binaryFile.absolutePath
            val configPath = configFile.absolutePath
            val command = "$binaryPath run -c $configPath"
            logBuffer?.append(AppLogBuffer.TAG_PAQET, "command: $command")
            process = ProcessBuilder("su", "-c", command)
                .directory(paqetDir)
                .redirectErrorStream(true)
                .start()
            stopRequested = false
            _isRunning.value = true
            val proc = process
            if (proc != null && logBuffer != null) {
                outputReaderThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                            lines.forEach { line ->
                                logBuffer.append(AppLogBuffer.TAG_PAQET, line)
                            }
                        }
                        logBuffer.append(AppLogBuffer.TAG_PAQET, "process exited")
                    } catch (e: IOException) {
                        // Stream closed when process is destroyed (e.g. stop() on another thread)
                        logBuffer.append(AppLogBuffer.TAG_PAQET, "process exited")
                    }
                }.apply { isDaemon = true; start() }
            }
            Thread {
                val proc = process
                val exitCode = proc?.waitFor() ?: -1
                _isRunning.value = false
                process = null
                if (!stopRequested) {
                    val yaml = lastConfigYaml
                    val summary = lastSessionSummary
                    if (yaml != null) {
                        logBuffer?.append(AppLogBuffer.TAG_SESSION, "paqet exited unexpectedly (exitCode=$exitCode); will attempt restart if enabled")
                        onCrashed?.invoke(yaml, summary)
                    }
                }
            }.start()
            true
        } catch (e: Exception) {
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "start failed: ${e.message}")
            _isRunning.value = false
            false
        }
    }

    /**
     * Stops and kills the running paqet process if any.
     * Sends destroy immediately; a background thread does wait, destroyForcibly, and killAnyExistingPaqet.
     * Clears stored config so we do not auto-restart after user disconnect.
     */
    fun stop() {
        stopRequested = true
        lastConfigYaml = null
        lastSessionSummary = null
        val fwPort = lastRawPort
        lastRawPort = null
        if (fwPort != null) fwExecutor.execute { removeAntiRstRuleBlocking(fwPort) }
        val p = process
        process = null
        _isRunning.value = false
        if (p != null) {
            logBuffer?.append(AppLogBuffer.TAG_SESSION, "=== paqet stop ===")
            p.destroy()
            Thread {
                val terminated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    p.waitFor(2, TimeUnit.SECONDS)
                } else {
                    // For API < 26, use a thread with timeout
                    val thread = Thread {
                        p.waitFor()
                    }
                    thread.start()
                    thread.join(2000)
                    !thread.isAlive
                }
                if (!terminated) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        p.destroyForcibly()
                    } else {
                        // For API < 26, destroy() is already called, try again
                        p.destroy()
                    }
                }
                outputReaderThread?.join(1000)
                outputReaderThread = null
                killAnyExistingPaqetBlocking()
            }.start()
        } else {
            Thread { killAnyExistingPaqetBlocking() }.start()
        }
    }

    companion object {
        private const val ASSET_BINARY_NAME = "paqet"
    }
}
