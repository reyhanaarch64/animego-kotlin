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
import android.view.ViewConfiguration
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.widget.LinearLayout
import android.widget.FrameLayout
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
    private var overlay: FloatingOverlayRoot? = null
    private var controlsOverlay: View? = null
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
        val viewContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        val screenWidth = viewContext.resources.displayMetrics.widthPixels
        val screenHeight = viewContext.resources.displayMetrics.heightPixels

        // The floating window is only the video rectangle. All controls are
        // layered on top of the video, so they do not increase its dimensions.
        val minWidth = dp(240)
        val maxWidth = (screenWidth - dp(24)).coerceAtLeast(minWidth)
        val width = dp(300).coerceIn(minWidth, maxWidth)
        val height = (width * 9f / 16f).roundToInt()

        val root = FloatingOverlayRoot(viewContext).apply {
            background = roundedBackground(AndroidColor.BLACK, dp(14).toFloat())
            clipToOutline = true
            elevation = dp(14).toFloat()
        }

        val video = LayoutInflater.from(ContextThemeWrapper(viewContext, R.style.AppTheme))
            .inflate(R.layout.view_floating_player, root, false) as PlayerView
        video.player = PlaybackEngine.player(this)
        video.useController = false
        playerView = video
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        video.setOnClickListener { toggleControls() }

        val controls = FrameLayout(viewContext).apply {
            isClickable = false
            isFocusable = false
        }
        controlsOverlay = controls
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        // Compact top overlay: title on the left, actions on the right.
        val header = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(3), dp(5), dp(3))
            background = roundedBackground(AndroidColor.argb(178, 8, 9, 15), dp(9).toFloat())
            setOnClickListener { toggleControls() }
        }
        val titleColumn = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val titleView = TextView(viewContext).apply {
            text = PlaybackEngine.currentTitle.ifBlank { "AnimeGo" }
            setTextColor(AndroidColor.WHITE)
            textSize = 10.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val episodeView = TextView(viewContext).apply {
            text = PlaybackEngine.currentEpisode
            setTextColor(AndroidColor.LTGRAY)
            textSize = 8f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleLabel = titleView
        episodeLabel = episodeView
        titleColumn.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        titleColumn.addView(episodeView, LinearLayout.LayoutParams(-1, -2))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -1, 1f))
        muteButton = controlButton(if (PlaybackEngine.player(this).volume <= 0f) "SOUND" else "MUTE") {
            toggleMute()
        }.apply { textSize = 8f }
        header.addView(muteButton, LinearLayout.LayoutParams(dp(38), dp(28)).apply { leftMargin = dp(3) })
        speedButton = controlButton("1×") { cycleSpeed() }.apply { textSize = 8f }
        header.addView(speedButton, LinearLayout.LayoutParams(dp(38), dp(28)).apply { leftMargin = dp(3) })
        header.addView(actionButton("↗") { openPlayer() }, LinearLayout.LayoutParams(dp(30), dp(28)).apply { leftMargin = dp(3) })
        header.addView(actionButton("×") { stopSelf() }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(3) })
        controls.addView(
            header,
            FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP).apply {
                leftMargin = dp(5); topMargin = dp(5); rightMargin = dp(5)
            }
        )

        // Center transport controls are drawn directly over the video.
        val transport = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = roundedBackground(AndroidColor.argb(150, 8, 9, 15), dp(24).toFloat())
            setOnClickListener { toggleControls() }
        }
        previousButton = controlButton("|◀") {
            PlaybackEngine.skipQueue(this@FloatingPlayerService, -1)
        }
        transport.addView(previousButton, LinearLayout.LayoutParams(dp(34), dp(36)))
        transport.addView(controlButton("−10") {
            PlaybackEngine.player(this@FloatingPlayerService).seekBack()
        }, LinearLayout.LayoutParams(dp(38), dp(36)))
        playButton = controlButton("Ⅱ") { togglePlayback() }.apply { textSize = 17f }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(42), dp(38)))
        transport.addView(controlButton("+10") {
            PlaybackEngine.player(this@FloatingPlayerService).seekForward()
        }, LinearLayout.LayoutParams(dp(38), dp(36)))
        nextButton = controlButton("▶|") {
            PlaybackEngine.skipQueue(this@FloatingPlayerService, 1)
        }
        transport.addView(nextButton, LinearLayout.LayoutParams(dp(34), dp(36)))
        controls.addView(
            transport,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER)
        )

        // Seekbar and time are part of the bottom video overlay, not a row
        // below the PlayerView. The progress ticker never overwrites scrubbing.
        val bottomPanel = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
            background = roundedBackground(AndroidColor.argb(188, 8, 9, 15), dp(8).toFloat())
            setOnClickListener { toggleControls() }
        }
        val bar = SeekBar(viewContext).apply {
            max = 1000
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(AndroidColor.rgb(190, 145, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(AndroidColor.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(AndroidColor.argb(150, 220, 220, 220))
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimePreview(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = PlaybackEngine.player(this@FloatingPlayerService)
                    val duration = player.duration
                    if (duration > 0L) {
                        player.seekTo((duration * ((seekBar?.progress ?: 0) / 1000f)).toLong())
                    }
                    userSeeking = false
                    refreshTimeline()
                }
            })
        }
        seekBar = bar
        bottomPanel.addView(bar, LinearLayout.LayoutParams(-1, dp(24)))
        timeLabel = TextView(viewContext).apply {
            setTextColor(AndroidColor.WHITE)
            textSize = 8f
            text = "00:00 / 00:00"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        bottomPanel.addView(timeLabel, LinearLayout.LayoutParams(-1, dp(12)))
        controls.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, dp(43), Gravity.BOTTOM).apply {
                leftMargin = dp(5); rightMargin = dp(5); bottomMargin = dp(5)
            }
        )

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
            // Start near the lower-right edge instead of permanently appearing
            // in the middle of the display. The user can drag it anywhere.
            x = (screenWidth - width - dp(14)).coerceAtLeast(0)
            y = (screenHeight * 0.58f).roundToInt().coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        }

        wm.addView(root, layout)
        overlay = root
        windowParams = layout
        PlaybackEngine.setOverlayActive(true)
        refreshButtons()
        refreshTimeline()
        setControlsVisible(true)
    }

    private fun toggleControls() {
        val currentlyVisible = controlsOverlay?.visibility == View.VISIBLE
        setControlsVisible(!currentlyVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun removeOverlay() {
        val old = overlay
        overlay = null
        controlsOverlay = null
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
        val minWidth = dp(240)
        val maxWidth = (bounds.first - dp(24)).coerceAtLeast(minWidth)
        val maxHeightWidth = ((bounds.second - dp(24)) * 16f / 9f).roundToInt()
        val maxWidthByHeight = maxHeightWidth.coerceAtLeast(minWidth)
        val newWidth = (p.width * scale).roundToInt().coerceIn(minWidth, minOf(maxWidth, maxWidthByHeight))
        p.width = newWidth
        p.height = (newWidth * 9f / 16f).roundToInt()
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
        playButton?.text = if (player.isPlaying) "Ⅱ" else "▶"
        muteButton?.text = if (isMuted) "SOUND" else "MUTE"
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

    /**
     * Observes touches before dispatching them to children. This allows dragging
     * from almost any point of the video window while normal child controls
     * (especially the SeekBar) continue to receive their own touch events.
     * Pinch gestures resize the whole window while preserving a 16:9 ratio.
     */
    private inner class FloatingOverlayRoot(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false
        private var downOnSeekBar = false
        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    resizeOverlay(detector.scaleFactor)
                    return true
                }
            }
        )

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            // Once a drag crosses touch slop, cancel the child control's click
            // target so moving the popup from a button cannot also activate it.
            return scaleDetector.isInProgress || (dragging && event.pointerCount == 1)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    downOnSeekBar = isPointInside(seekBar, event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val totalDx = event.rawX - downRawX
                        val totalDy = event.rawY - downRawY
                        val passedSlop = kotlin.math.hypot(totalDx.toDouble(), totalDy.toDouble()) > touchSlop
                        val verticalDragFromSeek = downOnSeekBar && kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx) * 1.2f
                        if (!dragging && passedSlop && (!downOnSeekBar || verticalDragFromSeek)) {
                            dragging = true
                        }
                        if (dragging) {
                            moveOverlay(event.rawX - lastRawX, event.rawY - lastRawY)
                        }
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    downOnSeekBar = false
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    // Rebase the drag origin around pinch gestures so the
                    // remaining finger cannot cause a sudden window jump.
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                }
            }
            return super.dispatchTouchEvent(event)
        }

        private fun isPointInside(target: View?, rawX: Float, rawY: Float): Boolean {
            target ?: return false
            if (!target.isShown) return false
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            return rawX >= location[0] && rawX <= location[0] + target.width &&
                rawY >= location[1] && rawY <= location[1] + target.height
        }
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
