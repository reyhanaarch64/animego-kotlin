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
        const val ACTION_SEEK_BACK = "app.animego.inc.PLAYBACK_SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "app.animego.inc.PLAYBACK_SEEK_FORWARD"
        const val ACTION_PREVIOUS_EPISODE = "app.animego.inc.PLAYBACK_PREVIOUS_EPISODE"
        const val ACTION_NEXT_EPISODE = "app.animego.inc.PLAYBACK_NEXT_EPISODE"

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressTask = object : Runnable {
        override fun run() {
            saveProgress()
            if (PlaybackEngine.currentSource.isNotBlank()) {
                updateNotification()
                handler.postDelayed(this, 5_000L)
            }
        }
    }

    // Give the player screen's auto-next listener time to install the next item.
    // If the item remains ended, playback is genuinely finished and both media
    // foreground services must release their notifications/windows.
    private val endedCleanup = Runnable {
        val player = runCatching { PlaybackEngine.player(this@PlaybackService) }.getOrNull() ?: return@Runnable
        if (player.playbackState == Player.STATE_ENDED) {
            saveProgress()
            stopService(Intent(this@PlaybackService, FloatingPlayerService::class.java))
            stopForegroundCompat()
            stopSelf()
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification()
            if (!isPlaying) saveProgress()
            if (isPlaying) handler.removeCallbacks(endedCleanup)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            handler.removeCallbacks(endedCleanup)
            if (playbackState == Player.STATE_ENDED) {
                handler.postDelayed(endedCleanup, 1_200L)
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            handler.removeCallbacks(endedCleanup)
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
                if (PlaybackEngine.isPlaybackRequested(player)) {
                    // A notification pause is an external pause, not a popup pause.
                    player.pause()
                    player.playWhenReady = false
                    saveProgress()
                    stopService(Intent(this, FloatingPlayerService::class.java))
                    stopForegroundCompat()
                    stopSelf(startId)
                    return START_NOT_STICKY
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }
            ACTION_SEEK_BACK -> PlaybackEngine.player(this).seekBack()
            ACTION_SEEK_FORWARD -> PlaybackEngine.player(this).seekForward()
            ACTION_PREVIOUS_EPISODE -> PlaybackEngine.skipQueue(this, -1)
            ACTION_NEXT_EPISODE -> PlaybackEngine.skipQueue(this, 1)
            ACTION_STOP -> {
                saveProgress()
                stopService(Intent(this, FloatingPlayerService::class.java))
                PlaybackEngine.clear()
                stopForegroundCompat()
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
        handler.removeCallbacks(endedCleanup)
        saveProgress()
        runCatching { PlaybackEngine.player(this).removeListener(listener) }
        runCatching { stopForegroundCompat() }
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
        val seekBack = PendingIntent.getService(this, 5104, Intent(this, PlaybackService::class.java).setAction(ACTION_SEEK_BACK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val seekForward = PendingIntent.getService(this, 5105, Intent(this, PlaybackService::class.java).setAction(ACTION_SEEK_FORWARD), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val previous = PendingIntent.getService(this, 5106, Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS_EPISODE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = PendingIntent.getService(this, 5107, Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT_EPISODE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = PlaybackEngine.currentTitle.ifBlank { "AnimeGo" }
        val line = PlaybackEngine.currentEpisode.ifBlank { "Sedang diputar" }
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val progress = if (duration > 0L) {
            ((player.currentPosition.coerceAtLeast(0L).toDouble() / duration) * 1000.0).toInt().coerceIn(0, 1000)
        } else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_animego)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, progress, duration <= 0L)
            .addAction(android.R.drawable.ic_media_previous, "Episode sebelumnya", previous)
            .addAction(android.R.drawable.ic_media_rew, "Mundur 10 detik", seekBack)
            .addAction(if (PlaybackEngine.isPlaybackRequested(player)) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (PlaybackEngine.isPlaybackRequested(player)) "Jeda" else "Putar", toggle)
            .addAction(android.R.drawable.ic_media_ff, "Maju 10 detik", seekForward)
            .addAction(android.R.drawable.ic_media_next, "Episode berikutnya", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", stop)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
