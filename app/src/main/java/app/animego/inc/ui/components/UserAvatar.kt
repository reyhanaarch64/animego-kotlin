package app.animego.inc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
fun UserAvatar(graph: AppGraph, uid: String, label: String = "", size: Dp = 44.dp, refreshKey: Int = 0) {
    val context = LocalContext.current
    var imageFile by remember(uid) { mutableStateOf<File?>(null) }
    var googlePhoto by remember(uid) { mutableStateOf<String?>(null) }
    var fallbackInitial by remember(uid, label) { mutableStateOf(label.trim().take(1).uppercase().ifBlank { "?" }) }
    var remoteVersion by remember(uid) { mutableIntStateOf(0) }
    DisposableEffect(uid) {
        val ref = graph.firebase.database.reference.child("avatars").child(uid).child("meta")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) { remoteVersion++ }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) { }
        }
        if (uid.isNotBlank()) ref.addValueEventListener(listener)
        onDispose { if (uid.isNotBlank()) ref.removeEventListener(listener) }
    }
    LaunchedEffect(uid, refreshKey, remoteVersion) {
        if (uid.isBlank()) {
            imageFile = null
            googlePhoto = null
            return@LaunchedEffect
        }
        val current = graph.auth.currentUser
        if (current?.uid == uid) {
            googlePhoto = current.photoUrl?.toString()
            fallbackInitial = current.email?.trim()?.take(1)?.uppercase()?.takeIf { it.isNotBlank() }
                ?: label.trim().take(1).uppercase().ifBlank { "?" }
        } else {
            googlePhoto = null
            fallbackInitial = label.trim().take(1).uppercase().ifBlank { "?" }
        }
        // Render cached/custom avatar first; only fetch public profile fallback when no uploaded image exists.
        imageFile = graph.avatar.getCachedAvatarFile(uid)
        val refreshed = graph.avatar.getAvatarFile(uid, forceRefresh = remoteVersion > 0)
        if (refreshed != null) {
            imageFile = refreshed
        } else if (current?.uid != uid) {
            runCatching { graph.social.getUser(uid) }.onSuccess { profile ->
                googlePhoto = profile?.googlePhotoUrl?.takeIf(String::isNotBlank)
                fallbackInitial = profile?.avatarFallback?.takeIf(String::isNotBlank) ?: fallbackInitial
            }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageFile?.isFile == true) {
            val file = imageFile!!
            val cacheKey = "avatar:$uid:${file.lastModified()}:${file.length()}:$refreshKey:$remoteVersion"
            AsyncImage(
                model = ImageRequest.Builder(context).data(file).memoryCacheKey(cacheKey).diskCacheKey(cacheKey).build(),
                contentDescription = if (label.isBlank()) "profile photo" else "$label profile photo",
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else if (!googlePhoto.isNullOrBlank()) {
            AsyncImage(model = googlePhoto, contentDescription = "$label profile photo", modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Text(fallbackInitial, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
