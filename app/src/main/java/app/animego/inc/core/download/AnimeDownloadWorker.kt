package app.animego.inc.core.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.animego.inc.AppGraph
import app.animego.inc.MainActivity
import app.animego.inc.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class AnimeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "animego_downloads"
        private const val NOTIFICATION_ID_BASE = 5200
    }

    private val graph by lazy { AppGraph.from(applicationContext) }
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }

    override suspend fun doWork(): Result = DownloadScheduler.executionMutex.withLock {
        val id = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0) return@withLock Result.failure()
        val row = graph.database.getDownload(id) ?: return@withLock Result.success()
        if (row.status == "completed" && File(row.path).isFile) return@withLock Result.success()

        createChannel()
        setForeground(foregroundInfo(id, "Menyiapkan unduhan", 0, true, row.title, row.ep))
        try {
            withContext(Dispatchers.IO) { download(id) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            graph.database.updateDownload(id, "queued", 0, 0, "")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "unduhan gagal"
            if (runAttemptCount < 3) {
                graph.database.updateDownload(id, "queued", 0, row.total, message)
                Result.retry()
            } else {
                graph.database.updateDownload(id, "failed", 0, 0, message)
                setForeground(foregroundInfo(id, "Unduhan gagal", 0, false, row.title, row.ep, message))
                Result.success()
            }
        }
    }

    private suspend fun download(id: Long) {
        val row = graph.database.getDownload(id) ?: error("data unduhan tidak ditemukan")
        val target = File(row.path)
        val parent = target.parentFile ?: error("folder unduhan tidak valid")
        if (!parent.exists() && !parent.mkdirs()) error("tidak dapat membuat folder unduhan")
        if (target.isFile && target.length() > 0L) {
            graph.database.updateDownload(id, "completed", target.length(), target.length(), "")
            return
        }
        val part = File(row.path + ".part")
        var offset = if (part.isFile) part.length() else 0L
        val headers = runCatching { JSONObject(row.headers) }.getOrDefault(JSONObject())
        val requestBuilder = Request.Builder().url(row.url).get()
        headers.keys().forEach { key ->
            val value = headers.optString(key)
            if (value.isNotBlank() && !key.equals("Range", true)) requestBuilder.header(key, value)
        }
        if (offset > 0) requestBuilder.header("Range", "bytes=$offset-")
        graph.database.updateDownload(id, "downloading", offset, row.total, "")
        var completedByRestart = false

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (offset > 0 && response.code == 416) {
                response.close()
                if (part.exists() && !part.delete()) error("file sementara tidak dapat direset")
                graph.database.updateDownload(id, "downloading", 0, 0, "")
                download(id)
                completedByRestart = true
                return@use
            }
            if (response.code !in 200..299 && response.code != 206) {
                error("HTTP ${response.code}: ${response.message}")
            }
            // Only append when the server explicitly confirms a partial response.
            val append = response.code == 206 && offset > 0
            if (!append) offset = 0L
            val body = response.body ?: error("respons unduhan kosong")
            val rangeTotal = response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.takeIf { it != "*" }
                ?.toLongOrNull()
            val totalHeader = rangeTotal ?: body.contentLength().takeIf { it > 0 }?.plus(offset) ?: row.total
            var downloaded = offset
            val lastProgress = longArrayOf(0L)
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (isStopped) throw kotlinx.coroutines.CancellationException("download dihentikan")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgress[0] >= 700L) {
                            lastProgress[0] = now
                            graph.database.updateDownload(id, "downloading", downloaded, totalHeader, "")
                            val percent = if (totalHeader > 0) ((downloaded * 100) / totalHeader).toInt().coerceIn(0, 100) else 0
                            setProgress(androidx.work.workDataOf("progress" to percent, "downloaded" to downloaded, "total" to totalHeader))
                            setForeground(foregroundInfo(id, "Mengunduh", percent, totalHeader <= 0, row.title, row.ep))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (!part.isFile || part.length() <= 0) error("file unduhan kosong")
            if (target.exists() && !target.delete()) error("file tujuan tidak dapat diganti")
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            val finalSize = target.length()
            graph.database.updateDownload(id, "completed", finalSize, totalHeader.takeIf { it > 0 } ?: finalSize, "")
            setForeground(foregroundInfo(id, "Unduhan selesai", 100, false, row.title, row.ep))
        }
        if (completedByRestart) return
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Unduhan AnimeGo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress unduhan anime"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun foregroundInfo(id: Long, label: String, percent: Int, indeterminate: Boolean, title: String, ep: String, detail: String = ""): ForegroundInfo {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply { putExtra("open_route", "downloads") }
        val pending = PendingIntent.getActivity(applicationContext, id.toInt(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val line = buildString {
            append(if (ep.isBlank()) label else "$label · $ep")
            if (detail.isNotBlank()) append(" · $detail")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_animego)
            .setContentTitle("$label: ${title.ifBlank { "AnimeGo" }}")
            .setContentText(line)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(label == "Mengunduh" || label == "Menyiapkan unduhan")
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (id % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }
}
