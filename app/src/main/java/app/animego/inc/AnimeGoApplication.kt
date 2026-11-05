package app.animego.inc

import android.app.Application
import androidx.work.Configuration

class AnimeGoApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        AppGraph.from(this)
    }
}
