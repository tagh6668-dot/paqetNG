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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.content.Context
import android.util.Log
import com.tagh6668.paqetng.paqet.RealDelayTester
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class HomeViewModel(
    private val configRepository: ConfigRepository,
    private val paqetRunner: PaqetRunner,
    private val defaultNetworkInfoProvider: DefaultNetworkInfoProvider,
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
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

    // --- Test Real Delays (batch) — isolated parallel tester (does not touch main PaqetRunner) ---
    private val realDelayTester = RealDelayTester(appContext, defaultNetworkInfoProvider)
    val realDelayResults: StateFlow<Map<String, Int>> = realDelayTester.results
    val realDelayTestingIds: StateFlow<Set<String>> = realDelayTester.testingIds
    val batchTesting: StateFlow<Boolean> = realDelayTester.isTesting

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
            val yaml = try { configWithNetwork.toPaqetYaml(logLevel = logLevel) } catch (e: IllegalStateException) { android.util.Log.e(TAG, "YAML error", e); return@launch }
            val started = paqetRunner.startWithYaml(yaml, summary)
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

    /** Test real delays for ALL configs in parallel (10 at a time) via isolated paqet instances. Does not touch main VPN. Timeout from Settings. */
    fun testRealDelays() {
        if (batchTesting.value) return
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            Log.d(TAG, "testRealDelays: starting batch (parallel x${RealDelayTester.CONCURRENCY})")
            val snapshot = configRepository.configs.first()
            if (snapshot.isEmpty()) {
                Log.d(TAG, "testRealDelays: no configs")
                return@launch
            }
            val checkUrl = settingsRepository.connectionCheckUrl.first()
            val timeoutSec = settingsRepository.connectionCheckTimeoutSeconds.first()
            val timeoutMs = (timeoutSec * 1000).coerceIn(3000, 60000)
            val allowLan = settingsRepository.socksListenLan.first()
            val logLevel = settingsRepository.logLevel.first()
            Log.d(TAG, "testRealDelays: checkUrl=$checkUrl timeout=${timeoutMs}ms count=${snapshot.size}")
            realDelayTester.testAll(snapshot, checkUrl, timeoutMs, logLevel, allowLan)
            Log.d(TAG, "testRealDelays: batch finished results=${realDelayResults.value}")
        }
    }

    fun cancelRealDelayTest() {
        batchJob?.cancel()
        batchJob = null
        realDelayTester.cancel()
        Log.d(TAG, "testRealDelays: cancelled by user")
    }

    fun clearRealDelayResults() {
        batchJob?.cancel()
        batchJob = null
        realDelayTester.clear()
    }
}
