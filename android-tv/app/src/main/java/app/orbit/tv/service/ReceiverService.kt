package app.orbit.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.orbit.tv.OrbitApp
import app.orbit.tv.R
import app.orbit.tv.ui.MainActivity

/**
 * Keeps the process (and the HTTP server) alive while receiving is enabled, including when the user presses Home
 * mid-transfer. Holds a Wi-Fi lock so the TV's Wi-Fi doesn't power-save during large transfers.
 */
class ReceiverService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_receiver), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_orbit)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_ready))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)

        (application as OrbitApp).receiver.start()
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "orbit:receiver")?.apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbit:receiver")?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_STICKY
    }

    /** Android 15 limits dataSync services to 6 h per day. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        wifiLock?.release(); wifiLock = null
        wakeLock?.release(); wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val ID = 1

        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java)) }
        fun stop(context: Context) = context.stopService(Intent(context, ReceiverService::class.java))
    }
}
