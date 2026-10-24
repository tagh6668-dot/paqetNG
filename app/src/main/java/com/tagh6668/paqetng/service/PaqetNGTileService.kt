package com.tagh6668.paqetng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tagh6668.paqetng.PaqetNGApplication
import com.tagh6668.paqetng.R
import com.tagh6668.paqetng.data.ConfigRepository
import com.tagh6668.paqetng.data.DefaultNetworkInfoProvider
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.SettingsRepository
import com.tagh6668.paqetng.data.toPaqetYaml
import kotlinx.coroutines.flow.first
import com.tagh6668.paqetng.vpn.PaqetNGVpnService
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile that lets the user turn VPN on/off from the notification center,
 * similar to v2rayNG. Uses the last-selected config; if none, prompts to add one in the app.
 */
class PaqetNGTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var stateReceiver: BroadcastReceiver? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    PaqetNGVpnService.ACTION_VPN_STARTED,
                    PaqetNGVpnService.ACTION_VPN_STOPPED,
                    SocksNotificationService.ACTION_SOCKS_STARTED,
                    SocksNotificationService.ACTION_SOCKS_STOPPED -> updateTileState()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PaqetNGVpnService.ACTION_VPN_STARTED)
            addAction(PaqetNGVpnService.ACTION_VPN_STOPPED)
            addAction(SocksNotificationService.ACTION_SOCKS_STARTED)
            addAction(SocksNotificationService.ACTION_SOCKS_STOPPED)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            stateReceiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStopListening() {
        super.onStopListening()
        stateReceiver?.let {
            try {
                applicationContext.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister tile receiver", e)
            }
            stateReceiver = null
        }
    }

    override fun onClick() {
        super.onClick()
        when (qsTile.state) {
            Tile.STATE_INACTIVE -> startVpnFromTile()
            Tile.STATE_ACTIVE -> stopVpnFromTile()
            else -> updateTileState()
        }
    }

    private fun updateTileState() {
        val app = applicationContext as? PaqetNGApplication ?: return
        val running = app.paqetRunner.isRunning.value
        qsTile?.let { tile ->
            tile.icon = android.graphics.drawable.Icon.createWithResource(applicationContext, R.drawable.ic_tile_p)
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(if (running) R.string.app_tile_label_connected else R.string.app_tile_label)
            tile.updateTile()
        }
    }

    private fun setState(state: Int, label: String? = null) {
        qsTile?.let { tile ->
            tile.icon = android.graphics.drawable.Icon.createWithResource(applicationContext, R.drawable.ic_tile_p)
            tile.state = state
            tile.label = label ?: getString(if (state == Tile.STATE_ACTIVE) R.string.app_tile_label_connected else R.string.app_tile_label)
            tile.updateTile()
        }
    }

    private fun startVpnFromTile() {
        Thread {
            if (tileBusy) return@Thread
            tileBusy = true
            try {
            val app = applicationContext as? PaqetNGApplication ?: return@Thread
            val configRepo = ConfigRepository(applicationContext)
            val settingsRepo = SettingsRepository(applicationContext)
            val config: PaqetConfig? = runBlocking {
                val lastId = settingsRepo.lastSelectedConfigId.first()
                val configs = configRepo.configs.first()
                configs.find { it.id == lastId } ?: configs.firstOrNull()
            }
            if (config == null) {
                mainHandler.post { Toast.makeText(applicationContext, R.string.app_tile_first_use, Toast.LENGTH_LONG).show() }
                return@Thread
            }
            val connectionMode = runBlocking { settingsRepo.connectionMode.first() }
            val allowLan = runBlocking { settingsRepo.socksListenLan.first() }
            val logLevel = runBlocking { settingsRepo.logLevel.first() }
            val port = config.socksPort()
            val socksListen = if (allowLan) "0.0.0.0:$port" else "127.0.0.1:$port"
            val info = runBlocking { DefaultNetworkInfoProvider(applicationContext).getDefaultNetworkInfo() }
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
            val summary = "server=${configWithNetwork.serverAddr} socks=${configWithNetwork.socksListen}"
            if (connectionMode == "socks") {
                val started = app.paqetRunner.startWithYaml(configWithNetwork.toPaqetYaml(logLevel = logLevel), summary, configWithNetwork.serverPort())
                if (started) {
                    val svc = Intent(applicationContext, SocksNotificationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(svc)
                    } else {
                        applicationContext.startService(svc)
                    }
                }
                mainHandler.post {
                    if (started) setState(Tile.STATE_ACTIVE, getString(R.string.app_tile_label_connected))
                    else Toast.makeText(applicationContext, R.string.app_tile_start_failed, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            val prepare = VpnService.prepare(applicationContext)
            if (prepare != null) {
                mainHandler.post { Toast.makeText(applicationContext, R.string.app_tile_vpn_permission, Toast.LENGTH_LONG).show() }
                return@Thread
            }
            val started = app.paqetRunner.startWithYaml(configWithNetwork.toPaqetYaml(logLevel = logLevel), summary, configWithNetwork.serverPort())
            if (!started) {
                mainHandler.post { Toast.makeText(applicationContext, R.string.app_tile_start_failed, Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            mainHandler.postDelayed({
                val splitMode = runBlocking { settingsRepo.splitMode.first() }
                val splitApps = runBlocking { settingsRepo.splitApps.first() }
                val intent = Intent(applicationContext, PaqetNGVpnService::class.java)
                    .putExtra(PaqetNGVpnService.EXTRA_SOCKS_PORT, config.socksPort())
                    .putExtra(PaqetNGVpnService.EXTRA_SPLIT_MODE, splitMode)
                    .putStringArrayListExtra(PaqetNGVpnService.EXTRA_SPLIT_APPS, ArrayList(splitApps))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }, 800)
            } finally { tileBusy = false }
        }.start()
    }

    private fun stopVpnFromTile() {
        if (tileBusy) return
        val app = applicationContext as? PaqetNGApplication ?: return
        app.paqetRunner.stop()
        applicationContext.startService(
            Intent(applicationContext, PaqetNGVpnService::class.java).setAction(PaqetNGVpnService.ACTION_STOP)
        )
        applicationContext.stopService(Intent(applicationContext, PaqetNGVpnService::class.java))
        applicationContext.startService(
            Intent(applicationContext, SocksNotificationService::class.java).setAction(SocksNotificationService.ACTION_STOP)
        )
        applicationContext.stopService(Intent(applicationContext, SocksNotificationService::class.java))
        setState(Tile.STATE_INACTIVE)
    }

    companion object {
        private const val TAG = "PaqetNGTileService"
        @Volatile private var tileBusy = false
    }
}
