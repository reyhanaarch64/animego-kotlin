package app.animego.inc.core.firebase

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("animego-cloud-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun syncSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("animego-cloud-sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().build())
    }
}
