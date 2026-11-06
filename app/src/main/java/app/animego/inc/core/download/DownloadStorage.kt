package app.animego.inc.core.download

import android.content.Context
import android.os.Environment
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.Episode
import app.animego.inc.core.database.AnimeDatabase
import org.json.JSONObject
import java.io.File
import java.net.URL

object DownloadStorage {
    fun queue(context: Context, database: AnimeDatabase, anime: Anime, episode: Episode, cookie: String = ""): Long {
        require(episode.url.isNotBlank()) { "tautan video kosong" }
        val animeId = anime.id
        val fallback = "animego-${if (animeId > 0) animeId else "video"}-${sanitize(episode.name)}"
        val filename = fileNameFromUrl(episode.url, fallback)
        @Suppress("DEPRECATION")
        val root = context.getExternalMediaDirs().firstOrNull()
            ?: File(Environment.getExternalStorageDirectory(), "Android/media/${context.packageName}")
        val directory = File(root, "${sanitize(anime.title)}/$animeId")
        if (!directory.exists() && !directory.mkdirs()) error("tidak dapat membuat folder unduhan")
        val target = File(directory, filename)
        if (target.isFile && target.length() > 0L) {
            val existing = database.findDownloadByPath(target.absolutePath)
            if (existing != null) {
                database.updateDownload(existing.id, "completed", target.length(), target.length(), "")
                return existing.id
            }
        }
        val headers = JSONObject().apply {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 AnimeGo/2.0")
            put("Referer", "https://karanime.com/")
            put("Origin", "https://karanime.com")
            put("Accept", "video/mp4,video/*,*/*;q=0.8")
            put("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Accept-Encoding", "identity")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        return database.addDownload(anime.slug, anime.id, anime.title, episode.name, episode.url,
            target.absolutePath, filename, headers.toString())
    }

    fun delete(recordPath: String): Boolean {
        val target = File(recordPath)
        val part = File("$recordPath.part")
        val targetResult = !target.exists() || target.delete()
        val partResult = !part.exists() || part.delete()
        return targetResult && partResult
    }

    fun sanitize(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Anime" }

    private fun fileNameFromUrl(url: String, fallback: String): String {
        var name = runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
        name = name.replace('%', '_')
        if (name.isBlank() || name == ".mp4") name = fallback
        if (!name.endsWith(".mp4", true)) name += ".mp4"
        return sanitize(name).trimEnd('.').ifBlank { "video.mp4" }
    }
}
