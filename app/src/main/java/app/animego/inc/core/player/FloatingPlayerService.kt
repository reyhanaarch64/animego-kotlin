package app.animego.inc.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.ContextThemeWrapper
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.animego.inc.MainActivity
import app.animego.inc.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Native floating video window. This intentionally uses Android Views instead of
 * ComposeView: a Service does not own the normal Activity view-tree lifecycle,
 * which made ComposeView composition fragile when launched as an overlay.
 */
class FloatingPlayerService : Service() {
    companion object {
        const val ACTION_SHOW = "app.animego.inc.FLOATING_SHOW"
        private const val CHANNEL_ID = "animego_floating"
        private const val NOTIFICATION_ID = 5102
        private const val ACTION_CLOSE = "app.animego.inc.FLOATING_CLOSE"

        fun start(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayContext: Context? = null
    private var overlay: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var playerView: PlayerView? = null
    private var seekBar: SeekBar? = null
    private var timeLabel: TextView? = null
    private var playButton: TextView? = null
    private var muteButton: TextView? = null
    private var speedButton: TextView? = null
    private var previousButton: TextView? = null
    private var nextButton: TextView? = null
    private var titleLabel: TextView? = null
    private var episodeLabel: TextView? = null
    private var isMuted = false
    private var speedIndex = 1
    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private var userSeeking = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshButtons()
            refreshTimeline()
        }
        override fun onVolumeChanged(volume: Float) = refreshButtons()
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = refreshButtons()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshTimeline()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            refreshTimeline()
            refreshHeader()
            refreshButtons()
            refreshNotification()
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            refreshTimeline()
            refreshButtons()
            if (overlay != null) mainHandler.postDelayed(this, 400L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A startForegroundService() launch must be promoted promptly, even when
        // a later validation decides that the overlay cannot be displayed.
        try {
            promoteToForeground()
        } catch (error: Throwable) {
            Log.e("AnimeGoFloating", "gagal memulai foreground service", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "izin tampil di atas aplikasi lain belum diberikan", Toast.LENGTH_LONG).show()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (PlaybackEngine.currentSource.isBlank()) {
            Toast.makeText(this, "belum ada video yang diputar", Toast.LENGTH_SHORT).show()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            removeOverlay()
            showOverlay()
            PlaybackEngine.player(this).addListener(playerListener)
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
            // The overlay cannot be reconstructed after process death because
            // its PlayerView and in-memory ExoPlayer surface no longer exist.
            return START_NOT_STICKY
        } catch (error: Throwable) {
            // WindowManager failures must not crash the app process.
            Log.e("AnimeGoFloating", "gagal memasang floating overlay", error)
            removeOverlay()
            stopForegroundCompat()
            Toast.makeText(
                this,
                "gagal menampilkan floating player: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        runCatching { PlaybackEngine.player(this).removeListener(playerListener) }
        removeOverlay()
        runCatching { stopForegroundCompat() }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun showOverlay() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Build the overlay window context only after the permission check in
        // onStartCommand. This keeps Service creation safe on devices where the
        // user has not granted SYSTEM_ALERT_WINDOW yet.
        val viewContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // A Service context is not guaranteed to be display-associated.
            // createWindowContext() requires a context attached to a Display;
            // derive one explicitly from the default display before creating
            // the TYPE_APPLICATION_OVERLAY context (Android 11+).
            val displayManager = getSystemService(DisplayManager::class.java)
                ?: error("display manager tidak tersedia")
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: error("display utama tidak ditemukan")
            createDisplayContext(display).createWindowContext(overlayType, null)
        } else {
            this
        }
        overlayContext = viewContext
        val wm = viewContext.getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = viewContext.resources.displayMetrics.density
        val screenWidth = viewContext.resources.displayMetrics.widthPixels
        val screenHeight = viewContext.resources.displayMetrics.heightPixels
        val minWidth = dp(250)
        val maxWidth = (screenWidth - dp(16)).coerceAtLeast(minWidth)
        val width = minOf(dp(340), maxWidth).coerceAtLeast(minWidth)
        val minHeight = dp(220)
        val maxHeight = (screenHeight * 0.84f).roundToInt().coerceAtLeast(minHeight)
        val height = minOf(dp(320), maxHeight).coerceAtLeast(minHeight)

        val root = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(8))
            background = roundedBackground(AndroidColor.rgb(17, 18, 27), dp(18).toFloat(), AndroidColor.rgb(52, 54, 70))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(viewContext).apply {
            text = PlaybackEngine.currentTitle.ifBlank { "AnimeGo" }
            setTextColor(AndroidColor.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val episodeView = TextView(viewContext).apply {
            text = PlaybackEngine.currentEpisode
            setTextColor(AndroidColor.LTGRAY)
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleLabel = titleView
        episodeLabel = episodeView
        titleColumn.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        titleColumn.addView(episodeView, LinearLayout.LayoutParams(-1, -2))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(actionButton("buka") { openPlayer() }, LinearLayout.LayoutParams(dp(48), dp(38)))
        header.addView(actionButton("×") { stopSelf() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        // The overlay uses a TextureView-backed PlayerView. Unlike a normal
        // activity SurfaceView, it composes predictably with a movable/resizable
        // TYPE_APPLICATION_OVERLAY window on Android OEM builds.
        val video = LayoutInflater.from(ContextThemeWrapper(viewContext, R.style.AppTheme))
            .inflate(R.layout.view_floating_player, root, false) as PlayerView
        video.player = PlaybackEngine.player(this)
        playerView = video
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(3)
            bottomMargin = dp(4)
        })

        val seekRow = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeLabel = TextView(viewContext).apply {
            setTextColor(AndroidColor.LTGRAY)
            textSize = 9f
            text = "00:00 / 00:00"
        }
        val bar = SeekBar(viewContext).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(AndroidColor.rgb(190, 145, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(AndroidColor.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(AndroidColor.DKGRAY)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimePreview(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = PlaybackEngine.player(this@FloatingPlayerService)
                    val duration = player.duration
                    if (duration > 0L) player.seekTo((duration * ((seekBar?.progress ?: 0) / 1000f)).toLong())
                    userSeeking = false
                    refreshTimeline()
                }
            })
        }
        seekBar = bar
        seekRow.addView(bar, LinearLayout.LayoutParams(0, dp(30), 1f))
        seekRow.addView(timeLabel, LinearLayout.LayoutParams(dp(78), -2).apply { leftMargin = dp(5) })
        root.addView(seekRow, LinearLayout.LayoutParams(-1, dp(30)))

        val transportControls = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previousButton = controlButton("prev") { PlaybackEngine.skipQueue(this@FloatingPlayerService, -1) }
        transportControls.addView(previousButton, weightedControl())
        transportControls.addView(controlButton("−10") { PlaybackEngine.player(this@FloatingPlayerService).seekBack() }, weightedControl())
        playButton = controlButton("jeda") { togglePlayback() }
        transportControls.addView(playButton, weightedControl())
        transportControls.addView(controlButton("+10") { PlaybackEngine.player(this@FloatingPlayerService).seekForward() }, weightedControl())
        nextButton = controlButton("next") { PlaybackEngine.skipQueue(this@FloatingPlayerService, 1) }
        transportControls.addView(nextButton, weightedControl())
        root.addView(transportControls, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })

        val secondaryControls = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        isMuted = PlaybackEngine.player(this).volume <= 0f
        muteButton = controlButton(if (isMuted) "suara aktif" else "mute") { toggleMute() }
        secondaryControls.addView(muteButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(2); rightMargin = dp(4) })
        speedButton = controlButton("1.0×") { cycleSpeed() }
        secondaryControls.addView(speedButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(4); rightMargin = dp(2) })
        root.addView(secondaryControls, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(1) })

        // Drag from the title area; controls remain directly clickable.
        var lastRawX = 0f
        var lastRawY = 0f
        titleColumn.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moveOverlay(dx, dy)
                    true
                }
                else -> false
            }
        }

        val scaleDetector = ScaleGestureDetector(viewContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                resizeOverlay(detector.scaleFactor)
                return true
            }
        })
        video.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }

        val layout = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - width) / 2).coerceAtLeast(0)
            y = (80 * density).roundToInt().coerceAtLeast(0)
        }

        // Keep references only after addView succeeds, so cleanup is deterministic.
        wm.addView(root, layout)
        overlay = root
        windowParams = layout
        // Only tell the Compose player to detach after the overlay view has
        // successfully attached its own video surface. This follows Media3's
        // recommended new-view-first handoff and leaves the screen player
        // untouched when WindowManager rejects an overlay window.
        PlaybackEngine.setOverlayActive(true)
        refreshButtons()
        refreshTimeline()
    }

    private fun removeOverlay() {
        val old = overlay
        overlay = null
        runCatching { playerView?.player = null }
        playerView = null
        if (old != null) runCatching { windowManager?.removeView(old) }
        PlaybackEngine.setOverlayActive(false)
        windowParams = null
        seekBar = null
        timeLabel = null
        playButton = null
        muteButton = null
        speedButton = null
        previousButton = null
        nextButton = null
        titleLabel = null
        episodeLabel = null
    }

    private fun moveOverlay(dx: Float, dy: Float) {
        val p = windowParams ?: return
        val view = overlay ?: return
        val bounds = screenBounds()
        p.x = (p.x + dx.roundToInt()).coerceIn(0, (bounds.first - p.width).coerceAtLeast(0))
        p.y = (p.y + dy.roundToInt()).coerceIn(0, (bounds.second - p.height).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(view, p) }
    }

    private fun resizeOverlay(scale: Float) {
        if (!scale.isFinite() || scale <= 0f) return
        val p = windowParams ?: return
        val view = overlay ?: return
        val bounds = screenBounds()
        val minWidth = dp(250)
        val minHeight = dp(220)
        p.width = (p.width * scale).roundToInt().coerceIn(minWidth, (bounds.first - dp(12)).coerceAtLeast(minWidth))
        p.height = (p.height * scale).roundToInt().coerceIn(minHeight, (bounds.second * 0.88f).roundToInt().coerceAtLeast(minHeight))
        p.x = p.x.coerceIn(0, (bounds.first - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (bounds.second - p.height).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(view, p) }
    }

    private fun screenBounds(): Pair<Int, Int> {
        val metrics = (overlayContext ?: this).resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun togglePlayback() {
        val player = PlaybackEngine.player(this)
        if (player.isPlaying) player.pause() else player.play()
        refreshButtons()
    }

    private fun toggleMute() {
        val player = PlaybackEngine.player(this)
        isMuted = player.volume > 0f
        player.volume = if (isMuted) 0f else 1f
        isMuted = player.volume <= 0f
        refreshButtons()
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        PlaybackEngine.player(this).setPlaybackSpeed(speeds[speedIndex])
        refreshButtons()
    }

    private fun refreshButtons() {
        val player = runCatching { PlaybackEngine.player(this) }.getOrNull() ?: return
        if (speedIndex !in speeds.indices || speeds[speedIndex] != player.playbackParameters.speed) {
            speedIndex = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - player.playbackParameters.speed) } ?: 1
        }
        isMuted = player.volume <= 0f
        playButton?.text = if (player.isPlaying) "jeda" else "putar"
        muteButton?.text = if (isMuted) "suara aktif" else "mute"
        val speedText = speeds[speedIndex].let { value ->
            if (value % 1f == 0f) value.toInt().toString()
            else value.toString().trimEnd('0').trimEnd('.')
        }
        speedButton?.text = "$speedText×"
        previousButton?.isEnabled = PlaybackEngine.canSkipQueue(-1)
        nextButton?.isEnabled = PlaybackEngine.canSkipQueue(1)
        previousButton?.alpha = if (previousButton?.isEnabled == true) 1f else 0.45f
        nextButton?.alpha = if (nextButton?.isEnabled == true) 1f else 0.45f
    }

    private fun refreshHeader() {
        titleLabel?.text = PlaybackEngine.currentTitle.ifBlank { "AnimeGo" }
        episodeLabel?.text = PlaybackEngine.currentEpisode
    }

    private fun refreshTimeline() {
        val player = runCatching { PlaybackEngine.player(this) }.getOrNull() ?: return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            seekBar?.progress = if (duration > 0L) ((position.toDouble() / duration) * 1000.0).roundToInt().coerceIn(0, 1000) else 0
        }
        timeLabel?.text = "${clock(position)} / ${clock(duration)}"
    }

    private fun updateTimePreview(progress: Int) {
        val duration = runCatching { PlaybackEngine.player(this).duration }.getOrDefault(0L).coerceAtLeast(0L)
        val preview = (duration * (progress / 1000f)).toLong()
        timeLabel?.text = "${clock(preview)} / ${clock(duration)}"
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun openPlayer() {
        startActivity(openPlayerIntent())
        stopSelf()
    }

    private fun openPlayerIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("open_route", "player")
        putExtra("slug", PlaybackEngine.currentSlug)
        putExtra("source", PlaybackEngine.currentSource)
        putExtra("title", PlaybackEngine.currentTitle)
        putExtra("episode", PlaybackEngine.currentEpisode)
        putExtra("cover", PlaybackEngine.currentCover)
        putExtra("position", PlaybackEngine.player(this@FloatingPlayerService).currentPosition.toDouble() / 1000.0)
        putExtra("offline", PlaybackEngine.currentOffline)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Floating player AnimeGo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status floating player AnimeGo"
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            5105,
            openPlayerIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val close = PendingIntent.getService(
            this,
            5106,
            Intent(this, FloatingPlayerService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_animego)
            .setContentTitle("AnimeGo floating player")
            .setContentText(PlaybackEngine.currentTitle)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "buka", open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "tutup", close)
            .build()
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        controlButton(label, action).apply { textSize = 11f }

    private fun controlButton(label: String, action: () -> Unit): TextView = TextView(overlayContext ?: this).apply {
        text = label
        setTextColor(AndroidColor.WHITE)
        textSize = 10f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        background = roundedBackground(AndroidColor.rgb(43, 44, 58), dp(9).toFloat())
        setPadding(dp(4), dp(5), dp(4), dp(5))
        setOnClickListener { action() }
    }

    private fun weightedControl(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) }

    private fun roundedBackground(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * (overlayContext ?: this).resources.displayMetrics.density).roundToInt()

    private fun clock(milliseconds: Long): String {
        if (milliseconds <= 0L) return "00:00"
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
