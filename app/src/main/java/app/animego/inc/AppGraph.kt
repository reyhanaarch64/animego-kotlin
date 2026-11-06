package app.animego.inc

import android.content.Context
import app.animego.inc.core.database.AnimeDatabase
import app.animego.inc.core.network.AnimeApi
import app.animego.inc.core.settings.SettingsStore
import app.animego.inc.core.firebase.FirebaseProvider
import app.animego.inc.core.firebase.AuthRepository
import app.animego.inc.core.firebase.SocialRepository
import app.animego.inc.core.firebase.CloudSyncRepository

class AppGraph private constructor(context: Context) {
    val database = AnimeDatabase(context)
    val api = AnimeApi()
    val settings = SettingsStore(context)
    val firebase = FirebaseProvider()
    val auth = AuthRepository(context, firebase)
    val social = SocialRepository(firebase)
    val sync = CloudSyncRepository(firebase, database)

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun from(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
