package app.animego.inc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.animego.inc.ui.AnimeGoApp

class MainActivity : AppCompatActivity() {
    private var requestedRoute by mutableStateOf("home")
    private var routeRequestId by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(
                "animego_messages", getString(R.string.ui_003), android.app.NotificationManager.IMPORTANCE_HIGH
            ))
        }
        requestedRoute = routeFromIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3301)
        }
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AnimeGoApp(graph = AppGraph.from(this), initialRoute = requestedRoute, routeRequestId = routeRequestId) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute = routeFromIntent(intent)
        routeRequestId += 1L
    }

    private fun routeFromIntent(intent: Intent?): String {
        val conversationId = intent?.getStringExtra("conversationId")
        if (!conversationId.isNullOrBlank()) return "chat/${Uri.encode(conversationId)}"
        return when (intent?.getStringExtra("open_route")) {
            "downloads" -> "downloads"
            "player" -> {
                val slug = Uri.encode(intent.getStringExtra("slug").orEmpty())
                val source = Uri.encode(intent.getStringExtra("source").orEmpty())
                val title = Uri.encode(intent.getStringExtra("title").orEmpty())
                val episode = Uri.encode(intent.getStringExtra("episode").orEmpty())
                val cover = Uri.encode(intent.getStringExtra("cover").orEmpty())
                val position = intent.getDoubleExtra("position", 0.0).toFloat()
                val offline = intent.getBooleanExtra("offline", false)
                "player?slug=$slug&index=-1&source=$source&title=$title&episode=$episode&cover=$cover&position=$position&offline=$offline"
            }
            else -> "home"
        }
    }
}
