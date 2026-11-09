package app.animego.inc.core.settings

import android.content.Context
import java.io.File
import coil3.imageLoader

class AppCacheManager(private val context: Context) {
    data class Breakdown(val avatars: Long, val images: Long, val temporary: Long) {
        val total: Long get() = avatars + images + temporary
    }

    fun calculate(): Breakdown {
        val avatarBytes = sizeOf(File(context.filesDir, "avatar-cache"))
        val imageDirectories = listOf("image_cache", "coil", "image_loader_disk_cache")
            .map { File(context.cacheDir, it) }
        val imageBytes = imageDirectories.sumOf(::sizeOf)
        val temporary = (sizeOf(context.cacheDir) - imageBytes).coerceAtLeast(0L) +
            (context.externalCacheDir?.let(::sizeOf) ?: 0L)
        return Breakdown(avatarBytes, imageBytes, temporary)
    }

    fun clear() {
        runCatching { context.imageLoader.memoryCache?.clear() }
        runCatching { context.imageLoader.diskCache?.clear() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        File(context.filesDir, "avatar-cache").deleteRecursively()
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::sizeOf) ?: 0L
    }
}
