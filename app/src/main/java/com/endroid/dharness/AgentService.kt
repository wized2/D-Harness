package com.endroid.dharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps the process (and WebView JS) alive while a multi-step tool chain runs
 * in the background. Notification shows the latest tool description.
 */
class AgentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopIdle = Runnable {
        if (lastBeginAt + IDLE_STOP_MS <= System.currentTimeMillis()) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dharness:agent").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // max 1h; refreshed on activity
        }
        startAsForeground("Agent ready", "Tool chain can continue in background")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STATUS -> {
                val tool = intent.getStringExtra(EXTRA_TOOL).orEmpty()
                val desc = intent.getStringExtra(EXTRA_DESC).orEmpty()
                lastBeginAt = System.currentTimeMillis()
                handler.removeCallbacks(stopIdle)
                val title = if (desc.isNotBlank()) desc else if (tool.isNotBlank()) "Running $tool" else "Working…"
                val text = if (tool.isNotBlank() && desc.isNotBlank()) tool else "D-Harness agent"
                startAsForeground(title, text)
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(60 * 60 * 1000L)
                }
            }
            ACTION_IDLE -> {
                lastBeginAt = System.currentTimeMillis()
                startAsForeground("Agent idle", "Waiting for next tool call…")
                handler.removeCallbacks(stopIdle)
                handler.postDelayed(stopIdle, IDLE_STOP_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    private fun startAsForeground(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val chId = "dharness_agent"
        nm.createNotificationChannel(
            NotificationChannel(chId, "Agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps tool calling alive in background"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val NOTIF_ID = 8801
        const val ACTION_STOP = "com.endroid.dharness.agent.STOP"
        const val ACTION_STATUS = "com.endroid.dharness.agent.STATUS"
        const val ACTION_IDLE = "com.endroid.dharness.agent.IDLE"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_DESC = "desc"
        private const val IDLE_STOP_MS = 45_000L

        @Volatile var running: Boolean = false
            private set

        @Volatile private var lastBeginAt: Long = 0L

        fun ensure(ctx: Context) {
            if (running) return
            val i = Intent(ctx, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun status(ctx: Context, tool: String, description: String) {
            ensure(ctx)
            ctx.startService(
                Intent(ctx, AgentService::class.java)
                    .setAction(ACTION_STATUS)
                    .putExtra(EXTRA_TOOL, tool)
                    .putExtra(EXTRA_DESC, description)
            )
        }

        fun idle(ctx: Context) {
            if (!running) return
            ctx.startService(Intent(ctx, AgentService::class.java).setAction(ACTION_IDLE))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
