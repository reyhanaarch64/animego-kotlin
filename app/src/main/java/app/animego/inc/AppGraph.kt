package app.animego.inc

import android.content.Context
import app.animego.inc.core.avatar.AvatarRepository
import app.animego.inc.core.database.AnimeDatabase
import app.animego.inc.core.firebase.AuthRepository
import app.animego.inc.core.firebase.CloudSyncRepository
import app.animego.inc.core.firebase.FirebaseProvider
import app.animego.inc.core.firebase.SocialRepository
import app.animego.inc.core.network.AnimeApi
import app.animego.inc.core.settings.AppCacheManager
import app.animego.inc.core.settings.SettingsStore

class AppGraph private constructor(context: Context) {
    private val appContext = context.applicationContext
    val database = AnimeDatabase(appContext)
    val api = AnimeApi()
    val settings = SettingsStore(appContext)
    val cache = AppCacheManager(appContext)
    val firebase = FirebaseProvider()
    val auth = AuthRepository(appContext, firebase)
    val avatar = AvatarRepository(appContext, firebase, database)
    val social = SocialRepository(appContext, firebase, database)
    val sync = CloudSyncRepository(appContext, firebase, database)

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun from(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
