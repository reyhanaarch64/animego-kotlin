package app.animego.inc.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.UserRound
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.UserAvatar
import app.animego.inc.ui.uiText
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth

@Composable
fun ProfileScreen(
    graph: AppGraph,
    onLoginGoogle: () -> Unit,
    onLoginEmail: () -> Unit,
    onRegister: () -> Unit,
    onEditProfile: () -> Unit,
    onFavorites: () -> Unit,
    onDownloads: () -> Unit,
    onHistory: () -> Unit,
    onCommunity: () -> Unit,
    onSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var uid by remember { mutableStateOf(graph.auth.currentUser?.uid.orEmpty()) }
    var signed by remember { mutableStateOf(graph.auth.isSignedIn) }
    var displayName by remember { mutableStateOf(graph.auth.currentUser?.displayName.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var avatarRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(graph) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            uid = auth.currentUser?.uid.orEmpty()
            signed = auth.currentUser != null && auth.currentUser?.isAnonymous == false
            displayName = auth.currentUser?.displayName.orEmpty()
            avatarRefresh++
        }
        graph.firebase.auth.addAuthStateListener(listener)
        onDispose { graph.firebase.auth.removeAuthStateListener(listener) }
    }
    LaunchedEffect(uid) {
        if (uid.isNotBlank() && signed) {
            runCatching { graph.social.getUser(uid) }.onSuccess { user ->
                if (user != null) { displayName = user.displayName; username = user.username }
            }
        } else username = ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText("profil"), style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (signed) UserAvatar(graph, uid, displayName, 64.dp, avatarRefresh)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (signed) displayName.ifBlank { uiText("pengguna") } else uiText("belum login"), style = MaterialTheme.typography.titleMedium)
                    Text(if (signed) "@${username.ifBlank { uid.take(8) }}" else uiText("login untuk mengakses profil dan menyinkronkan data anime lu."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!signed) {
            Button(onClick = onLoginGoogle, modifier = Modifier.fillMaxWidth()) { Text(uiText("login dengan google")) }
            OutlinedButton(onClick = onLoginEmail, modifier = Modifier.fillMaxWidth()) { Text(uiText("login dengan email")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("belum punya akun?"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRegister) { Text(uiText("daftar"), color = MaterialTheme.colorScheme.primary) }
            }
        }
        Text(uiText("akses cepat"), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (signed) ProfilePill("akun", Lucide.UserRound, onEditProfile)
            ProfilePill("suka", Lucide.Heart, onFavorites)
            ProfilePill("riwayat", Lucide.History, onHistory)
            ProfilePill("offline", Lucide.Download, onDownloads)
            ProfilePill("komunitas", Lucide.Users, onCommunity)
            ProfilePill("pengaturan", Lucide.Settings, onSettings)
        }
        if (signed) {
            TextButton(onClick = { scope.launch { graph.auth.logout() } }) { Text(uiText("keluar")) }
        }
    }
}

@Composable
private fun ProfilePill(title: String, icon: ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(uiText(title), maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
    )
}
