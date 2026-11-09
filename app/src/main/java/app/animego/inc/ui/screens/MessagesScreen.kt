package app.animego.inc.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.core.firebase.LocalConversation
import app.animego.inc.ui.components.EmptyState
import app.animego.inc.ui.components.UserAvatar
import app.animego.inc.ui.uiText
import kotlinx.coroutines.launch
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.auth.FirebaseAuth

@Composable
fun MessagesScreen(graph: AppGraph, onChat: (String) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var uid by remember { mutableStateOf(graph.auth.currentUser?.uid.orEmpty()) }
    var signed by remember { mutableStateOf(graph.auth.isSignedIn) }
    var conversations by remember { mutableStateOf<List<LocalConversation>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    DisposableEffect(graph) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            uid = auth.currentUser?.uid.orEmpty()
            signed = auth.currentUser != null && auth.currentUser?.isAnonymous == false
            conversations = emptyList()
            refresh++
        }
        graph.firebase.auth.addAuthStateListener(listener)
        onDispose { graph.firebase.auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(uid, signed, refresh) {
        if (signed) {
            loading = true
            conversations = graph.database.getConversations(uid)
            runCatching { graph.social.getConversations() }
                .onSuccess { conversations = it }
                .onFailure { status = it.message.orEmpty() }
            loading = false
        } else conversations = emptyList()
    }

    DisposableEffect(uid, signed) {
        if (!signed || uid.isBlank()) return@DisposableEffect onDispose { }
        val ref = graph.firebase.database.reference.child("userConversations").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { refresh++ }
            override fun onCancelled(error: DatabaseError) { status = error.message }
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(uiText("pesan"), style = MaterialTheme.typography.headlineSmall)
        if (!signed) {
            EmptyState(uiText("login diperlukan untuk menggunakan chat."), uiText("percakapan dengan pengguna lain akan muncul di sini."))
            TextButton(onClick = onLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(uiText("login")) }
        } else {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("cari percakapan...")) }, singleLine = true
            )
            if (status.isNotBlank()) Text(uiText(status), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val filtered = conversations.filter {
                it.otherDisplayName.contains(query, true) || it.otherUsername.contains(query, true) || it.lastMessage.contains(query, true)
            }
            if (filtered.isEmpty() && !loading) {
                EmptyState(uiText("belum ada percakapan"), uiText("percakapan dengan pengguna lain akan muncul di sini."))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(filtered, key = { it.id }) { conversation ->
                        Card(Modifier.fillMaxWidth().clickable { onChat(conversation.id) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                UserAvatar(graph, conversation.otherUid, conversation.otherDisplayName, 48.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(conversation.otherDisplayName.ifBlank { "@${conversation.otherUsername}".ifBlank { conversation.otherUid.take(8) } }, style = MaterialTheme.typography.titleSmall)
                                    Text(conversation.lastMessage.ifBlank { uiText("mulai percakapan") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (conversation.unreadCount > 0) Badge { Text(conversation.unreadCount.toString()) }
                            }
                        }
                    }
                }
            }
        }
    }
}
