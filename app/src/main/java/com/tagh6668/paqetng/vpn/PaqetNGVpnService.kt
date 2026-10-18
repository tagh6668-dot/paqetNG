package com.tagh6668.paqetng.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.tagh6668.paqetng.MainActivity
import com.tagh6668.paqetng.PaqetNGApplication
import com.tagh6668.paqetng.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android VPN that creates a tun interface and forwards traffic to the local SOCKS5 port via tun2socks.
 * Flow: device apps -> tun -> tun2socks (hev-socks5-tunnel) -> 127.0.0.1:socksPort -> paqet.
 */
class PaqetNGVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2Socks: Tun2SocksRunner? = null

    @get:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @get:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received ACTION_STOP - stopping paqet and tearing down VPN")
            try {
                (application as? PaqetNGApplication)?.paqetRunner?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop paqetRunner from VPN service", e)
            }
            tearDown()
            stopSelf()
            return START_NOT_STICKY
        }
        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val splitMode = intent?.getStringExtra(EXTRA_SPLIT_MODE) ?: "disabled"
        val splitAppsExtra = intent?.getStringArrayListExtra(EXTRA_SPLIT_APPS)
        val splitApps: Set<String> = splitAppsExtra?.toSet() ?: emptySet()
        val prepare = prepare(this)
        if (prepare != null) {
            Log.e(TAG, "VPN preparation failed - user must grant VPN permission")
            stopSelf()
            return START_NOT_STICKY
        }
        // Must call startForeground within ~5s to avoid ANR; do it immediately with "Connecting..."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(connecting = true),
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(connecting = true))
        }
        serviceScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (vpnInterface != null) {
                    stopTun2Socks()
                    vpnInterface?.close()
                    vpnInterface = null
                }
                establishVpn(splitMode, splitApps)
            }
            if (!success) {
                Log.e(TAG, "Failed to establish VPN - stopping service")
                stopSelf()
                return@launch
            }
            withContext(Dispatchers.IO) {
                startTun2Socks(socksPort)
            }
            // Update notification to "VPN connected"; notify Quick Settings tile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(connecting = false),
                    FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(connecting = false))
            }
            sendBroadcast(Intent(ACTION_VPN_STARTED).setPackage(packageName))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tearDown()
        super.onDestroy()
    }

    /** Tears down VPN (tun, tun2socks, foreground) so the OS drops the VPN. */
    private fun tearDown() {
        sendBroadcast(Intent(ACTION_VPN_STOPPED).setPackage(packageName))
        if (Build.VERSION.SDK_INT >= 31) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (_: Exception) {
                // Callback may not have been registered
            }
        }
        stopTun2Socks()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
    }

    private fun establishVpn(splitMode: String = "disabled", splitApps: Set<String> = emptySet()): Boolean {
        return try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addAddress("fd00::2", 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addDnsServer("2001:4860:4860::8888")
                .addDnsServer("2001:4860:4860::8844")
                .setBlocking(false)
            // Split tunneling
            val effectiveMode = when (splitMode) {
                "bypass", "only" -> if (splitApps.isEmpty()) "disabled" else splitMode
                else -> "disabled"
            }
            when (effectiveMode) {
                "bypass" -> {
                    // All via VPN except selected apps
                    val toBypass = splitApps.toMutableSet()
                    toBypass.add(packageName)
                    for (pkg in toBypass) {
                        try { builder.addDisallowedApplication(pkg) } catch (e: Exception) { Log.w(TAG, "bypass $pkg failed: ${e.message}") }
                    }
                    Log.i(TAG, "Split bypass: bypassing ${toBypass.size} apps")
                }
                "only" -> {
                    // Only selected apps via VPN (self naturally bypassed unless user selected it)
                    for (pkg in splitApps) {
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) { Log.w(TAG, "only $pkg failed: ${e.message}") }
                    }
                    Log.i(TAG, "Split only: ${splitApps.size} apps via VPN")
                }
                else -> {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not disallow self: ${e.message}")
                    }
                }
            }
            vpnInterface = builder.establish()
            if (vpnInterface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    connectivityManager.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request underlying network: ${e.message}")
                }
            }
            vpnInterface != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            false
        }
    }

    private fun startTun2Socks(socksPort: Int) {
        val pfd = vpnInterface ?: return
        val fd = pfd.fd
        if (fd < 0) {
            Log.e(TAG, "Invalid VPN fd=$fd - TUN mode will not work")
            return
        }
        Log.i(TAG, "Starting tun2socks fd=$fd socksPort=$socksPort")
        tun2Socks = Tun2SocksRunner(this, socksPort)
        if (!tun2Socks!!.start(fd)) {
            Log.e(TAG, "tun2socks failed to start - TUN traffic will not be routed to SOCKS. Ensure libhev-socks5-tunnel.so is in jniLibs.")
        } else {
            Log.i(TAG, "tun2socks started; TUN mode active")
        }
    }

    private fun stopTun2Socks() {
        tun2Socks?.stop()
        tun2Socks = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Silent migration: downgrade any previous HIGH/noisy channel to LOW silent
            nm.getNotificationChannel(CHANNEL_ID)?.let { existing ->
                val needsRecreate = existing.importance != NotificationManager.IMPORTANCE_LOW
                        || existing.sound != null || existing.shouldVibrate()
                if (needsRecreate) nm.deleteNotificationChannel(CHANNEL_ID)
            }
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    description = "VPN connection status (silent)"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(connecting: Boolean = false): Notification {
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            0,
            Intent(this, PaqetNGVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (connecting) "Connecting…" else getString(R.string.notification_vpn_connected))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_disconnect), stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val TAG = "PaqetNGVpn"
        const val ACTION_STOP = "com.tagh6668.paqetng.vpn.STOP"
        /** Broadcast sent when VPN is torn down so the app can sync connection state (e.g. stop paqet). */
        const val ACTION_VPN_STOPPED = "com.tagh6668.paqetng.vpn.STOPPED"
        /** Broadcast sent when VPN is connected so Quick Settings tile can update. */
        const val ACTION_VPN_STARTED = "com.tagh6668.paqetng.vpn.STARTED"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_SPLIT_MODE = "split_mode"
        const val EXTRA_SPLIT_APPS = "split_apps"
        private const val DEFAULT_PORT = 1284
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_channel"
    }
}
