package app.animego.inc.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.UserAvatar
import app.animego.inc.ui.uiText
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import kotlin.math.min

@Composable
fun AccountScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uid = graph.auth.currentUser?.uid.orEmpty()
    var displayName by remember { mutableStateOf(graph.auth.currentUser?.displayName.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var avatarRefresh by remember { mutableIntStateOf(0) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var cropX by remember { mutableFloatStateOf(0.5f) }
    var cropY by remember { mutableFloatStateOf(0.5f) }
    var cropZoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(uid) {
        if (uid.isNotBlank() && graph.auth.isSignedIn) {
            runCatching { graph.social.getUser(uid) }.onSuccess { user ->
                if (user != null) { username = user.username; displayName = user.displayName; bio = user.bio }
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { selectedImage = uri; cropX = 0.5f; cropY = 0.5f; cropZoom = 1f; message = "" }
    }

    BackHandler(enabled = selectedImage != null) { selectedImage = null }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try { block(); message = "profil berhasil diperbarui" }
            catch (e: Exception) { message = e.message ?: "operasi gagal" }
            finally { busy = false }
        }
    }

    if (selectedImage != null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("atur foto profil", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedImage = null }, enabled = !busy) { Text("batal") }
            }
            Text("geser gambar untuk memilih bagian yang dipakai. gunakan slider untuk zoom.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            val cropPainter = rememberAsyncImagePainter(model = selectedImage)
            val intrinsic = cropPainter.intrinsicSize
            val sourceWidth = intrinsic.width.takeIf { it.isFinite() && it > 0f } ?: 1f
            val sourceHeight = intrinsic.height.takeIf { it.isFinite() && it > 0f } ?: 1f
            val aspect = sourceWidth / sourceHeight
            val previewWidth = if (aspect >= 1f) 280f * aspect else 280f
            val previewHeight = if (aspect >= 1f) 280f else 280f / aspect
            val density = LocalDensity.current
            val previewWidthPx = with(density) { previewWidth.dp.toPx() }
            val previewHeightPx = with(density) { previewHeight.dp.toPx() }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(280.dp).clip(RoundedCornerShape(18.dp))
                        .pointerInput(cropZoom, previewWidthPx, previewHeightPx, sourceWidth, sourceHeight) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val cropSide = min(sourceWidth, sourceHeight) / cropZoom
                                val halfX = cropSide / (2f * sourceWidth)
                                val halfY = cropSide / (2f * sourceHeight)
                                cropX = (cropX - drag.x / (previewWidthPx * cropZoom)).coerceIn(halfX, 1f - halfX)
                                cropY = (cropY - drag.y / (previewHeightPx * cropZoom)).coerceIn(halfY, 1f - halfY)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = cropPainter,
                        contentDescription = "crop foto profil",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.width(previewWidth.dp).height(previewHeight.dp)
                            .graphicsLayer {
                                scaleX = cropZoom
                                scaleY = cropZoom
                                translationX = (0.5f - cropX) * previewWidthPx * cropZoom
                                translationY = (0.5f - cropY) * previewHeightPx * cropZoom
                            }
                    )
                }
            }
            Text("zoom ${(cropZoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = cropZoom, onValueChange = { cropZoom = it }, valueRange = 1f..3f)
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            Button(onClick = {
                val uri = selectedImage ?: return@Button
                scope.launch {
                    busy = true
                    message = ""
                    runCatching { graph.avatar.uploadAvatar(uri, cropX, cropY, cropZoom) }
                        .onSuccess {
                            avatarRefresh++
                            selectedImage = null
                            message = "foto profil berhasil diupload"
                        }
                        .onFailure { message = it.message ?: "gagal upload avatar" }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "mengupload..." else "simpan dan upload") }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(uiText("akun animego"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(uiText("kembali")) }
        }
        if (!graph.auth.isSignedIn) {
            Text(uiText("login diperlukan"), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            UserAvatar(graph, uid, displayName, 78.dp, avatarRefresh)
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium)
                Text("@$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { avatarPicker.launch("image/*") }, enabled = !busy) { Text(uiText("ubah foto profil")) }
            }
        }
        Text("foto dipotong sesuai area pilihan, dikompresi maksimal 150 KB, lalu disimpan sebagai Base64 di Firebase Realtime Database.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText("username")) }, singleLine = true)
        OutlinedTextField(displayName, { displayName = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText("nama tampilan")) }, singleLine = true)
        OutlinedTextField(bio, { bio = it }, modifier = Modifier.fillMaxWidth(), label = { Text(uiText("bio")) }, maxLines = 3)
        Button(onClick = { run { graph.social.updateProfile(username, displayName, bio) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiText("simpan profil")) }
        if (graph.auth.currentUser?.isEmailVerified == false) {
            Text(uiText("email belum diverifikasi"))
            OutlinedButton(onClick = { run { graph.auth.resendVerification() } }, enabled = !busy) { Text(uiText("kirim ulang verifikasi")) }
        }
        OutlinedButton(onClick = { run { graph.auth.logout(); onBack() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiText("keluar")) }
        if (message.isNotBlank()) Text(uiText(message), color = MaterialTheme.colorScheme.primary)
    }
}
