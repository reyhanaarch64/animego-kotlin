package app.animego.inc

import android.content.Context
import app.animego.inc.core.database.AnimeDatabase
import app.animego.inc.core.network.AnimeApi
import app.animego.inc.core.settings.SettingsStore

class AppGraph private constructor(context: Context) {
    val database = AnimeDatabase(context)
    val api = AnimeApi()
    val settings = SettingsStore(context)

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun from(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
