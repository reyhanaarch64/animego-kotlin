package app.animego.inc.core.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import app.animego.inc.AppGraph
import app.animego.inc.MainActivity
import app.animego.inc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "animego_playback"
        private const val NOTIFICATION_ID = 5101
        const val ACTION_TOGGLE = "app.animego.inc.PLAYBACK_TOGGLE"
        const val ACTION_STOP = "app.animego.inc.PLAYBACK_STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressTask = object : Runnable {
        override fun run() {
            saveProgress()
            if (PlaybackEngine.currentSource.isNotBlank()) handler.postDelayed(this, 5_000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification()
            if (!isPlaying) saveProgress()
        }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateNotification()
            saveProgress()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlaybackEngine.player(this).addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val player = PlaybackEngine.player(this)
                if (player.isPlaying) player.pause() else player.play()
            }
            ACTION_STOP -> {
                saveProgress()
                PlaybackEngine.clear()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        updateNotification()
        handler.removeCallbacks(progressTask)
        handler.postDelayed(progressTask, 5_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        saveProgress()
        runCatching { PlaybackEngine.player(this).removeListener(listener) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveProgress() {
        val slug = PlaybackEngine.currentSlug
        val source = PlaybackEngine.currentSource
        if (slug.isBlank() || source.isBlank() || PlaybackEngine.currentOffline) return
        val player = runCatching { PlaybackEngine.player(this) }.getOrNull() ?: return
        val title = PlaybackEngine.currentTitle
        val episode = PlaybackEngine.currentEpisode
        val cover = PlaybackEngine.currentCover
        val position = player.currentPosition.coerceAtLeast(0L).toDouble() / 1000.0
        val duration = player.duration.takeIf { it > 0L }?.toDouble()?.div(1000.0) ?: 0.0
        val database = AppGraph.from(applicationContext).database
        ioScope.launch { runCatching { database.updateProgress(slug, title, episode, cover, source, position, duration) } }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Pemutaran AnimeGo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kontrol playback AnimeGo"
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    private fun openPlayerIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("open_route", "player")
        putExtra("slug", PlaybackEngine.currentSlug)
        putExtra("source", PlaybackEngine.currentSource)
        putExtra("title", PlaybackEngine.currentTitle)
        putExtra("episode", PlaybackEngine.currentEpisode)
        putExtra("cover", PlaybackEngine.currentCover)
        putExtra("position", PlaybackEngine.player(this@PlaybackService).currentPosition.toDouble() / 1000.0)
        putExtra("offline", PlaybackEngine.currentOffline)
    }

    private fun buildNotification(): android.app.Notification {
        val player = PlaybackEngine.player(this)
        val open = PendingIntent.getActivity(this, 5101, openPlayerIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 5102, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 5103, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = PlaybackEngine.currentTitle.ifBlank { "AnimeGo" }
        val line = PlaybackEngine.currentEpisode.ifBlank { "Sedang diputar" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_animego)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .addAction(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (player.isPlaying) "Jeda" else "Putar", toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", stop)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
