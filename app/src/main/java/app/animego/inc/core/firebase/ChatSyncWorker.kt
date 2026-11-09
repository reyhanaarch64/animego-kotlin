package app.animego.inc.core.firebase

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.animego.inc.AppGraph

class ChatSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppGraph.from(applicationContext).social.syncPendingMessages()
        Result.success()
    } catch (_: Exception) {
        if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}
