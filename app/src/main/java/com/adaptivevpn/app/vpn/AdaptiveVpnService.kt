package com.adaptivevpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.adaptivevpn.app.MainActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Android VPN lifecycle host. It never establishes a TUN unless the engine is ready. */
class AdaptiveVpnService : VpnService() {
    private val engine: SingBoxEngine by lazy { NativeSingBoxEngine() }
    private var activeConfigPath: String? = null
    private var probeGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adaptive-vpn-traffic-probe").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngineAndService()
                return START_NOT_STICKY
            }
            ACTION_START -> startSafely(intent.getStringExtra(EXTRA_CONFIG_PATH))
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startSafely(configPath: String?) {
        // Promote immediately to foreground before doing any work.
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی موتور VPN"))
        try {
            if (prepare(this) != null) error("مجوز VPN صادر نشده است.")
            val file = configPath?.let(::File)
            require(file != null && file.isFile && file.length() in 1L..1_048_576L) { "پروفایل در دسترس نیست." }
            val canonicalPath = file.canonicalPath
            // Android may redeliver/start the service more than once. Avoid tearing down a
            // healthy engine when the same profile is requested again.
            if (activeConfigPath == canonicalPath) {
                // Do not erase a completed probe result if Android redelivers the same start intent.
                val current = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATUS, null)
                if (current == "TRAFFIC_PROBE_PASSED") {
                    updateNotification("آزمون‌های HTTPS موفق شدند؛ نشت ترافیک بررسی نشده")
                } else {
                    updateNotification("موتور فعال است؛ آزمون ترافیک در انتظار نتیجه است")
                }
                return
            }
            if (activeConfigPath != null) {
                runCatching { engine.stop() }
                activeConfigPath = null
            }
            engine.start(this, canonicalPath)
            activeConfigPath = canonicalPath
            setStatus("STARTED_UNVERIFIED")
            updateNotification("هسته شروع شد؛ آزمون ترافیک HTTPS در حال اجراست")
            scheduleTrafficProbe(canonicalPath)
        } catch (e: Exception) {
            probeGeneration++
            runCatching { engine.stop() }
            activeConfigPath = null
            setStatus("ERROR: ${e.message ?: "خطای ناشناخته"}")
            removeForegroundAndStop()
        }
    }

    private fun stopEngineAndService() {
        probeGeneration++ // invalidate any in-flight result before updating DISCONNECTED
        runCatching { engine.stop() }
        activeConfigPath = null
        setStatus("DISCONNECTED")
        removeForegroundAndStop()
    }

    private fun removeForegroundAndStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AdaptiveVPN", NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("AdaptiveVPN")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun scheduleTrafficProbe(configPath: String) {
        val generation = ++probeGeneration
        probeExecutor.execute {
            val result = TrafficProbe.run()
            mainHandler.post {
                // A slow result from an old profile must never overwrite stop/revoke/new-profile state.
                if (generation != probeGeneration || activeConfigPath != configPath) return@post
                if (result.passed) {
                    setStatus("TRAFFIC_PROBE_PASSED")
                    updateNotification("آزمون‌های HTTPS موفق شدند؛ نشت ترافیک بررسی نشده")
                } else {
                    setStatus("PROBE_FAILED: ${result.summary}")
                    updateNotification("آزمون ترافیک ناموفق بود؛ اتصال تأیید نشده است")
                }
            }
        }
    }

    private fun setStatus(value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
    }

    override fun onRevoke() {
        stopEngineAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        probeGeneration++
        runCatching { engine.stop() }
        activeConfigPath = null
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_START = "com.adaptivevpn.app.vpn.START"
        const val ACTION_STOP = "com.adaptivevpn.app.vpn.STOP"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val PREFS = "vpn_state"
        const val KEY_STATUS = "status"
        const val CHANNEL_ID = "adaptive_vpn_status"
        const val NOTIFICATION_ID = 4102
    }
}
