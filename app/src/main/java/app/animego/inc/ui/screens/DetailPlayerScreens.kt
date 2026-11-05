package app.animego.inc.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.animego.inc.AppGraph
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.Episode
import app.animego.inc.core.download.DownloadScheduler
import app.animego.inc.core.download.DownloadStorage
import app.animego.inc.core.player.FloatingPlayerService
import app.animego.inc.core.player.PlaybackEngine
import app.animego.inc.core.player.PlaybackService
import app.animego.inc.ui.components.EmptyState
import app.animego.inc.ui.components.LoadingState
import app.animego.inc.ui.components.ScreenHeader
import app.animego.inc.ui.components.formatClock
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.PictureInPicture2
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DetailScreen(graph: AppGraph, slug: String, onBack: () -> Unit, onPlay: (String, Int) -> Unit, onOpenGenre: (String) -> Unit) {
    val context = LocalContext.current
    var anime by remember { mutableStateOf<Anime?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var episodeQuery by remember { mutableStateOf("") }

    LaunchedEffect(slug) {
        loading = true
        try {
            anime = graph.api.getDetail(slug)
            if (anime == null) error = "anime tidak ditemukan" else {
                favorite = graph.database.isFavorite(anime!!.slug)
                error = ""
            }
        } catch (e: Exception) { error = e.message ?: "gagal mengambil detail anime" }
        finally { loading = false }
    }

    when {
        loading -> LoadingState("memuat detail anime...")
        anime == null -> EmptyState("detail tidak tersedia", error)
        else -> {
            val item = anime!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "kembali") }
                    Text("detail anime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(128.dp).aspectRatio(0.70f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (item.cover.isNotBlank()) AsyncImage(item.cover, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.type.ifBlank { "anime" }.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(listOf(item.year, item.status, item.score.takeIf { it.isNotBlank() }?.let { "★ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (item.episodes.isNotEmpty()) onPlay(item.slug, 0) }, enabled = item.episodes.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("putar")
                            }
                            OutlinedButton(onClick = {
                                favorite = !favorite
                                if (favorite) graph.database.saveFavorite(item.slug, item.title, item.cover) else graph.database.removeFavorite(item.slug)
                            }) { Icon(Lucide.Heart, contentDescription = "favorit", tint = if (favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                val genres = item.genreText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (genres.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { genre -> FilterChip(selected = false, onClick = { onOpenGenre(genre) }, label = { Text(genre) }) }
                    }
                }
                Text("sinopsis", Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(item.synopsis.ifBlank { "sinopsis tidak tersedia." }, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("informasi", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        "status" to item.status,
                        "tipe" to item.type,
                        "tahun tayang" to item.year,
                        "durasi" to item.duration,
                        "judul jepang" to item.japanese,
                        "episode tersedia" to item.episodes.size.toString()
                    ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("episode", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${item.episodes.size} episode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
                if (item.episodes.isNotEmpty()) {
                    OutlinedTextField(
                        value = episodeQuery,
                        onValueChange = { episodeQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        singleLine = true,
                        placeholder = { Text("filter episode") }
                    )
                }
                if (item.episodes.isEmpty()) EmptyState("episode belum tersedia", "server belum mengirimkan daftar episode untuk anime ini")
                item.episodes.forEachIndexed { index, episode ->
                    if (episodeQuery.isBlank() || episode.name.contains(episodeQuery, ignoreCase = true)) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onPlay(item.slug, index) }) {
                                Text(episode.name, fontWeight = FontWeight.SemiBold)
                                Text("sentuh untuk memutar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onPlay(item.slug, index) }) { Icon(Lucide.Play, contentDescription = "putar episode") }
                            IconButton(onClick = {
                                try {
                                    val id = DownloadStorage.queue(context, graph.database, item, episode, graph.api.cookieHeader(episode.url))
                                    DownloadScheduler.enqueue(context, id)
                                    message = "${episode.name} masuk antrean unduhan"
                                } catch (e: Exception) { message = e.message ?: "gagal menambahkan unduhan" }
                            }) { Icon(Lucide.Download, contentDescription = "unduh episode") }
                        }
                    }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun PlayerScreen(
    graph: AppGraph,
    slug: String,
    index: Int,
    source: String,
    title: String,
    episode: String,
    cover: String,
    initialPosition: Long,
    offline: Boolean,
    onBack: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { PlaybackEngine.player(context) }
    var anime by remember { mutableStateOf<Anime?>(null) }
    var activeEpisode by remember { mutableStateOf(episode) }
    var activeTitle by remember { mutableStateOf(title) }
    var activeCover by remember { mutableStateOf(cover) }
    var activeSlug by remember { mutableStateOf(slug) }
    var activeSource by remember { mutableStateOf(source) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(index) }
    var positionApplied by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(source.isBlank()) }
    var error by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var currentPosition by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(player.volume == 0f) }
    var fullscreen by remember { mutableStateOf(false) }
    var fullscreenPortrait by remember { mutableStateOf(false) }
    var lastAutoNextIndex by remember { mutableStateOf(-1) }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    LaunchedEffect(slug, index, source, offline) {
        if (source.isNotBlank()) {
            activeSource = if (offline && !source.startsWith("file:")) Uri.fromFile(File(source)).toString() else source
            activeTitle = title.ifBlank { "video tersimpan" }
            activeEpisode = episode.ifBlank { "video" }
            activeCover = cover
            activeSlug = slug
            currentIndex = index
            loading = false
        } else if (slug.isNotBlank()) {
            loading = true
            try {
                anime = graph.api.getDetail(slug)
                val detail = anime ?: throw IllegalStateException("anime tidak ditemukan")
                episodes = detail.episodes
                currentIndex = index.coerceIn(0, (detail.episodes.size - 1).coerceAtLeast(0))
                val selected = detail.episodes.getOrNull(currentIndex) ?: throw IllegalStateException("episode tidak ditemukan")
                activeTitle = detail.title
                activeEpisode = selected.name
                activeCover = detail.cover
                activeSlug = detail.slug
                activeSource = selected.url
                if (selected.url.isBlank()) throw IllegalStateException("tautan video kosong")
            } catch (e: Exception) { error = e.message ?: "gagal menyiapkan video" }
            finally { loading = false }
        }
    }

    LaunchedEffect(slug, source) {
        if (source.isNotBlank() && slug.isNotBlank()) {
            runCatching { graph.api.getDetail(slug) }.onSuccess { detail ->
                if (detail != null) {
                    anime = detail
                    episodes = detail.episodes
                    if (currentIndex < 0) {
                        currentIndex = detail.episodes.indexOfFirst { it.name == activeEpisode }.coerceAtLeast(0)
                    }
                    if (activeTitle.isBlank()) activeTitle = detail.title
                    if (activeCover.isBlank()) activeCover = detail.cover
                    if (activeSlug.isBlank()) activeSlug = detail.slug
                }
            }
        }
    }

    LaunchedEffect(activeSource) {
        if (activeSource.isNotBlank()) {
            lastAutoNextIndex = -1
            val sourceUri = if (offline && !activeSource.startsWith("file:")) Uri.fromFile(File(activeSource)).toString() else activeSource
            val resumePosition = if (positionApplied) 0L else initialPosition
            PlaybackEngine.play(context, sourceUri, activeTitle, activeEpisode, activeSlug, activeCover, resumePosition, offline)
            positionApplied = true
            error = ""
        }
    }

    DisposableEffect(player, episodes, currentIndex, offline, activeTitle, activeSlug, activeCover) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = (player.duration.takeIf { it > 0 } ?: 0L).toFloat()
                if (playbackState == Player.STATE_ENDED && !offline && currentIndex in 0 until episodes.lastIndex && lastAutoNextIndex != currentIndex) {
                    lastAutoNextIndex = currentIndex
                    val next = episodes[currentIndex + 1]
                    if (next.url.isNotBlank()) {
                        currentIndex += 1
                        activeEpisode = next.name
                        activeSource = next.url
                        PlaybackEngine.play(context, next.url, activeTitle, next.name, activeSlug, activeCover, 0L, false)
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (!offline && activeSlug.isNotBlank() && activeSource.isNotBlank()) {
                val pos = player.currentPosition.toDouble() / 1000.0
                val dur = (player.duration.coerceAtLeast(0L)).toDouble() / 1000.0
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    graph.database.updateProgress(activeSlug, activeTitle, activeEpisode, activeCover, activeSource, pos, dur)
                }
            }
        }
    }

    LaunchedEffect(activeSource) {
        while (isActive) {
            currentPosition = player.currentPosition.coerceAtLeast(0L).toFloat()
            duration = player.duration.takeIf { it > 0 }?.toFloat() ?: 0f
            speed = player.playbackParameters.speed
            delay(500L)
        }
    }

    DisposableEffect(fullscreen, fullscreenPortrait) {
        val activity = context as? Activity
        if (activity != null) {
            if (fullscreen) {
                activity.requestedOrientation = if (fullscreenPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (error.isNotBlank()) Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(18.dp))
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(activeTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(activeEpisode, color = Color.LightGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                IconButton(onClick = { fullscreen = false }) {
                    Icon(Lucide.X, contentDescription = "keluar fullscreen", tint = Color.White)
                }
            }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.align(Alignment.Center)) {
                Icon(if (playing) Lucide.Pause else Lucide.Play, contentDescription = "putar atau jeda", tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC090910)).padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Slider(
                    value = if (duration > 0) (currentPosition / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) currentPosition = it * duration },
                    onValueChangeFinished = { player.seekTo(currentPosition.toLong()) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(currentPosition / 1000.0), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    Text(formatClock(duration / 1000.0), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { player.seekBack() }) { Icon(Lucide.SkipBack, contentDescription = "mundur 10 detik", tint = Color.White) }
                    IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                        Icon(if (playing) Lucide.Pause else Lucide.Play, contentDescription = "putar atau jeda", tint = Color.White)
                    }
                    IconButton(onClick = { player.seekForward() }) { Icon(Lucide.SkipForward, contentDescription = "maju 10 detik", tint = Color.White) }
                    IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                        Icon(if (muted) Lucide.VolumeX else Lucide.Volume2, contentDescription = "mute", tint = Color.White)
                    }
                    TextButton(onClick = {
                        val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                        val next = rates[(rates.indexOf(speed).coerceAtLeast(0) + 1) % rates.size]
                        player.setPlaybackSpeed(next)
                        speed = next
                    }) { Text("${speed}×", color = Color.White) }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Color(0xFF090910)).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "kembali", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(activeTitle.ifBlank { "AnimeGo player" }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(activeEpisode, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { fullscreenPortrait = false; fullscreen = true }) { Icon(Lucide.Maximize, contentDescription = "fullscreen landscape", tint = Color.White) }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update = { it.player = player }, modifier = Modifier.fillMaxSize())
                if (loading) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error, color = Color.White, modifier = Modifier.padding(18.dp))
            }
            if (!loading && error.isBlank()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekBack() }) { Icon(Lucide.SkipBack, contentDescription = "mundur 10 detik", tint = Color.White) }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(54.dp)) {
                            Icon(if (playing) Lucide.Pause else Lucide.Play, contentDescription = "play pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = { player.seekForward() }) { Icon(Lucide.SkipForward, contentDescription = "maju 10 detik", tint = Color.White) }
                        IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                            Icon(if (muted) Lucide.VolumeX else Lucide.Volume2, contentDescription = if (muted) "aktifkan suara" else "senyapkan", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                            } else FloatingPlayerService.start(context)
                        }) { Icon(Lucide.PictureInPicture2, contentDescription = "floating player", tint = Color.White) }
                    }
                    Slider(value = if (duration > 0) (currentPosition / duration).coerceIn(0f, 1f) else 0f, onValueChange = { if (duration > 0) currentPosition = it * duration }, onValueChangeFinished = { player.seekTo(currentPosition.toLong()) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatClock(currentPosition / 1000.0), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        Text(formatClock(duration / 1000.0), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                            FilterChip(selected = speed == rate, onClick = { player.setPlaybackSpeed(rate) }, label = { Text("${rate}×") })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenDetail, enabled = activeSlug.isNotBlank(), modifier = Modifier.weight(1f)) { Icon(Lucide.Info, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("detail") }
                        OutlinedButton(onClick = { if (currentIndex > 0) playEpisode(currentIndex - 1, episodes, context, activeTitle, activeSlug, activeCover, onUpdate = { idx, ep, url -> currentIndex = idx; activeEpisode = ep; activeSource = url }) }, enabled = currentIndex > 0 && episodes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("sebelumnya") }
                        OutlinedButton(onClick = { if (currentIndex >= 0 && currentIndex < episodes.lastIndex) playEpisode(currentIndex + 1, episodes, context, activeTitle, activeSlug, activeCover, onUpdate = { idx, ep, url -> currentIndex = idx; activeEpisode = ep; activeSource = url }) }, enabled = currentIndex >= 0 && currentIndex < episodes.lastIndex, modifier = Modifier.weight(1f)) { Text("berikutnya") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fullscreenPortrait = true; fullscreen = true }, modifier = Modifier.weight(1f)) { Text("fullscreen portrait") }
                        OutlinedButton(onClick = { fullscreenPortrait = false; fullscreen = true }, modifier = Modifier.weight(1f)) { Text("fullscreen landscape") }
                    }
                    if (!offline && episodes.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("daftar episode", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${episodes.size} episode", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        }
                        val animeForDownload = anime
                        episodes.forEachIndexed { epIndex, ep ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                    if (epIndex != currentIndex) playEpisode(epIndex, episodes, context, activeTitle, activeSlug, activeCover,
                                        onUpdate = { idx, name, url -> currentIndex = idx; activeEpisode = name; activeSource = url })
                                },
                                colors = CardDefaults.cardColors(containerColor = if (epIndex == currentIndex) Color(0xFF302542) else Color(0xFF171720)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ep.name, color = Color.White, fontWeight = if (epIndex == currentIndex) FontWeight.Bold else FontWeight.Medium)
                                        if (epIndex == currentIndex) Text("sedang diputar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = {
                                        val item = animeForDownload
                                        if (item != null) {
                                            runCatching {
                                                val downloadId = DownloadStorage.queue(context, graph.database, item, ep, graph.api.cookieHeader(ep.url))
                                                DownloadScheduler.enqueue(context, downloadId)
                                                Toast.makeText(context, "episode masuk antrean unduhan", Toast.LENGTH_SHORT).show()
                                            }.onFailure { Toast.makeText(context, it.message ?: "gagal mengunduh", Toast.LENGTH_SHORT).show() }
                                        }
                                    }, enabled = animeForDownload != null) { Icon(Lucide.Download, contentDescription = "unduh episode", tint = Color.White) }
                                }
                            }
                        }
                    }
                    if (!offline && activeSource.isNotBlank()) {
                        Text("buka tautan stream", Modifier.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activeSource))) }
                        }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private fun playEpisode(
    index: Int, episodes: List<Episode>, context: Context,
    title: String, slug: String, cover: String,
    onUpdate: (Int, String, String) -> Unit
) {
    val episode = episodes.getOrNull(index) ?: return
    if (episode.url.isBlank()) return
    PlaybackEngine.play(context, episode.url, title, episode.name, slug, cover, 0L, false)
    onUpdate(index, episode.name, episode.url)
}
