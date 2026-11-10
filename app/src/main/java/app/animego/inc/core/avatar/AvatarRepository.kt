package app.animego.inc.core.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.system.Os
import android.net.Uri
import android.util.Base64
import app.animego.inc.core.database.AnimeDatabase
import app.animego.inc.core.firebase.FirebaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/** Processes avatar images locally and stores only compact Base64 payloads in Realtime Database. */
class AvatarRepository(
    private val context: Context,
    private val firebase: FirebaseProvider,
    private val database: AnimeDatabase
) {
    companion object {
        const val MAX_BYTES = 150 * 1024
        const val MAX_DIMENSION = 512
        private const val META_CHECK_TTL_MS = 5 * 60 * 1000L
    }

    private val avatarDirectory: File get() = File(context.filesDir, "avatar-cache").apply { mkdirs() }

    suspend fun uploadAvatar(uri: Uri, centerX: Float = 0.5f, centerY: Float = 0.5f, zoom: Float = 1f): File = withContext(Dispatchers.IO) {
        val user = firebase.auth.currentUser ?: error("login diperlukan")
        require(!user.isAnonymous) { "fitur ini memerlukan akun" }
        val bytes = compressImage(uri, centerX, centerY, zoom)
        val hash = sha256(bytes)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val now = System.currentTimeMillis()
        val file = File(avatarDirectory, "${user.uid}.jpg")
        val temporary = File(avatarDirectory, "${user.uid}.upload-${System.nanoTime()}.tmp")
        // Prepare the local payload before the cloud write so a local I/O failure
        // cannot leave the server updated while the UI reports an upload failure.
        temporary.writeBytes(bytes)
        val root = firebase.database.reference
        try {
            root.updateChildren(mapOf(
                "avatars/${user.uid}/data" to base64,
                "avatars/${user.uid}/meta/hash" to hash,
                "avatars/${user.uid}/meta/mimeType" to "image/jpeg",
                "avatars/${user.uid}/meta/updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "publicProfiles/${user.uid}/avatarHash" to hash,
                "publicProfiles/${user.uid}/avatarUpdatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
            )).await()
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        // Replace the previous cache file atomically; never delete the valid image first.
        try {
            Os.rename(temporary.absolutePath, file.absolutePath)
        } catch (_: Exception) {
            // A cache write is recoverable from RTDB; do not report a successful
            // cloud upload as failed just because the atomic rename was unavailable.
            runCatching { temporary.copyTo(file, overwrite = true) }
            temporary.delete()
        }
        runCatching { database.putAvatarCache(user.uid, hash, "image/jpeg", now, now, file.absolutePath) }
        file
    }

    /** Returns an already-cached avatar without waiting for network metadata. */
    suspend fun getCachedAvatarFile(uid: String): File? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        database.getAvatarCache(uid)?.path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }

    /** Returns the persistent local file; Base64 is fetched only when metadata proves it changed. */
    suspend fun getAvatarFile(uid: String, forceRefresh: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        val cached = database.getAvatarCache(uid)
        val cachedFile = cached?.path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        val now = System.currentTimeMillis()
        // Negative cache entries also avoid repeating metadata requests for users without an avatar.
        if (!forceRefresh && cached != null && now - cached.checkedAt < META_CHECK_TTL_MS) return@withContext cachedFile

        try {
            val metaSnapshot = firebase.database.reference.child("avatars").child(uid).child("meta").get().await()
            if (!metaSnapshot.exists()) {
                database.putAvatarCache(uid, cached?.hash.orEmpty(), cached?.mimeType ?: "image/jpeg", cached?.updatedAt ?: 0L, now, cachedFile?.absolutePath.orEmpty())
                return@withContext cachedFile
            }
            val hash = metaSnapshot.child("hash").getValue(String::class.java).orEmpty()
            val mime = metaSnapshot.child("mimeType").getValue(String::class.java).orEmpty().ifBlank { "image/jpeg" }
            val updatedAt = metaSnapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            if (cachedFile != null && cached?.hash == hash) {
                database.putAvatarCache(uid, hash, mime, updatedAt, now, cachedFile.absolutePath)
                return@withContext cachedFile
            }
            val encoded = firebase.database.reference.child("avatars").child(uid).child("data").get().await()
                .getValue(String::class.java).orEmpty()
            if (encoded.isBlank()) {
                database.putAvatarCache(uid, cached?.hash.orEmpty(), cached?.mimeType ?: mime, cached?.updatedAt ?: 0L, now, cachedFile?.absolutePath.orEmpty())
                return@withContext cachedFile
            }
            val decoded = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) {
                database.putAvatarCache(uid, cached?.hash.orEmpty(), cached?.mimeType ?: mime, cached?.updatedAt ?: 0L, now, cachedFile?.absolutePath.orEmpty())
                return@withContext cachedFile
            }
            if (decoded.size > MAX_BYTES || hash.isBlank() || sha256(decoded) != hash) {
                database.putAvatarCache(uid, cached?.hash.orEmpty(), cached?.mimeType ?: mime, cached?.updatedAt ?: 0L, now, cachedFile?.absolutePath.orEmpty())
                return@withContext cachedFile
            }
            val file = File(avatarDirectory, "$uid.jpg")
            val temporary = File(avatarDirectory, "$uid.refresh-${System.nanoTime()}.tmp")
            temporary.writeBytes(decoded)
            // Rename replaces the previous file atomically on the same filesystem.
            try {
                Os.rename(temporary.absolutePath, file.absolutePath)
            } catch (_: Exception) {
                temporary.delete()
                return@withContext cachedFile
            }
            database.putAvatarCache(uid, hash, mime, updatedAt, now, file.absolutePath)
            file
        } catch (_: Exception) {
            cachedFile
        }
    }

    fun cacheDirectory(): File = avatarDirectory

    private fun compressImage(uri: Uri, centerX: Float, centerY: Float, zoom: Float): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("gambar tidak bisa dibaca")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "format gambar tidak didukung" }

        // Sample large source images before decoding so a high-resolution photo cannot exhaust RAM.
        var sample = 1
        while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("gagal memproses gambar")

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = orientBitmap(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()

        // Crop a user-selected square region before resizing.
        val baseSide = min(oriented.width, oriented.height)
        val side = (baseSide / zoom.coerceIn(1f, 3f)).toInt().coerceAtLeast(1)
        val cx = (centerX.coerceIn(0f, 1f) * oriented.width).toInt()
        val cy = (centerY.coerceIn(0f, 1f) * oriented.height).toInt()
        val left = (cx - side / 2).coerceIn(0, oriented.width - side)
        val top = (cy - side / 2).coerceIn(0, oriented.height - side)
        val cropped = Bitmap.createBitmap(oriented, left, top, side, side)
        if (cropped !== oriented) oriented.recycle()

        var current = Bitmap.createScaledBitmap(cropped, min(MAX_DIMENSION, side).coerceAtLeast(1), min(MAX_DIMENSION, side).coerceAtLeast(1), true)
        if (current !== cropped) cropped.recycle()

        var quality = 88
        while (true) {
            val output = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val result = output.toByteArray()
            if (result.size <= MAX_BYTES) {
                current.recycle()
                return result
            }

            if (quality > 40) {
                quality = (quality - 8).coerceAtLeast(40)
            } else if (current.width > 64) {
                val newSide = (current.width * 0.8f).toInt().coerceAtLeast(64)
                val smaller = Bitmap.createScaledBitmap(current, newSide, newSide, true)
                current.recycle()
                current = smaller
                quality = 84
            } else if (quality > 24) {
                quality -= 4
            } else if (current.width > 8) {
                val newSide = (current.width * 0.75f).toInt().coerceAtLeast(8)
                val smaller = Bitmap.createScaledBitmap(current, newSide, newSide, true)
                current.recycle()
                current = smaller
                quality = 60
            } else {
                current.recycle()
                error("gambar tidak bisa dikompres ke batas ukuran avatar")
            }
        }
    }

    private fun orientBitmap(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
