package app.animego.inc.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.AnimePage
import app.animego.inc.core.data.Genre
import app.animego.inc.ui.components.AnimeListCard
import app.animego.inc.ui.components.EmptyState
import app.animego.inc.ui.components.LoadingState
import app.animego.inc.ui.components.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val browseFilters = listOf(
    "latest" to "terbaru", "ongoing" to "sedang tayang", "completed" to "selesai",
    "movies" to "film", "top" to "populer", "random" to "acak", "oldest" to "terlama"
)

@Composable
fun BrowseScreen(graph: AppGraph, onOpenAnime: (String) -> Unit, onOpenGenres: () -> Unit, onOpenSchedule: () -> Unit) {
    var kind by remember { mutableStateOf("latest") }
    var page by remember { mutableStateOf(1) }
    var data by remember { mutableStateOf<AnimePage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(kind, page) {
        loading = true
        try { data = graph.api.getList(kind, page, 12); error = "" }
        catch (e: Exception) { error = e.message ?: "gagal memuat katalog" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("jelajah", "pilih kategori untuk menyaring katalog")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            browseFilters.forEach { (value, label) ->
                FilterChip(selected = kind == value, onClick = { kind = value; page = 1 }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenGenres, modifier = Modifier.weight(1f)) { Text("genre") }
            Button(onClick = onOpenSchedule, modifier = Modifier.weight(1f)) { Text("jadwal rilis") }
        }
        when {
            loading -> LoadingState("memuat katalog...")
            error.isNotBlank() -> EmptyState("gagal memuat katalog", error)
            data?.items.isNullOrEmpty() -> EmptyState("belum ada anime", "coba kategori lain atau muat ulang nanti")
            else -> {
                LazyColumn(Modifier.weight(1f)) {
                    items(data!!.items, key = { it.slug }) { AnimeListCard(it, onClick = { onOpenAnime(it.slug) }) }
                    item {
                        PageControls(
                            page,
                            data?.totalPages ?: 1,
                            { page = (page - 1).coerceAtLeast(1) },
                            { page = (page + 1).coerceAtMost(data?.totalPages ?: page) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(graph: AppGraph, onOpenAnime: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var results by remember { mutableStateOf<AnimePage?>(null) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)
        suggestions = runCatching { graph.api.suggest(query.trim(), 6) }.getOrDefault(emptyList())
    }

    suspend fun searchNow(targetPage: Int = 1) {
        if (query.isBlank()) return
        keyboard?.hide()
        page = targetPage
        loading = true
        error = ""
        try { results = graph.api.search(query.trim(), targetPage, 24); suggestions = emptyList() }
        catch (e: Exception) { error = e.message ?: "pencarian gagal" }
        finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("cari anime", "judul, seri, atau kata kunci")
        OutlinedTextField(
            value = query, onValueChange = { query = it; results = null; page = 1 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("contoh: naruto, one piece...") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { scope.launch { searchNow() } })
        )
        Button(onClick = { scope.launch { searchNow() } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("cari") }
        if (suggestions.isNotEmpty()) {
            Text("saran pencarian", Modifier.padding(start = 18.dp, top = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            suggestions.forEach { anime ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), onClick = { onOpenAnime(anime.slug) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(anime.title, Modifier.padding(14.dp), fontWeight = FontWeight.Medium)
                }
            }
        }
        when {
            loading -> LoadingState("mencari...")
            error.isNotBlank() -> EmptyState("pencarian gagal", error)
            results != null && results!!.items.isEmpty() -> EmptyState("tidak ditemukan", "coba kata kunci yang berbeda")
            results != null -> LazyColumn(Modifier.weight(1f)) {
                items(results!!.items, key = { it.slug }) { AnimeListCard(it, onClick = { onOpenAnime(it.slug) }) }
                item {
                    PageControls(
                        page,
                        results!!.totalPages,
                        { scope.launch { searchNow((page - 1).coerceAtLeast(1)) } },
                        { scope.launch { searchNow(page + 1) } }
                    )
                }
            }
        }
    }
}

@Composable
fun GenreListScreen(graph: AppGraph, onOpenGenre: (String) -> Unit, onBack: () -> Unit = {}) {
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { genres = graph.api.getGenres() } catch (e: Exception) { error = e.message ?: "gagal memuat genre" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("genre anime", "temukan anime berdasarkan genre", action = { TextButton(onClick = onBack) { Text("kembali") } })
        when {
            loading -> LoadingState()
            error.isNotBlank() -> EmptyState("gagal memuat genre", error)
            else -> LazyColumn {
                items(genres, key = { it.id }) { genre ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { onOpenGenre(genre.slug) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(genre.name, fontWeight = FontWeight.SemiBold)
                                Text("${genre.count} anime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenreScreen(graph: AppGraph, slug: String, onOpenAnime: (String) -> Unit, onBack: () -> Unit) {
    var data by remember { mutableStateOf<Pair<Genre?, AnimePage>?>(null) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(slug, page) {
        loading = true
        try { data = graph.api.getGenre(slug, page, 24); error = "" } catch (e: Exception) { error = e.message ?: "gagal memuat genre" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(data?.first?.name ?: "genre", "katalog berdasarkan genre", action = { TextButton(onClick = onBack) { Text("kembali") } })
        when {
            loading -> LoadingState()
            error.isNotBlank() -> EmptyState("gagal memuat genre", error)
            data?.second?.items.isNullOrEmpty() -> EmptyState("belum ada anime", "genre ini belum memiliki hasil")
            else -> LazyColumn {
                items(data!!.second.items, key = { it.slug }) { AnimeListCard(it, onClick = { onOpenAnime(it.slug) }) }
                item { PageControls(page, data!!.second.totalPages, { page = (page - 1).coerceAtLeast(1) }, { page++ }) }
            }
        }
    }
}

@Composable
fun ScheduleScreen(graph: AppGraph, onOpenAnime: (String) -> Unit, onBack: () -> Unit) {
    val days = listOf("senin", "selasa", "rabu", "kamis", "jumat", "sabtu", "minggu")
    var day by remember { mutableStateOf(days.first()) }
    var items by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(day, page) {
        loading = true
        try {
            val result = graph.api.getSchedule(day, page, 24)
            items = result.items
            totalPages = result.totalPages
            error = ""
        }
        catch (e: Exception) { error = e.message ?: "gagal memuat jadwal" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("jadwal rilis", "pilih hari penayangan", action = { TextButton(onClick = onBack) { Text("kembali") } })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            days.forEach { value -> FilterChip(selected = day == value, onClick = { day = value; page = 1 }, label = { Text(value) }) }
        }
        when {
            loading -> LoadingState("memuat jadwal...")
            error.isNotBlank() -> EmptyState("jadwal tidak tersedia", error)
            items.isEmpty() -> EmptyState("belum ada jadwal", "belum ada data anime untuk hari ini")
            else -> LazyColumn {
                items(items, key = { it.slug }) { AnimeListCard(it, onClick = { onOpenAnime(it.slug) }) }
                item { PageControls(page, totalPages, { page = (page - 1).coerceAtLeast(1) }, { page++ }) }
            }
        }
    }
}

@Composable
private fun PageControls(page: Int, totalPages: Int, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = previous, enabled = page > 1) { Text("sebelumnya") }
        Text("$page / ${totalPages.coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = next, enabled = totalPages > 0 && page < totalPages) { Text("berikutnya") }
    }
}
