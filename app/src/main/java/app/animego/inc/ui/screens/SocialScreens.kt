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
import app.animego.inc.core.firebase.CloudUser
import app.animego.inc.core.firebase.ChatMessage
import app.animego.inc.ui.components.UserAvatar
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun SocialHubScreen(graph: AppGraph, onAccount: () -> Unit, onChat: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<CloudUser>>(emptyList()) }
    var friends by remember { mutableStateOf<List<CloudUser>>(emptyList()) }
    var requests by remember { mutableStateOf<List<app.animego.inc.core.firebase.FriendRequest>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var signed by remember { mutableStateOf(graph.auth.isSignedIn) }
    DisposableEffect(graph) {
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { signed = it.currentUser != null && it.currentUser?.isAnonymous == false }
        graph.firebase.auth.addAuthStateListener(listener)
        onDispose { graph.firebase.auth.removeAuthStateListener(listener) }
    }

    fun refresh() { scope.launch {
        busy = true
        try { if (signed) { friends = graph.social.getFriends(); requests = graph.social.getIncomingRequests() } }
        catch (e: Exception) { status = e.message ?: "gagal memuat" }
        finally { busy = false }
    } }
    LaunchedEffect(signed) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(app.animego.inc.ui.uiText("komunitas"), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onAccount) { Text(app.animego.inc.ui.uiText(if (signed) "akun" else "masuk")) }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(app.animego.inc.ui.uiText("cari username (min. 2 karakter)")) }, singleLine = true)
        Button(onClick = {
            scope.launch { busy = true; try { users = graph.social.searchUsers(query) } catch (e: Exception) { status = e.message ?: "pencarian gagal" }; busy = false }
        }, enabled = query.trim().length >= 2 && !busy, modifier = Modifier.fillMaxWidth()) { Text(app.animego.inc.ui.uiText("cari pengguna")) }
        if (status.isNotBlank()) Text(uiText(status), color = MaterialTheme.colorScheme.error)
        if (!signed) Text(app.animego.inc.ui.uiText("mode guest: lu bisa membaca konten publik, tetapi follow, pertemanan, dan chat memerlukan akun."))
        if (signed && requests.isNotEmpty()) {
            Text(app.animego.inc.ui.uiText("permintaan pertemanan"), style = MaterialTheme.typography.titleMedium)
            requests.forEach { request ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${uiText("dari")} ${request.fromUid.take(12)}")
                    Row {
                        TextButton(onClick = { scope.launch { runCatching { graph.social.respondFriendRequest(request.id, true) }.onFailure { status = it.message.orEmpty() }; refresh() } }) { Text(app.animego.inc.ui.uiText("terima")) }
                        TextButton(onClick = { scope.launch { runCatching { graph.social.respondFriendRequest(request.id, false) }.onFailure { status = it.message.orEmpty() }; refresh() } }) { Text(app.animego.inc.ui.uiText("tolak")) }
                    }
                }
            }
        }
        if (signed && friends.isNotEmpty()) {
            Text(app.animego.inc.ui.uiText("teman"), style = MaterialTheme.typography.titleMedium)
            friends.forEach { user ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UserAvatar(graph, user.uid, user.displayName, 40.dp)
                    Column(Modifier.weight(1f)) { Text(user.displayName); Text("@${user.username}", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { scope.launch { runCatching { onChat(graph.social.createConversation(user.uid)) }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("chat")) }
                        TextButton(onClick = { scope.launch { runCatching { graph.social.removeFriend(user.uid) }.onSuccess { status = "pertemanan dihapus"; refresh() }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("hapus")) }
                    }
                }
            }
        }
        Text(app.animego.inc.ui.uiText("hasil pencarian"), style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(users, key = { it.uid }) { user ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            UserAvatar(graph, user.uid, user.displayName, 42.dp)
                            Column { Text(user.displayName, style = MaterialTheme.typography.titleMedium); Text("@${user.username}", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (user.bio.isNotBlank()) Text(user.bio)
                        Text("${user.followerCount} ${uiText("pengikut")} · ${user.followingCount} ${uiText("mengikuti")}", style = MaterialTheme.typography.labelSmall)
                        if (signed) Row {
                            TextButton(onClick = { scope.launch { runCatching { graph.social.follow(user.uid) }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("ikuti")) }
                            TextButton(onClick = { scope.launch { runCatching { graph.social.sendFriendRequest(user.uid) }.onSuccess { status = "permintaan dikirim" }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("tambah teman")) }
                            TextButton(onClick = { scope.launch { runCatching { graph.social.block(user.uid) }.onSuccess { status = "pengguna diblokir"; refresh() }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("blokir")) }
                            TextButton(onClick = { scope.launch { runCatching { graph.social.report("user", user.uid, "laporan pengguna") }.onSuccess { status = "laporan dikirim" }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("laporkan")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(graph: AppGraph, conversationId: String, onBack: () -> Unit, onOpenAnime: (String) -> Unit, onOpenEpisode: (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<app.animego.inc.core.data.Anime>>(emptyList()) }
    var peer by remember { mutableStateOf<app.animego.inc.core.firebase.LocalConversation?>(null) }
    var lastReadMessageId by remember(conversationId) { mutableStateOf("") }
    val mentionQuery = text.takeIf { it.startsWith("@") }?.drop(1)?.substringBefore("/").orEmpty()
    LaunchedEffect(mentionQuery) {
        if (mentionQuery.length >= 2) {
            kotlinx.coroutines.delay(300)
            suggestions = runCatching { graph.api.suggest(mentionQuery, 5) }.getOrDefault(emptyList())
        } else suggestions = emptyList()
    }
    fun refresh() {
        scope.launch {
            messages = runCatching { graph.social.getLocalMessages(conversationId) }.getOrDefault(emptyList())
            runCatching { graph.social.getMessages(conversationId) }.onSuccess { messages = it }
                .onFailure { status = it.message.orEmpty() }
        }
    }
    DisposableEffect(conversationId) {
        val listener = graph.social.observeMessages(conversationId, { data ->
            messages = data
            val latestIncoming = data.lastOrNull { it.senderId != graph.auth.currentUser?.uid }?.id.orEmpty()
            if (latestIncoming.isNotBlank() && latestIncoming != lastReadMessageId) {
                lastReadMessageId = latestIncoming
                scope.launch { runCatching { graph.social.markConversationRead(conversationId, force = true) } }
            }
        }, { status = it })
        onDispose { graph.social.removeMessagesListener(listener) }
    }
    LaunchedEffect(conversationId) {
        peer = runCatching { graph.database.getConversations(graph.auth.currentUser?.uid.orEmpty()).firstOrNull { it.id == conversationId } }.getOrNull()
        messages = runCatching { graph.social.getLocalMessages(conversationId) }.getOrDefault(emptyList())
        val latestIncoming = messages.lastOrNull { it.senderId != graph.auth.currentUser?.uid }?.id.orEmpty()
        if (latestIncoming.isNotBlank() && latestIncoming != lastReadMessageId) {
            lastReadMessageId = latestIncoming
            runCatching { graph.social.markConversationRead(conversationId, force = true) }
        }
        refresh()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!peer?.otherUid.isNullOrBlank()) UserAvatar(graph, peer!!.otherUid, peer!!.otherDisplayName, 38.dp)
                Column {
                    Text(peer?.otherDisplayName?.ifBlank { app.animego.inc.ui.uiText("pesan") } ?: app.animego.inc.ui.uiText("pesan"), style = MaterialTheme.typography.titleMedium)
                    if (!peer?.otherUsername.isNullOrBlank()) Text("@${peer?.otherUsername}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onBack) { Text(app.animego.inc.ui.uiText("kembali")) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false) {
            items(messages, key = { it.id }) { msg ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        when (msg.type) {
                            "anime", "episode" -> {
                                Text(if (msg.type == "episode") "${uiText("berbagi episode")} ${msg.episodeNumber}" else uiText("berbagi anime"))
                                if (msg.coverUrl.isNotBlank()) AsyncImage(model = msg.coverUrl, contentDescription = msg.animeTitle, modifier = Modifier.fillMaxWidth().height(140.dp))
                                Text(msg.animeTitle, style = MaterialTheme.typography.titleMedium)
                                if (msg.type == "episode") TextButton(onClick = {
                                    scope.launch {
                                        val number = msg.episodeNumber.toIntOrNull() ?: 1
                                        val detail = graph.api.getDetail(msg.animeSlug)
                                        val index = detail?.episodes?.indexOfFirst { ep -> Regex("\\d+").find(ep.name)?.value?.toIntOrNull() == number } ?: -1
                                        if (index >= 0) onOpenEpisode(msg.animeSlug, index) else status = "episode tidak ditemukan di katalog"
                                    }
                                }) { Text(app.animego.inc.ui.uiText("buka episode")) }
                                else TextButton(onClick = { onOpenAnime(msg.animeSlug) }) { Text(app.animego.inc.ui.uiText("buka anime")) }
                            }
                            else -> Text(msg.text)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (msg.deliveryState == "sent") msg.senderId.take(10) else "${msg.senderId.take(10)} · ${uiText(msg.deliveryState)}", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { scope.launch { runCatching { graph.social.report("message", msg.id, "laporan pesan") }.onSuccess { status = "laporan dikirim" }.onFailure { status = it.message.orEmpty() } } }) { Text(app.animego.inc.ui.uiText("laporkan")) }
                        }
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(uiText(status), color = MaterialTheme.colorScheme.error)
        if (suggestions.isNotEmpty()) {
            Text(app.animego.inc.ui.uiText("bagikan anime"), style = MaterialTheme.typography.labelLarge)
            suggestions.forEach { anime ->
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            val episodeNumber = text.substringAfterLast('/', "").takeIf { it.all { ch -> ch.isDigit() } && it.isNotBlank() }
                            if (episodeNumber == null) graph.social.sendAnimeMessage(conversationId, anime.slug, anime.title, anime.cover)
                            else {
                                val detail = graph.api.getDetail(anime.slug)
                                val ep = detail?.episodes?.getOrNull(episodeNumber.toIntOrNull()?.minus(1) ?: -1)
                                if (ep != null) graph.social.sendAnimeMessage(conversationId, anime.slug, anime.title, anime.cover, "${anime.slug}#$episodeNumber", episodeNumber)
                                else error("episode tidak ditemukan")
                            }
                        }.onSuccess { text = ""; suggestions = emptyList(); messages = graph.social.getLocalMessages(conversationId) }.onFailure { status = it.message.orEmpty() }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("${anime.title} · @${anime.slug}") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), label = { Text(app.animego.inc.ui.uiText("pesan")) }, maxLines = 3)
            Button(onClick = {
                scope.launch { busy = true; runCatching { graph.social.sendTextMessage(conversationId, text) }
                    .onSuccess { text = ""; messages = graph.social.getLocalMessages(conversationId) }
                    .onFailure { status = it.message.orEmpty() }; busy = false }
            }, enabled = text.isNotBlank() && !busy) { Text(app.animego.inc.ui.uiText("kirim")) }
        }
    }
}
