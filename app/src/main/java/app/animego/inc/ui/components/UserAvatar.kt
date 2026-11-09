package app.animego.inc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    LaunchedEffect(uid, refreshKey) {
        if (uid.isBlank()) {
            imageFile = null
        } else {
            // Paint the persistent cache immediately, then refresh only if the remote hash changed.
            imageFile = graph.avatar.getCachedAvatarFile(uid)
            val refreshed = graph.avatar.getAvatarFile(uid)
            if (refreshed != null) imageFile = refreshed
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageFile?.isFile == true) {
            val file = imageFile!!
            val cacheKey = "avatar:$uid:${file.lastModified()}:${file.length()}:$refreshKey"
            AsyncImage(
                model = ImageRequest.Builder(context).data(file).memoryCacheKey(cacheKey).diskCacheKey(cacheKey).build(),
                contentDescription = if (label.isBlank()) "profile photo" else "$label profile photo",
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(label.trim().take(1).uppercase().ifBlank { "?" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
