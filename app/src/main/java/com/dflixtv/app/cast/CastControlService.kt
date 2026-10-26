package com.dflixtv.app.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dflixtv.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phone side of "Play on TV" while something plays on the TV: keeps [CastClient.nowPlaying]
 * current for the in-app mini bar, and shows a notification with ⏪ ⏯ ⏩ ⏹ that works from the
 * notification shade and lock screen. Stops itself once the TV stops playing.
 */
class CastControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires the notification within seconds of starting.
        startInForeground(CastClient.nowPlaying)
        intent?.getStringExtra(EXTRA_ACTION)?.let { action ->
            scope.launch {
                runCatching { CastClient.control(action) }
                refresh()
            }
        }
        if (poll?.isActive != true) poll = scope.launch { pollUntilStopped() }
        return START_NOT_STICKY
    }

    private suspend fun pollUntilStopped() {
        var idle = 0
        while (currentCoroutineContext().isActive) {
            val status = refresh()
            // Unreachable or nothing playing for ~10 s: the session is over.
            idle = if (status?.active == true) 0 else idle + 1
            if (idle >= 5) break
            delay(2_000)
        }
        CastClient.nowPlaying = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun refresh(): CastStatus? {
        val status = CastClient.status()
        CastClient.nowPlaying = status?.takeIf { it.active }
        status?.takeIf { it.active }?.let { notificationManager().notify(NOTIFICATION_ID, notification(it)) }
        return status
    }

    private fun startInForeground(status: CastStatus?) {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(status), type)
    }

    private fun notification(status: CastStatus?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_REMOTE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = status?.playing == true
        val progress = status?.takeIf { it.durationMs > 0 }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(androidx.media3.ui.R.drawable.exo_icon_play)
            .setContentTitle(status?.title?.ifBlank { null } ?: "Playing on TV")
            .setContentText(listOfNotNull(status?.subtitle, "on ${CastClient.active?.name ?: "TV"}").joinToString(" · "))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .apply { progress?.let { setProgress(1000, (it.positionMs * 1000 / it.durationMs).toInt(), false) } }
            .addAction(androidx.media3.ui.R.drawable.exo_icon_rewind, "Back 10 s", action("back", 1))
            .addAction(
                if (playing) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play,
                if (playing) "Pause" else "Play",
                action("toggle", 2),
            )
            .addAction(androidx.media3.ui.R.drawable.exo_icon_fastforward, "Forward 10 s", action("forward", 3))
            .addAction(androidx.media3.ui.R.drawable.exo_icon_stop, "Stop", action("stop", 4))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, CastControlService::class.java).putExtra(EXTRA_ACTION, name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playing on TV", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for what's playing on your DFLIX TV"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playing_on_tv"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_ACTION = "action"

        /** Starts (or keeps) the controls running; safe to call repeatedly. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CastControlService::class.java)) }
        }
    }
}
