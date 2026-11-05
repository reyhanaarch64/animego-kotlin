package app.animego.inc.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.animego.inc.AppGraph
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

object DownloadScheduler {
    /** Serializes transfers to preserve the old app's one-download-at-a-time queue. */
    internal val executionMutex = Mutex()

    private fun uniqueName(downloadId: Long) = "animego_download_$downloadId"

    fun enqueue(context: Context, downloadId: Long) {
        if (downloadId <= 0) return
        val request = OneTimeWorkRequestBuilder<AnimeDownloadWorker>()
            .setInputData(Data.Builder().putLong(AnimeDownloadWorker.KEY_DOWNLOAD_ID, downloadId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .addTag(uniqueName(downloadId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(downloadId), ExistingWorkPolicy.KEEP, request)
    }

    /** KEEP prevents duplicate jobs when old pending database rows are restored after boot/update. */
    fun enqueuePending(context: Context) {
        val app = context.applicationContext
        val database = AppGraph.from(app).database
        database.getPendingDownloads().forEach { enqueue(app, it.id) }
    }
}
