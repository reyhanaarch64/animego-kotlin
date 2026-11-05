package app.animego.inc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.AnimeHome
import app.animego.inc.core.data.HistoryRecord
import app.animego.inc.ui.components.AnimeRail
import app.animego.inc.ui.components.HistoryStrip
import app.animego.inc.ui.components.LoadingState
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings

@Composable
fun HomeScreen(
    graph: AppGraph,
    onOpenAnime: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeHistory: (HistoryRecord) -> Unit
) {
    var home by remember { mutableStateOf<AnimeHome?>(null) }
    var history by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        try {
            history = graph.database.getHistory()
            home = graph.api.getHome()
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "gagal memuat beranda"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ANIMEGO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                Text("jelajahi cerita berikutnya", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSettings) { Icon(Lucide.Settings, contentDescription = "pengaturan") }
        }
        if (loading) LoadingState("mengambil katalog AnimeGo...")
        if (error.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth().padding(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("gagal memuat data", fontWeight = FontWeight.Bold)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val data = home
        if (data != null && data.ongoing.items.isEmpty() && data.top.items.isEmpty() && data.movies.items.isEmpty() && data.completed.items.isEmpty()) {
            androidx.compose.material3.Text("katalog belum tersedia", Modifier.padding(horizontal = 20.dp, vertical = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (data != null) {
            val featured = data.top.items.firstOrNull() ?: data.ongoing.items.firstOrNull()
            if (featured != null) HeroCard(featured, onOpenAnime)
            HistoryStrip(history, onResume = onResumeHistory, onHistory = onOpenHistory)
            AnimeRail("sedang tayang", data.ongoing.items, onOpenAnime, onOpenBrowse)
            AnimeRail("populer", data.top.items, onOpenAnime, onOpenBrowse)
            AnimeRail("film", data.movies.items, onOpenAnime, onOpenBrowse)
            AnimeRail("sudah selesai", data.completed.items, onOpenAnime, onOpenBrowse)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroCard(anime: Anime, onOpenAnime: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(245.dp).clickable { onOpenAnime(anime.slug) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (anime.cover.isNotBlank()) AsyncImage(anime.cover, contentDescription = anime.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00B0B12)))))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("rekomendasi anime", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(anime.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(listOf(anime.type, anime.year, anime.score.takeIf { it.isNotBlank() }?.let { "★ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onOpenAnime(anime.slug) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text("lihat detail")
                }
            }
        }
    }
}
