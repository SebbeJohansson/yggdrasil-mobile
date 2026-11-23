package io.github.sebbejohansson.yggdrasil.linux

import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.MainActivity
import io.github.sebbejohansson.yggdrasil.R
import io.github.sebbejohansson.yggdrasil.YggApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Whether the bridge should always run in Ygg's own Linux. */
class LinuxBridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("linux_bridge", Context.MODE_PRIVATE)
    var alwaysOn: Boolean
        get() = prefs.getBoolean("always_on", false)
        set(v) = prefs.edit { putBoolean("always_on", v) }
}

/**
 * Ygg's own Linux, always on: a foreground service (with a quiet
 * notification) that runs the bridge in proot and starts it again when it
 * stops. Started when you turn it on, and after the phone restarts. Android
 * lets it run as long as Ygg's battery use is Unrestricted.
 */
class LinuxBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var keeper: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Claude bridge", NotificationManager.IMPORTANCE_MIN))
        // The specialUse type is Android 14+; before it, a plain foreground service.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION, notification("Starting…"), type)
        running.value = true
        scope.launch { keepRunning() }
        scope.launch { keepHerdr() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LinuxBridgeSettings(this).alwaysOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private suspend fun keepRunning() {
        val linux = (application as YggApp).container.ownLinux
        var wait = 5_000L
        while (scope.isActive) {
            if (!linux.installed) {
                update("Set up Ygg's Linux first (Settings → Claude bridge).")
                delay(60_000)
                continue
            }
            val started = System.currentTimeMillis()
            update("Running on 127.0.0.1:8765")
            runCatching { linux.refreshBridge() }
            val p = runCatching { linux.start(GuestSetup.BRIDGE, GuestSetup.BRIDGE_ENV) }.getOrNull()
            process = p
            if (p != null) {
                val tail = ArrayDeque<String>()
                p.inputStream.bufferedReader().forEachLine { tail.addLast(it); if (tail.size > 5) tail.removeFirst() }
                val code = p.waitFor()
                lastExit.value = "ended with $code: " + tail.joinToString(" / ").take(300)
            }
            // A bridge that ran a while gets a quick restart; one that keeps failing, slower ones.
            wait = if (System.currentTimeMillis() - started > 60_000) 5_000L else (wait * 2).coerceAtMost(300_000L)
            update("Stopped; starting again in ${wait / 1000} s")
            delay(wait)
        }
    }

    /**
     * The herdr keeper, in a proot of its own beside the bridge's: the bridge's
     * proot ends everything it started when it exits (--kill-on-exit), so herdr
     * (and the Claude sessions in it) lives here instead and goes on while the
     * bridge restarts. Started again if it stops, after the bridge has its copy
     * of the script in place.
     */
    private suspend fun keepHerdr() {
        val linux = (application as YggApp).container.ownLinux
        delay(3_000)
        while (scope.isActive) {
            if (!linux.installed) {
                delay(60_000)
                continue
            }
            val p = runCatching { linux.start(GuestSetup.KEEPER, GuestSetup.BRIDGE_ENV) }.getOrNull()
            keeper = p
            if (p != null) {
                p.inputStream.bufferedReader().forEachLine { }
                p.waitFor()
            }
            delay(10_000)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinuxBridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Claude bridge")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun update(text: String) {
        status.value = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }

    override fun onDestroy() {
        running.value = false
        status.value = "Off"
        process?.destroy()
        keeper?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "linux_bridge"
        private const val NOTIFICATION = 7401
        private const val ACTION_STOP = "io.github.sebbejohansson.yggdrasil.LINUX_BRIDGE_STOP"

        val running = MutableStateFlow(false)
        val status: MutableStateFlow<String> = MutableStateFlow("Off")
        val lastExit: MutableStateFlow<String?> = MutableStateFlow(null)
        val runningState: StateFlow<Boolean> get() = running

        fun start(context: Context) {
            LinuxBridgeSettings(context).alwaysOn = true
            ContextCompat.startForegroundService(context, Intent(context, LinuxBridgeService::class.java))
        }

        fun stop(context: Context) {
            LinuxBridgeSettings(context).alwaysOn = false
            context.stopService(Intent(context, LinuxBridgeService::class.java))
        }

        /** After a restart or an update: on again if it was on. */
        fun resume(context: Context) {
            if (LinuxBridgeSettings(context).alwaysOn) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, LinuxBridgeService::class.java)) }
            }
        }
    }
}
