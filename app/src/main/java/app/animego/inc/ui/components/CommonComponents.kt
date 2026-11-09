package app.animego.inc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.HistoryRecord
import coil3.compose.AsyncImage
import app.animego.inc.core.player.PlaybackSnapshot
import app.animego.inc.ui.uiText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.X
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(uiText(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(uiText(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) action()
    }
}

@Composable
fun LoadingState(label: String = "memuat data...") {
    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(uiText(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(uiText(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(uiText(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(uiText(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!trailing.isNullOrBlank()) Text(uiText(trailing), Modifier.clickable { onTrailing?.invoke() }.padding(6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AnimeRail(title: String, items: List<Anime>, onOpen: (String) -> Unit, action: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    SectionTitle(title, "lihat semua", action)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
        items(items, key = { if (it.slug.isNotBlank()) it.slug else it.id.toString() }) { anime -> AnimePosterCard(anime, onClick = { onOpen(anime.slug) }) }
    }
}

@Composable
fun AnimePosterCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(134.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.70f).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (anime.cover.isNotBlank()) {
                AsyncImage(anime.cover, contentDescription = anime.title, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
            }
            if (anime.score.isNotBlank()) {
                Card(modifier = Modifier.align(Alignment.TopEnd).padding(7.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), shape = RoundedCornerShape(8.dp)) {
                    Text("★ ${anime.score}", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(anime.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(metaLine(anime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AnimeListCard(anime: Anime, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(76.dp).aspectRatio(0.70f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (anime.cover.isNotBlank()) AsyncImage(anime.cover, contentDescription = anime.title, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(metaLine(anime), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (anime.genreText.isNotBlank()) Text(anime.genreText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun HistoryStrip(items: List<HistoryRecord>, onResume: (HistoryRecord) -> Unit, onHistory: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    SectionTitle(app.animego.inc.ui.uiText("lanjutkan menonton"), "riwayat", onHistory)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
        items(items.take(10), key = { it.slug }) { item ->
            Column(Modifier.width(158.dp).clickable { onResume(item) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (item.cover.isNotBlank()) AsyncImage(item.cover, contentDescription = item.title, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                    val progress = if (item.duration > 0) (item.position / item.duration).toFloat().coerceIn(0f, 1f) else 0f
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.primary))
                }
                Spacer(Modifier.height(7.dp))
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.ep} · ${formatClock(item.position)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun metaLine(anime: Anime): String = listOfNotNull(
    anime.type.takeIf { it.isNotBlank() },
    anime.year.takeIf { it.isNotBlank() },
    anime.episode.takeIf { it > 0 }?.let { "$it ep" },
    anime.status.takeIf { it.isNotBlank() }
).joinToString(" · ").ifBlank { "anime" }

fun formatClock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}


@Composable
fun PlaybackMiniPlayer(
    playback: PlaybackSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onMuteToggle: () -> Unit,
    onSpeedCycle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    canPreviousEpisode: Boolean,
    canNextEpisode: Boolean
) {
    var scrubProgress by remember(playback.source) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(0L)
    val actualProgress = if (duration > 0L) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.size(width = 48.dp, height = 42.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
                    if (playback.cover.isNotBlank()) {
                        AsyncImage(playback.cover, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                    Text(playback.title.ifBlank { "AnimeGo" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(playback.episode.ifBlank { if (playback.offline) app.animego.inc.ui.uiText("video offline") else app.animego.inc.ui.uiText("sedang diputar") }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onSkipBack) {
                    Icon(Lucide.SkipBack, contentDescription = app.animego.inc.ui.uiText("mundur 10 detik"))
                }
                IconButton(onClick = onToggle) {
                    Icon(if (playback.isPlaying) Lucide.Pause else Lucide.Play, contentDescription = if (playback.isPlaying) "jeda" else "putar")
                }
                IconButton(onClick = onSkipForward) {
                    Icon(Lucide.SkipForward, contentDescription = app.animego.inc.ui.uiText("maju 10 detik"))
                }
                IconButton(onClick = onClose) { Icon(Lucide.X, contentDescription = app.animego.inc.ui.uiText("tutup pemutar")) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(playback.positionMs / 1000.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 6.dp),
                    value = scrubProgress ?: actualProgress,
                    onValueChange = { scrubProgress = it },
                    onValueChangeFinished = {
                        scrubProgress?.let { onSeek((duration * it).toLong()) }
                        scrubProgress = null
                    },
                    enabled = duration > 0L
                )
                Text(formatClock(playback.durationMs / 1000.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPreviousEpisode, enabled = canPreviousEpisode) { Text(app.animego.inc.ui.uiText("episode −")) }
                TextButton(onClick = onNextEpisode, enabled = canNextEpisode) { Text(app.animego.inc.ui.uiText("episode +")) }
                IconButton(onClick = onMuteToggle) {
                    Icon(if (playback.volume <= 0f) Lucide.VolumeX else Lucide.Volume2, contentDescription = if (playback.volume <= 0f) "aktifkan suara" else "mute")
                }
                TextButton(onClick = onSpeedCycle) { Text("${playback.speed}×") }
                TextButton(onClick = onOpen) { Text(app.animego.inc.ui.uiText("buka")) }
            }
        }
    }
}
