package app.animego.inc.ui.screens
import app.animego.inc.ui.uiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.work.WorkManager
import app.animego.inc.AppGraph
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.DownloadRecord
import app.animego.inc.core.data.HistoryRecord
import app.animego.inc.core.download.DownloadScheduler
import app.animego.inc.core.download.DownloadStorage
import app.animego.inc.ui.components.AnimeListCard
import app.animego.inc.ui.components.EmptyState
import app.animego.inc.ui.components.ScreenHeader
import app.animego.inc.ui.components.formatClock
import java.io.File

@Composable
fun FavoritesScreen(graph: AppGraph, onOpenAnime: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf(emptyList<Anime>()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        favorites = graph.database.getFavorites(graph.auth.currentUser?.uid ?: "local").map { Anime(slug = it.slug, title = it.title, cover = it.cover) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(app.animego.inc.ui.uiText("favorit"), "anime yang lu simpan")
        if (favorites.isEmpty()) EmptyState(app.animego.inc.ui.uiText("belum ada favorit"), "buka detail anime lalu tekan tombol hati untuk menyimpannya")
        else LazyColumn {
            items(favorites, key = { it.slug }) { anime ->
                AnimeListCard(anime, onClick = { onOpenAnime(anime.slug) }, trailing = {
                    TextButton(onClick = { scope.launch { graph.sync.removeFavorite(anime.slug); reload++ } }) { Text(app.animego.inc.ui.uiText("hapus")) }
                })
            }
        }
    }
}

@Composable
fun HistoryScreen(graph: AppGraph, onResume: (HistoryRecord) -> Unit) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(emptyList<HistoryRecord>()) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { history = graph.database.getHistory(graph.auth.currentUser?.uid ?: "local") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(app.animego.inc.ui.uiText("riwayat"), "lanjutkan dari posisi terakhir", action = {
            if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(app.animego.inc.ui.uiText("bersihkan")) }
        })
        if (history.isEmpty()) EmptyState(app.animego.inc.ui.uiText("riwayat masih kosong"), app.animego.inc.ui.uiText("anime yang lu tonton akan muncul di sini"))
        else LazyColumn {
            items(history, key = { it.slug }) { row ->
                val progress = if (row.duration > 0) (row.position / row.duration * 100).toInt().coerceIn(0, 100) else 0
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = { onResume(row) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontWeight = FontWeight.SemiBold)
                            Text("${row.ep} · ${formatClock(row.position)} / ${formatClock(row.duration)} · $progress%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { scope.launch { graph.sync.removeHistory(row.slug); reload++ } }) { Text(app.animego.inc.ui.uiText("hapus")) }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(app.animego.inc.ui.uiText("bersihkan riwayat?")) },
        text = { Text(app.animego.inc.ui.uiText("semua data progres menonton akan dihapus.")) },
        confirmButton = { TextButton(onClick = { scope.launch { graph.sync.clearHistory(); reload++; confirmClear = false } }) { Text(app.animego.inc.ui.uiText("bersihkan")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(app.animego.inc.ui.uiText("batal")) } }
    )
}

@Composable
fun DownloadsScreen(graph: AppGraph, onPlayOffline: (DownloadRecord) -> Unit) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf(emptyList<DownloadRecord>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    LaunchedEffect(refresh) {
        while (true) {
            downloads = graph.database.getDownloads()
            kotlinx.coroutines.delay(1200)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(app.animego.inc.ui.uiText("unduhan"), app.animego.inc.ui.uiText("video offline dan antrean download"))
        if (downloads.isEmpty()) EmptyState(app.animego.inc.ui.uiText("belum ada unduhan"), app.animego.inc.ui.uiText("tambahkan episode dari halaman detail anime"))
        else LazyColumn {
            items(downloads, key = { it.id }) { row ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(row.title, fontWeight = FontWeight.SemiBold)
                        Text(row.ep, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(downloadStatus(row), style = MaterialTheme.typography.labelMedium, color = when (row.status) {
                            "completed" -> MaterialTheme.colorScheme.primary
                            "failed", "missing" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }, modifier = Modifier.padding(top = 6.dp))
                        if (row.status == "downloading" || row.status == "queued") {
                            LinearProgressIndicator(progress = { (row.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        if (row.error.isNotBlank()) Text(row.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (row.status) {
                                "completed" -> Button(onClick = { if (File(row.path).isFile) onPlayOffline(row) else refresh++ }, enabled = File(row.path).isFile) { Text(app.animego.inc.ui.uiText("putar offline")) }
                                "downloading", "queued" -> OutlinedButton(onClick = {}, enabled = false) { Text(app.animego.inc.ui.uiText(if (row.status == "downloading") "sedang diunduh" else "dalam antrean")) }
                                else -> OutlinedButton(onClick = { graph.database.retryDownload(row.id); DownloadScheduler.enqueue(context, row.id); refresh++ }) {
                                    Text(app.animego.inc.ui.uiText("coba lagi"))
                                }
                            }
                            TextButton(onClick = { confirmDelete = row }) { Text(app.animego.inc.ui.uiText("hapus")) }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { row -> AlertDialog(
        onDismissRequest = { confirmDelete = null }, title = { Text(app.animego.inc.ui.uiText("hapus unduhan?")) },
        text = { Text(app.animego.inc.ui.uiTextFormat("file video dan catatan unduhan", row.title, row.ep)) },
        confirmButton = { TextButton(onClick = {
            WorkManager.getInstance(context).cancelAllWorkByTag("animego_download_${row.id}")
            DownloadStorage.delete(row.path)
            graph.database.removeDownload(row.id)
            confirmDelete = null
            refresh++
        }) { Text(app.animego.inc.ui.uiText("hapus")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(app.animego.inc.ui.uiText("batal")) } }
    ) }
}

private fun downloadStatus(row: DownloadRecord): String = when (row.status) {
    "queued" -> "menunggu antrean"
    "downloading" -> "mengunduh ${row.percent}% · ${formatBytes(row.progress)}${if (row.total > 0) " / ${formatBytes(row.total)}" else ""}"
    "completed" -> "selesai · ${formatBytes(row.total.takeIf { it > 0 } ?: row.progress)}"
    "failed" -> "gagal"
    "missing" -> "file tidak ditemukan"
    else -> row.status
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
