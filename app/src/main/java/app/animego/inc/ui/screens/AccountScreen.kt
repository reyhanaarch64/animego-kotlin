package app.animego.inc.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.UserAvatar
import app.animego.inc.ui.uiText
import kotlinx.coroutines.launch

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

    LaunchedEffect(uid) {
        if (uid.isNotBlank() && graph.auth.isSignedIn) {
            runCatching { graph.social.getUser(uid) }.onSuccess { user ->
                if (user != null) { username = user.username; displayName = user.displayName; bio = user.bio }
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = ""
            runCatching { graph.avatar.uploadAvatar(uri) }
                .onSuccess { avatarRefresh++; message = "foto profil diperbarui" }
                .onFailure { message = it.message ?: "gagal upload avatar" }
            busy = false
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try {
                block()
                message = "profil berhasil diperbarui"
            } catch (e: Exception) {
                message = e.message ?: "operasi gagal"
            } finally { busy = false }
        }
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
        Text(uiText("foto otomatis dipotong 1:1 dari tengah, di-resize maksimal 512×512, lalu dikompres maksimal 150 KB sebelum disimpan ke database."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
