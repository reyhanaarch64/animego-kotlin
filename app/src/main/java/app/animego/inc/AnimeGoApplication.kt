package app.animego.inc

import android.app.Application
import androidx.work.Configuration

class AnimeGoApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        AppGraph.from(this)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(
                "animego_messages", "pesan", android.app.NotificationManager.IMPORTANCE_HIGH
            ))
        }
        app.animego.inc.core.firebase.CloudSyncScheduler.schedulePeriodic(this)
        if (AppGraph.from(this).auth.isSignedIn) {
            app.animego.inc.core.firebase.CloudSyncScheduler.syncSoon(this)
            app.animego.inc.core.firebase.ChatSyncScheduler.schedule(this)
        }
    }
}
