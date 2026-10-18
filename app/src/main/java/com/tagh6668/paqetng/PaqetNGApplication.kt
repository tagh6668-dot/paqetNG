package com.tagh6668.paqetng

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.tagh6668.paqetng.data.AppLogBuffer
import com.tagh6668.paqetng.data.SettingsRepository
import com.tagh6668.paqetng.data.TcpdumpBuffer
import com.tagh6668.paqetng.paqet.PaqetRunner
import com.tagh6668.paqetng.paqet.TcpdumpRunner
import com.tagh6668.paqetng.service.SocksNotificationService
import com.tagh6668.paqetng.vpn.PaqetNGVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application singleton. Holds [logBuffer] and [paqetRunner] so the VPN service can
 * notify the app when it stops (e.g. user disconnects from notification/system VPN settings),
 * and the app can sync state by stopping paqet. When paqet crashes, restarts it if auto-reconnect is on.
 */
class PaqetNGApplication : Application() {

    val logBuffer: AppLogBuffer = AppLogBuffer()
    val tcpdumpBuffer: TcpdumpBuffer = TcpdumpBuffer()
    val paqetRunner: PaqetRunner by lazy { PaqetRunner(this, logBuffer) }
    val tcpdumpRunner: TcpdumpRunner by lazy { TcpdumpRunner(this, tcpdumpBuffer) }
    private val settingsRepository by lazy { SettingsRepository(this) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val vpnStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PaqetNGVpnService.ACTION_VPN_STOPPED,
                SocksNotificationService.ACTION_SOCKS_STOPPED -> paqetRunner.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(PaqetNGVpnService.ACTION_VPN_STOPPED)
            addAction(SocksNotificationService.ACTION_SOCKS_STOPPED)
        }
        ContextCompat.registerReceiver(
            this,
            vpnStoppedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        paqetRunner.onCrashed = { configYaml, sessionSummary ->
            applicationScope.launch {
                val doRestart = settingsRepository.autoReconnect.first()
                if (doRestart) {
                    logBuffer.append(AppLogBuffer.TAG_SESSION, "paqet crashed; restarting in 1s (auto-reconnect enabled)")
                    delay(1000)
                    paqetRunner.startWithYaml(configYaml, sessionSummary)
                }
            }
        }
    }
}
