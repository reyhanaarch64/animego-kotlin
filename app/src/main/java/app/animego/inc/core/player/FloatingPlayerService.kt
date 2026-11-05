package app.animego.inc.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import app.animego.inc.MainActivity
import app.animego.inc.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.X

class FloatingPlayerService : Service() {
    companion object {
        const val ACTION_SHOW = "app.animego.inc.FLOATING_SHOW"
        private const val CHANNEL_ID = "animego_floating"
        private const val NOTIFICATION_ID = 5102
        fun start(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlay: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (PlaybackEngine.currentSource.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        removeOverlay()
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        stopForeground(true)
        super.onDestroy()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val width = minOf((300 * density).toInt(), screenWidth - (20 * density).toInt()).coerceAtLeast((210 * density).toInt())
        val height = minOf((205 * density).toInt(), (screenHeight * 0.72f).toInt()).coerceAtLeast((150 * density).toInt())
        val view = ComposeView(this)
        view.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FloatingOverlay(
                    title = PlaybackEngine.currentTitle,
                    episode = PlaybackEngine.currentEpisode,
                    onClose = { stopSelf() },
                    onOpen = {
                        startActivity(openPlayerIntent())
                        stopSelf()
                    },
                    onToggle = {
                        val player = PlaybackEngine.player(this)
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onScale = { scale ->
                        params?.let { p ->
                            val minWidth = (210 * resources.displayMetrics.density).toInt()
                            val minHeight = (150 * resources.displayMetrics.density).toInt()
                            p.width = (p.width * scale).toInt().coerceIn(minWidth, resources.displayMetrics.widthPixels - (12 * resources.displayMetrics.density).toInt())
                            p.height = (p.height * scale).toInt().coerceIn(minHeight, (resources.displayMetrics.heightPixels * 0.78f).toInt())
                            p.x = p.x.coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
                            p.y = p.y.coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
                            runCatching { windowManager?.updateViewLayout(view, p) }
                        }
                    },
                    onDrag = { dx, dy ->
                        params?.let { p ->
                            p.x = (p.x + dx).toInt()
                            p.y = (p.y + dy).toInt()
                            runCatching { windowManager?.updateViewLayout(view, p) }
                        }
                    }
                )
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val layout = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = ((screenWidth - width) / 2).coerceAtLeast(0); y = (90 * density).toInt() }
        params = layout
        overlay = view
        windowManager?.addView(view, layout)
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
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Floating player AnimeGo", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
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
        val pending = PendingIntent.getActivity(this, 5105, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_animego)
            .setContentTitle("AnimeGo floating player").setContentText(PlaybackEngine.currentTitle)
            .setContentIntent(pending).setOngoing(true).build()
    }
}

@Composable
private fun FloatingOverlay(title: String, episode: String, onClose: () -> Unit, onOpen: () -> Unit, onToggle: () -> Unit, onScale: (Float) -> Unit, onDrag: (Float, Float) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback by PlaybackEngine.snapshot.collectAsState()
    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onScale(zoom) }
        },
        shape = RoundedCornerShape(18.dp), color = Color(0xFF11111A), tonalElevation = 8.dp
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, amount -> change.consume(); onDrag(amount.x, amount.y) }
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Text(episode, color = Color.LightGray, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onOpen) { Icon(Lucide.ExternalLink, contentDescription = "kembali ke aplikasi", tint = Color.White) }
                IconButton(onClick = onToggle) {
                    Icon(if (playback.isPlaying) Lucide.Pause else Lucide.Play, contentDescription = "play/pause", tint = Color.White)
                }
                IconButton(onClick = onClose) { Icon(Lucide.X, contentDescription = "tutup", tint = Color.White) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(12.dp))) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                    player = PlaybackEngine.player(ctx)
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                } }, update = { it.player = PlaybackEngine.player(context) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
