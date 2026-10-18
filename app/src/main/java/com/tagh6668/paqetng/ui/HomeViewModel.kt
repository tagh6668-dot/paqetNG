package com.tagh6668.paqetng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.ConfigRepository
import com.google.gson.Gson
import com.tagh6668.paqetng.data.KcpBlockOptions
import com.tagh6668.paqetng.data.DefaultNetworkInfoProvider
import com.tagh6668.paqetng.data.SettingsRepository
import com.tagh6668.paqetng.data.toPaqetYaml
import kotlinx.coroutines.flow.first
import com.tagh6668.paqetng.paqet.PaqetRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

class HomeViewModel(
    private val configRepository: ConfigRepository,
    private val paqetRunner: PaqetRunner,
    private val defaultNetworkInfoProvider: DefaultNetworkInfoProvider,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private companion object {
        private const val TAG = "HomeViewModel"
    }

    val configs: StateFlow<List<PaqetConfig>> = configRepository.configs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val isRunning: StateFlow<Boolean> = paqetRunner.isRunning

    private val _selectedConfigId = MutableStateFlow<String?>(null)
    val selectedConfigId: StateFlow<String?> = _selectedConfigId.asStateFlow()

    /** Config id we are currently connected with (null when disconnected). Used to restart paqet when user changes profile while connected. */
    private val _connectedConfigId = MutableStateFlow<String?>(null)
    val connectedConfigId: StateFlow<String?> = _connectedConfigId.asStateFlow()

    private val _editorConfig = MutableStateFlow<PaqetConfig?>(null)
    val editorConfig: StateFlow<PaqetConfig?> = _editorConfig.asStateFlow()

    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

    private val _latencyTesting = MutableStateFlow(false)
    val latencyTesting: StateFlow<Boolean> = _latencyTesting.asStateFlow()

    // --- Test Real Delays (batch) ---
    private val _realDelayResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val realDelayResults: StateFlow<Map<String, Int>> = _realDelayResults.asStateFlow()

    private val _realDelayTestingIds = MutableStateFlow<Set<String>>(emptySet())
    val realDelayTestingIds: StateFlow<Set<String>> = _realDelayTestingIds.asStateFlow()

    private val _batchTesting = MutableStateFlow(false)
    val batchTesting: StateFlow<Boolean> = _batchTesting.asStateFlow()

    private var batchJob: Job? = null

    val showLatencyInUi: StateFlow<Boolean> = settingsRepository.showLatencyInUi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _showAddConfigDialog = MutableStateFlow(false)
    val showAddConfigDialog: StateFlow<Boolean> = _showAddConfigDialog.asStateFlow()

    private val _showRootRequiredDialog = MutableStateFlow(false)
    val showRootRequiredDialog: StateFlow<Boolean> = _showRootRequiredDialog.asStateFlow()

    private val _deleteConfigId = MutableStateFlow<String?>(null)
    val deleteConfigId: StateFlow<String?> = _deleteConfigId.asStateFlow()

    private val gson = Gson()

    init {
        paqetRunner.checkRoot()
        viewModelScope.launch {
            paqetRunner.rootAvailable.collectLatest { available ->
                if (available == false) _showRootRequiredDialog.value = true
            }
        }
        viewModelScope.launch {
            val lastId = settingsRepository.lastSelectedConfigId.first()
            if (!lastId.isNullOrEmpty()) _selectedConfigId.value = lastId
        }
    }

    fun selectConfig(id: String?) {
        _selectedConfigId.value = id
        viewModelScope.launch { settingsRepository.setLastSelectedConfigId(id) }
    }

    fun openAddConfigDialog() { _showAddConfigDialog.value = true }

    fun dismissAddConfigDialog() { _showAddConfigDialog.value = false }

    fun dismissRootRequiredDialog() { _showRootRequiredDialog.value = false }

    /** Parse text (paqet:// or JSON), add config. Returns true if parse succeeded (add is async). */
    fun addConfigFromImport(text: String?): Boolean {
        val configs = PaqetConfig.parseAllFromImport(text, gson)
        if (configs.isEmpty()) return false
        viewModelScope.launch {
            val port = settingsRepository.socksPort.first()
            var lastId: String? = null
            for (config in configs) {
                val withPort = config.copy(
                    id = "",
                    socksListen = config.socksListen.ifEmpty { "127.0.0.1:$port" }
                )
                lastId = configRepository.add(withPort)
            }
            if (lastId != null) selectConfig(lastId)
            _showAddConfigDialog.value = false
        }
        return true
    }

    /** Result of multi import: how many parsed/imported. */
    fun addConfigsFromImportWithCount(text: String?): Int {
        val configs = PaqetConfig.parseAllFromImport(text, gson)
        if (configs.isEmpty()) return 0
        viewModelScope.launch {
            val port = settingsRepository.socksPort.first()
            var lastId: String? = null
            for (config in configs) {
                val withPort = config.copy(
                    id = "",
                    socksListen = config.socksListen.ifEmpty { "127.0.0.1:$port" }
                )
                lastId = configRepository.add(withPort)
            }
            if (lastId != null) selectConfig(lastId)
            _showAddConfigDialog.value = false
        }
        return configs.size
    }

    fun exportAllToClipboardText(configs: List<PaqetConfig>): String =
        PaqetConfig.exportAllToText(configs)

    fun openAdd() {
        _showAddConfigDialog.value = false
        viewModelScope.launch {
            val port = settingsRepository.socksPort.first()
            _editorConfig.value = PaqetConfig(
                id = "",
                name = "",
                serverAddr = "",
                networkInterface = "wlan0",
                ipv4Addr = "",
                ipv6Addr = "",
                routerMac = "",
                kcpKey = "",
                kcpBlock = KcpBlockOptions.default,
                socksListen = "127.0.0.1:$port"
            )
            val info = defaultNetworkInfoProvider.getDefaultNetworkInfo() ?: return@launch
            _editorConfig.value = (_editorConfig.value ?: return@launch).copy(
                networkInterface = info.interfaceName,
                ipv4Addr = info.ipv4Addr,
                ipv6Addr = info.ipv6Addr,
                routerMac = info.routerMac
            )
        }
    }

    fun openEdit(config: PaqetConfig) { _editorConfig.value = config }

    fun dismissEditor() { _editorConfig.value = null }

    fun saveConfig(config: PaqetConfig) {
        viewModelScope.launch {
            if (config.id.isEmpty()) {
                val newId = configRepository.add(config)
                // Auto-select the newly created profile
                selectConfig(newId)
            } else {
                configRepository.update(config)
            }
            _editorConfig.value = null
        }
    }

    fun requestDeleteConfig(id: String) {
        _deleteConfigId.value = id
    }

    fun confirmDeleteConfig() {
        val id = _deleteConfigId.value ?: return
        _deleteConfigId.value = null
        viewModelScope.launch {
            configRepository.delete(id)
            if (_selectedConfigId.value == id) {
                _selectedConfigId.value = null
                settingsRepository.setLastSelectedConfigId(null)
            }
        }
    }

    fun cancelDeleteConfig() {
        _deleteConfigId.value = null
    }

    /**
     * Starts paqet with the given config. When paqet is successfully running, [onPaqetStarted]
     * is invoked so the VPN can be started (traffic will then go TUN → tun2socks → SOCKS → paqet).
     */
    fun connect(config: PaqetConfig?, onPaqetStarted: (() -> Unit)? = null) {
        if (config == null) return
        viewModelScope.launch {
            val allowLan = settingsRepository.socksListenLan.first()
            val logLevel = settingsRepository.logLevel.first()
            val port = config.socksPort()
            val socksListen = if (allowLan) "0.0.0.0:$port" else "127.0.0.1:$port"
            val info = defaultNetworkInfoProvider.getDefaultNetworkInfo()
            val configWithNetwork = (if (info != null) {
                config.copy(
                    networkInterface = info.interfaceName,
                    ipv4Addr = info.ipv4Addr,
                    ipv6Addr = info.ipv6Addr,
                    routerMac = info.routerMac,
                    socksListen = socksListen
                )
            } else {
                config.copy(socksListen = socksListen)
            })
            val summary = "server=${configWithNetwork.serverAddr} iface=${configWithNetwork.networkInterface} ip=${configWithNetwork.ipv4Addr} ipv6=${configWithNetwork.ipv6Addr} routerMac=${configWithNetwork.routerMac} socks=${configWithNetwork.socksListen}"
            val started = paqetRunner.startWithYaml(configWithNetwork.toPaqetYaml(logLevel = logLevel), summary)
            if (started) {
                _connectedConfigId.value = config.id
                onPaqetStarted?.invoke()
            } else {
                _connectedConfigId.value = null
            }
        }
    }

    fun disconnect() {
        _connectedConfigId.value = null
        _latencyMs.value = null
        paqetRunner.stop()
    }

    fun testLatency(config: PaqetConfig?) {
        Log.d(TAG, "testLatency called: config=${config?.serverAddr}, configNull=${config == null}")
        if (config == null) {
            Log.d(TAG, "testLatency: config is null, returning")
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "testLatency: starting, setting latencyTesting=true")
            _latencyTesting.value = true
            _latencyMs.value = null
            var checkUrl = settingsRepository.connectionCheckUrl.first().trim()
            if (checkUrl.isNotEmpty() && !checkUrl.startsWith("http://") && !checkUrl.startsWith("https://")) {
                checkUrl = "https://$checkUrl"
            }
            if (checkUrl.isEmpty()) checkUrl = SettingsRepository.DEFAULT_CONNECTION_CHECK_URL
            Log.d(TAG, "testLatency: connection check URL=$checkUrl via SOCKS 127.0.0.1:${config.socksPort()}")
            val socksPort = config.socksPort()
            val ms = withContext(Dispatchers.IO) {
                runCatching {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
                    val url = URL(checkUrl)
                    val conn = url.openConnection(proxy) as? HttpURLConnection ?: return@runCatching null
                    try {
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.instanceFollowRedirects = true
                        val start = System.currentTimeMillis()
                        conn.connect()
                        conn.responseCode
                        (System.currentTimeMillis() - start).toInt()
                    } finally {
                        conn.disconnect()
                    }
                }.getOrElse { e: Throwable ->
                    Log.w(TAG, "testLatency: connection check failed (via proxy)", e)
                    null
                }
            }
            Log.d(TAG, "testLatency: result ms=$ms, setting _latencyMs and latencyTesting=false")
            _latencyMs.value = ms ?: -1
            _latencyTesting.value = false
        }
    }

    /** Test real delays for ALL configs sequentially via actual paqet SOCKS. */
    fun testRealDelays() {
        if (_batchTesting.value) return
        if (paqetRunner.isRunning.value) {
            Log.w(TAG, "testRealDelays: abort, paqet is running (connected)")
            return
        }
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            Log.d(TAG, "testRealDelays: starting batch")
            _batchTesting.value = true
            _realDelayTestingIds.value = emptySet()
            _realDelayResults.value = emptyMap()
            val snapshot = configRepository.configs.first()
            if (snapshot.isEmpty()) {
                Log.d(TAG, "testRealDelays: no configs")
                _batchTesting.value = false
                return@launch
            }
            var checkUrl = settingsRepository.connectionCheckUrl.first().trim()
            if (checkUrl.isNotEmpty() && !checkUrl.startsWith("http://") && !checkUrl.startsWith("https://")) {
                checkUrl = "https://" + checkUrl
            }
            if (checkUrl.isEmpty()) checkUrl = SettingsRepository.DEFAULT_CONNECTION_CHECK_URL
            val timeoutSec = settingsRepository.connectionCheckTimeoutSeconds.first()
            val timeoutMs = (timeoutSec * 1000).coerceIn(3000, 60000)
            val allowLan = settingsRepository.socksListenLan.first()
            val logLevel = settingsRepository.logLevel.first()
            Log.d(TAG, "testRealDelays: checkUrl=$checkUrl timeout=${timeoutMs}ms count=${snapshot.size}")
            for (cfg in snapshot) {
                if (!isActive) {
                    Log.d(TAG, "testRealDelays: cancelled")
                    break
                }
                _realDelayTestingIds.update { it + cfg.id }
                val ms = testSingleRealDelay(cfg, allowLan, logLevel, checkUrl, timeoutMs, timeoutMs)
                _realDelayResults.update { it + (cfg.id to ms) }
                _realDelayTestingIds.update { it - cfg.id }
                Log.d(TAG, "testRealDelays: ${cfg.serverAddr} -> ${ms}ms")
                delay(350)
            }
            _batchTesting.value = false
            Log.d(TAG, "testRealDelays: batch finished results=${_realDelayResults.value}")
        }
    }

    fun cancelRealDelayTest() {
        batchJob?.cancel()
        batchJob = null
        _batchTesting.value = false
        _realDelayTestingIds.value = emptySet()
        try { paqetRunner.stop() } catch (_: Exception) {}
        Log.d(TAG, "testRealDelays: cancelled by user")
    }

    fun clearRealDelayResults() {
        _realDelayResults.value = emptyMap()
        _realDelayTestingIds.value = emptySet()
    }

    private suspend fun testSingleRealDelay(
        config: PaqetConfig,
        allowLan: Boolean,
        logLevel: String,
        checkUrl: String,
        connectTimeout: Int,
        readTimeout: Int
    ): Int {
        return try {
            val port = config.socksPort()
            val socksListen = if (allowLan) "0.0.0.0:$port" else "127.0.0.1:$port"
            val info = defaultNetworkInfoProvider.getDefaultNetworkInfo()
            val configWithNetwork = if (info != null) {
                config.copy(
                    networkInterface = info.interfaceName,
                    ipv4Addr = info.ipv4Addr,
                    ipv6Addr = info.ipv6Addr,
                    routerMac = info.routerMac,
                    socksListen = socksListen
                )
            } else {
                config.copy(socksListen = socksListen)
            }
            val yaml = configWithNetwork.toPaqetYaml(logLevel = logLevel)
            val summary = "real-delay test: server=${configWithNetwork.serverAddr} socks=$socksListen"
            val started = withContext(Dispatchers.IO) { paqetRunner.startWithYaml(yaml, summary) }
            if (!started) {
                Log.w(TAG, "testSingleRealDelay: paqet failed to start for ${config.serverAddr}")
                return -1
            }
            val ready = withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                var ok = false
                while (System.currentTimeMillis() - start < 4000) {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 500); ok = true; break }
                    } catch (_: Exception) { }
                    try { Thread.sleep(180) } catch (_: Exception) {}
                }
                ok
            }
            delay(250)
            if (!ready) {
                Log.w(TAG, "testSingleRealDelay: SOCKS not ready for ${config.serverAddr} port $port")
                withContext(Dispatchers.IO) { paqetRunner.stop() }
                delay(300)
                return -1
            }
            val ms = withContext(Dispatchers.IO) {
                runCatching {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    val url = URL(checkUrl)
                    val conn = url.openConnection(proxy) as? HttpURLConnection ?: return@runCatching null
                    try {
                        conn.requestMethod = "GET"
                        conn.connectTimeout = connectTimeout
                        conn.readTimeout = readTimeout
                        conn.instanceFollowRedirects = true
                        val start = System.currentTimeMillis()
                        conn.connect()
                        val code = conn.responseCode
                        Log.d(TAG, "testSingleRealDelay: ${config.serverAddr} response $code")
                        (System.currentTimeMillis() - start).toInt()
                    } finally {
                        conn.disconnect()
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "testSingleRealDelay failed for ${config.serverAddr}", e)
                    null
                }
            }
            withContext(Dispatchers.IO) { paqetRunner.stop() }
            delay(350)
            ms ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "testSingleRealDelay exception ${config.serverAddr}", e)
            try { paqetRunner.stop() } catch (_: Exception) {}
            -1
        }
    }
}
