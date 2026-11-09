package app.animego.inc.core.firebase

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
    private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(networkConstraints()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("animego-cloud-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraints())
            .setInitialDelay(15, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE keeps the active sync intact and guarantees a follow-up pass for newer local changes.
        WorkManager.getInstance(context).enqueueUniqueWork("animego-cloud-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
