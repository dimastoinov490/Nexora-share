package com.nexora.share.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nexora.share.MainActivity
import com.nexora.share.NexoraApplication
import com.nexora.share.R
import com.nexora.share.server.NexoraHttpServer
import com.nexora.share.utils.NetworkUtils

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.nexora.share.action.START"
        const val ACTION_STOP = "com.nexora.share.action.STOP"
        const val NOTIFICATION_ID = 1001
        const val DEFAULT_PORT = 8080

        fun start(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: NexoraHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            ACTION_START, null -> startServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server?.isAlive == true) return

        val ip = NetworkUtils.getLocalIpAddress(this)
        val port = DEFAULT_PORT

        try {
            server = NexoraHttpServer(applicationContext, port)
            server?.start()

            acquireLocks()
            registerNetworkMonitor()

            val notification = buildNotification(ip, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val isWifi = NetworkUtils.isWifiOrHotspotActive(this)
            ServerRepository.setRunning(true, ip, port)
            ServerRepository.setNetworkStatus(isWifi, ip)
        } catch (e: Exception) {
            e.printStackTrace()
            ServerRepository.setRunning(false, ip, port, error = e.message)
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        releaseLocks()
        unregisterNetworkMonitor()

        val isWifi = NetworkUtils.isWifiOrHotspotActive(this)
        val ip = NetworkUtils.getLocalIpAddress(this)
        ServerRepository.setRunning(false, ip, DEFAULT_PORT)
        ServerRepository.setNetworkStatus(isWifi, ip)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val serverUrl = "http://$ip:$port"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NexoraApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content, serverUrl))
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                pendingStop
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexoraShare:WakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NexoraShare:WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerNetworkMonitor() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder().build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateNetworkInfo()
                }

                override fun onLost(network: Network) {
                    updateNetworkInfo()
                }
            }
            networkCallback?.let { connectivityManager?.registerNetworkCallback(request, it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress(this)
        val isWifi = NetworkUtils.isWifiOrHotspotActive(this)
        ServerRepository.setNetworkStatus(isWifi, ip)
        if (server?.isAlive == true) {
            val notification = buildNotification(ip, DEFAULT_PORT)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun unregisterNetworkMonitor() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
