package app.animego.inc.ui.screens
import app.animego.inc.ui.uiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.core.firebase.CloudComment
import app.animego.inc.ui.components.UserAvatar
import kotlinx.coroutines.launch

@Composable
fun CommentsScreen(graph: AppGraph, animeSlug: String, onBack: () -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<CloudComment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(graph.auth.isSignedIn) }
    var likedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    DisposableEffect(graph) {
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { loggedIn = it.currentUser != null && it.currentUser?.isAnonymous == false }
        graph.firebase.auth.addAuthStateListener(listener)
        onDispose { graph.firebase.auth.removeAuthStateListener(listener) }
    }
    fun reload() { scope.launch {
        busy = true
        runCatching { graph.social.getComments(animeSlug) }.onSuccess { loaded ->
            comments = loaded
            if (loggedIn) {
                val liked = mutableSetOf<String>()
                for (comment in loaded) if (graph.social.hasLikedComment(animeSlug, comment.id)) liked.add(comment.id)
                likedIds = liked
            } else likedIds = emptySet()
        }.onFailure { status = it.message.orEmpty() }
        busy = false
    } }
    LaunchedEffect(animeSlug, loggedIn) { reload() }
    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 720.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column { Text(app.animego.inc.ui.uiText("komentar"), style = MaterialTheme.typography.titleLarge); Text("$animeSlug · ${comments.size}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onBack) { Text("tutup") }
        }
        if (status.isNotBlank()) Text(uiText(status), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(comments, key = { it.id }) { comment ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            UserAvatar(graph, comment.authorId, comment.authorName, 34.dp)
                            Text(comment.authorName, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(comment.text)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (loggedIn) scope.launch {
                                    val next = comment.id !in likedIds
                                    runCatching { graph.social.likeComment(animeSlug, comment.id, next) }
                                        .onSuccess { likedIds = if (next) likedIds + comment.id else likedIds - comment.id; reload() }
                                        .onFailure { status = it.message.orEmpty() }
                                } else onLogin()
                            }) { Text("${uiText(if (comment.id in likedIds) "disukai" else "suka")} · ${comment.likeCount}") }
                            TextButton(onClick = { if (loggedIn) replyTo = comment.id else onLogin() }) { Text(app.animego.inc.ui.uiText("balas")) }
                            if (loggedIn) TextButton(onClick = { scope.launch { runCatching { graph.social.report("comment", comment.id, "laporan pengguna") }.onSuccess { status = "laporan dikirim" }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("laporkan")) }
                        }
                    }
                }
            }
        }
        if (replyTo.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(app.animego.inc.ui.uiTextFormat("membalas komentar", replyTo.take(8)), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { replyTo = "" }) { Text(app.animego.inc.ui.uiText("batal")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), label = { Text(uiText(if (replyTo.isBlank()) "tulis komentar" else "tulis balasan")) }, maxLines = 3)
            Button(onClick = {
                if (!loggedIn) onLogin() else scope.launch {
                    busy = true
                    runCatching { graph.social.postComment(animeSlug, draft, replyTo) }
                        .onSuccess { draft = ""; replyTo = ""; reload() }
                        .onFailure { status = it.message.orEmpty() }
                    busy = false
                }
            }, enabled = draft.isNotBlank() && !busy) { Text(uiText(if (loggedIn) "kirim" else "masuk")) }
        }
    }
}
