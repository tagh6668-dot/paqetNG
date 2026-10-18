package com.tagh6668.paqetng

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tagh6668.paqetng.data.AppLogBuffer
import com.tagh6668.paqetng.data.ConfigRepository
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.SettingsRepository
import com.tagh6668.paqetng.data.ThemePref
import com.tagh6668.paqetng.ui.HomeScreen
import com.tagh6668.paqetng.ui.HomeViewModel
import com.tagh6668.paqetng.ui.LogViewerScreen
import com.tagh6668.paqetng.ui.PacketDetailActivity
import com.tagh6668.paqetng.ui.PacketDumpScreen
import com.tagh6668.paqetng.ui.SettingsScreen
import com.tagh6668.paqetng.ui.SplitAppsScreen
import com.tagh6668.paqetng.ui.theme.PaqetNGTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.core.content.ContextCompat
import com.tagh6668.paqetng.vpn.PaqetNGVpnService

class MainActivity : ComponentActivity() {

    private val app by lazy { application as PaqetNGApplication }
    private val logBuffer get() = app.logBuffer
    private val paqetRunner get() = app.paqetRunner
    private val configRepository by lazy { ConfigRepository(applicationContext) }
    private val defaultNetworkInfoProvider by lazy { com.tagh6668.paqetng.data.DefaultNetworkInfoProvider(applicationContext) }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private val viewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    configRepository,
                    paqetRunner,
                    defaultNetworkInfoProvider,
                    settingsRepository,
                    applicationContext
                ) as T
            }
        }
    }

    private var pendingConnectConfig: PaqetConfig? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingVpnStartRunnable: Runnable? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val config = pendingConnectConfig
        pendingConnectConfig = null
        if (result.resultCode == RESULT_OK && config != null) {
            doConnect(config)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.util.Log.w("MainActivity", "POST_NOTIFICATIONS denied - VPN notification will be hidden on Android 13+")
            android.widget.Toast.makeText(this, "Notification permission denied - disconnect button will not appear in shade", android.widget.Toast.LENGTH_LONG).show()
        }
        // If user granted, next connect will show notification normally
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @Suppress("unused")
    private fun ensureNotificationPermission(onReady: () -> Unit) {
        if (hasNotificationPermission()) { onReady(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            onReady()
        } else onReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Proactively request POST_NOTIFICATIONS on first launch (Android 13+) so FGS notification is visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            val theme by settingsRepository.theme.collectAsState(initial = ThemePref.SYSTEM)
            val darkTheme = when (theme) {
                ThemePref.LIGHT -> false
                ThemePref.DARK -> true
                ThemePref.SYSTEM -> isSystemInDarkTheme()
            }
            var showSettings by remember { mutableStateOf(false) }
            var showLogViewer by remember { mutableStateOf(false) }
            var showPacketDump by remember { mutableStateOf(false) }
            var showSplitApps by remember { mutableStateOf(false) }
            
            // Helper functions to update screen state
            val dismissPacketDump = { showPacketDump = false }
            val dismissLogViewer = { showLogViewer = false }
            val dismissSettings = { showSettings = false }
            val dismissSplitApps = { showSplitApps = false }
            val showSplitAppsScreen = { showSplitApps = true }
            val showPacketDumpScreen = { showPacketDump = true }
            val showLogViewerScreen = { showLogViewer = true }
            val showSettingsScreen = { showSettings = true }
            val isRunning by viewModel.isRunning.collectAsState(initial = false)
            val connectionMode by settingsRepository.connectionMode.collectAsState(initial = SettingsRepository.DEFAULT_CONNECTION_MODE)
            val configs by viewModel.configs.collectAsState(initial = emptyList())
            val selectedConfigId by viewModel.selectedConfigId.collectAsState()
            val connectedConfigId by viewModel.connectedConfigId.collectAsState()
            val latencyMs by viewModel.latencyMs.collectAsState()
            val latencyTesting by viewModel.latencyTesting.collectAsState(initial = false)
            val selectedConfig: PaqetConfig? = remember(configs, selectedConfigId) {
                configs.find { it.id == selectedConfigId } ?: configs.firstOrNull()
            }
            // When user changes the selected profile while connected, restart paqet (and VPN) with the new profile
            LaunchedEffect(selectedConfigId, isRunning, connectedConfigId) {
                val newConfig = selectedConfigId?.let { id -> configs.find { it.id == id } }
                if (isRunning && newConfig != null && selectedConfigId != connectedConfigId) {
                    handleDisconnect()
                    handleConnect(newConfig, connectionMode)
                }
            }
            PaqetNGTheme(darkTheme = darkTheme) {
                when {
                    showPacketDump -> {
                        BackHandler(onBack = dismissPacketDump)
                        PacketDumpScreen(
                            tcpdumpBuffer = app.tcpdumpBuffer,
                            tcpdumpRunner = app.tcpdumpRunner,
                            isPaqetRunning = isRunning,
                            connectionMode = connectionMode,
                            connectedConfig = selectedConfig,
                            latencyMs = latencyMs,
                            latencyTesting = latencyTesting,
                            onTestConnection = { viewModel.testLatency(selectedConfig) },
                            onBack = dismissPacketDump,
                            onOpenPacketDetail = { packetText ->
                                startActivity(
                                    Intent(this, PacketDetailActivity::class.java)
                                        .putExtra(PacketDetailActivity.EXTRA_PACKET_TEXT, packetText)
                                )
                            }
                        )
                    }
                    showLogViewer -> {
                        BackHandler(onBack = dismissLogViewer)
                        LogViewerScreen(
                            logBuffer = logBuffer,
                            onBack = dismissLogViewer,
                            isVpnConnected = isRunning,
                            onPacketDumpClick = showPacketDumpScreen
                        )
                    }
                    showSplitApps -> {
                        BackHandler(onBack = dismissSplitApps)
                        SplitAppsScreen(
                            settingsRepository = settingsRepository,
                            onBack = dismissSplitApps
                        )
                    }
                    showSettings -> {
                        BackHandler(onBack = dismissSettings)
                        SettingsScreen(
                            settingsRepository = settingsRepository,
                            onBack = dismissSettings,
                            onSplitAppsClick = {
                                showSplitAppsScreen()
                            }
                        )
                    }
                    else -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onSettingsClick = showSettingsScreen,
                            onLogsClick = showLogViewerScreen,
                            onConnect = { handleConnect(it, connectionMode) },
                            onDisconnect = { handleDisconnect() }
                        )
                    }
                }
            }
        }
    }

    private fun handleConnect(config: PaqetConfig?, connectionMode: String) {
        if (config == null) return
        logBuffer.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (connectionMode == "socks") {
            logBuffer.append(AppLogBuffer.TAG_VPN, "Starting paqet (SOCKS-only mode); showing disconnect notification.")
            viewModel.connect(config) {
                // Show ongoing notification so user can disconnect from shade
                val svc = Intent(this, com.tagh6668.paqetng.service.SocksNotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
            }
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingConnectConfig = config
            vpnPermissionLauncher.launch(prepare)
        } else {
            doConnect(config)
        }
    }

    private fun doConnect(config: PaqetConfig) {
        cancelPendingVpnStart()
        logBuffer.append(AppLogBuffer.TAG_VPN, "Starting paqet; VPN will start once SOCKS is listening on port ${config.socksPort()}")
        viewModel.connect(config) {
            logBuffer.append(AppLogBuffer.TAG_VPN, "Paqet started; waiting for SOCKS to bind then starting VPN…")
            // Give paqet time to bind to 127.0.0.1:socksPort before tun2socks tries to connect (same idea as v2rayNG).
            val splitMode = runBlocking { settingsRepository.splitMode.first() }
            val splitApps = runBlocking { settingsRepository.splitApps.first() }
            val runnable = Runnable {
                logBuffer.append(AppLogBuffer.TAG_VPN, "VPN starting port=${config.socksPort()} config=${config.name.ifEmpty { config.serverAddr }} mode=$splitMode apps=${splitApps.size}")
                val intent = Intent(this@MainActivity, PaqetNGVpnService::class.java)
                    .putExtra(PaqetNGVpnService.EXTRA_SOCKS_PORT, config.socksPort())
                    .putExtra(PaqetNGVpnService.EXTRA_SPLIT_MODE, splitMode)
                    .putStringArrayListExtra(PaqetNGVpnService.EXTRA_SPLIT_APPS, ArrayList(splitApps))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                // Clear the reference after execution to allow cancellation if needed
                pendingVpnStartRunnable = null
            }
            pendingVpnStartRunnable = runnable
            mainHandler.postDelayed(runnable, 800)
        }
    }

    private fun cancelPendingVpnStart() {
        pendingVpnStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVpnStartRunnable = null
    }

    private fun handleDisconnect() {
        cancelPendingVpnStart()
        viewModel.disconnect()
        // Tear down whichever foreground service is active (VPN or SOCKS)
        startService(
            Intent(this, PaqetNGVpnService::class.java).setAction(PaqetNGVpnService.ACTION_STOP)
        )
        stopService(Intent(this, PaqetNGVpnService::class.java))
        startService(
            Intent(this, com.tagh6668.paqetng.service.SocksNotificationService::class.java)
                .setAction(com.tagh6668.paqetng.service.SocksNotificationService.ACTION_STOP)
        )
        stopService(Intent(this, com.tagh6668.paqetng.service.SocksNotificationService::class.java))
    }
}
