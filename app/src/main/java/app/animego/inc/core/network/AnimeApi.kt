package app.animego.inc.core.network

import app.animego.inc.core.data.Anime
import app.animego.inc.core.data.AnimeHome
import app.animego.inc.core.data.AnimePage
import app.animego.inc.core.data.Episode
import app.animego.inc.core.data.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class AnimeApi {
    companion object {
        const val BASE = "https://karanime.com/wp-json/wp/v2"
        private const val ORIGIN = "https://karanime.com/"
        private const val LIST_FIELDS = "id,slug,title,date,modified,featured_media,link,animegenre,animetype,animestatus,meta_box.ero_image,meta_box.ero_episode,meta_box.ero_episodebaru,meta_box.ero_status,meta_box.ero_type,meta_box.ero_skor,meta_box.ero_tayang,meta_box.ero_sub,meta_box.ero_durasi,meta_box.ero_genreapp,meta_box.ero_japanese,meta_box.ero_trailer"
        private const val SUGGEST_FIELDS = "id,slug,title,featured_media,meta_box.ero_image,meta_box.ero_episode,meta_box.ero_episodebaru,meta_box.ero_skor,meta_box.ero_status"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 AnimeGo/2.0"
    }

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = (cookieStore[url.host].orEmpty() + cookies).distinctBy { it.name }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host].orEmpty().filter { it.matches(url) }
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var warmedUp = false
    @Volatile private var homeCache: AnimeHome? = null

    fun getCachedHome(): AnimeHome? = homeCache

    /** Cookie header for download requests, sharing the same native HTTP cookie jar as the API. */
    fun cookieHeader(rawUrl: String): String {
        val url = runCatching { rawUrl.toHttpUrl() }.getOrNull() ?: return ""
        return cookieJar.loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
    }

    private suspend fun request(path: String, params: Map<String, String> = emptyMap()): ApiResponse = withContext(Dispatchers.IO) {
        warmUpOrigin()
        val base = (if (path.startsWith("/")) BASE else "$BASE/") + path
        val urlBuilder = base.toHttpUrl().newBuilder()
        params.forEach { (key, value) -> if (value.isNotBlank()) urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        var last: Exception? = null
        var nonJsonBody = ""
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", ORIGIN)
                    .header("Origin", "https://karanime.com")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = "HTTP ${response.code}: ${body.compact()}"
                        if (response.code == 429 || response.code >= 500) throw RetryableApiException(message)
                        throw ApiException(message)
                    }
                    val normalized = normalizeJsonBody(body)
                    if (!looksLikeJson(normalized)) {
                        nonJsonBody = normalized
                        throw RetryableApiException("server mengembalikan respons non-json: ${normalized.compact()}")
                    }
                    return@withContext ApiResponse(
                        body = normalized,
                        total = response.header("X-WP-Total")?.toIntOrNull() ?: 0,
                        totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
                    )
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }
        if (nonJsonBody.isNotBlank()) {
            throw ApiException("API AnimeGo mengembalikan halaman non-JSON. WebView fallback sengaja tidak dipakai pada build native. ${nonJsonBody.compact()}")
        }
        throw ApiException(last?.message ?: "request API gagal")
    }

    private fun warmUpOrigin() {
        if (warmedUp) return
        synchronized(this) {
            if (warmedUp) return
            try {
                val req = Request.Builder().url(ORIGIN).header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml").build()
                client.newCall(req).execute().use { it.body?.close() }
            } catch (_: Exception) {
            } finally {
                warmedUp = true
            }
        }
    }

    suspend fun getHome(forceRefresh: Boolean = false): AnimeHome {
        if (!forceRefresh) homeCache?.let { return it }
        val loaded = kotlinx.coroutines.supervisorScope {
        val ongoing = async { runCatching { getList("ongoing", 1, 12) }.getOrDefault(AnimePage()) }
        val top = async { runCatching { getList("top", 1, 12) }.getOrDefault(AnimePage()) }
        val movies = async { runCatching { getList("movies", 1, 12) }.getOrDefault(AnimePage()) }
        val completed = async { runCatching { getList("completed", 1, 12) }.getOrDefault(AnimePage()) }
        AnimeHome(ongoing.await(), top.await(), movies.await(), completed.await())
        }
        val previous = homeCache
        val stable = if (previous == null) loaded else AnimeHome(
            ongoing = loaded.ongoing.takeIf { it.items.isNotEmpty() } ?: previous.ongoing,
            top = loaded.top.takeIf { it.items.isNotEmpty() } ?: previous.top,
            movies = loaded.movies.takeIf { it.items.isNotEmpty() } ?: previous.movies,
            completed = loaded.completed.takeIf { it.items.isNotEmpty() } ?: previous.completed
        )
        if (stable.ongoing.items.isNotEmpty() || stable.top.items.isNotEmpty() || stable.movies.items.isNotEmpty() || stable.completed.items.isNotEmpty()) homeCache = stable
        return stable
    }

    suspend fun getList(kind: String, page: Int = 1, limit: Int = 12): AnimePage {
        val p = baseListParams(page, limit).toMutableMap()
        when (kind) {
            "ongoing" -> p["animestatus"] = "2872"
            "completed" -> p["animestatus"] = "2883"
            "movies" -> p["animetype"] = "2916"
            "top" -> p["animetop"] = "2901"
            "random" -> p["orderby"] = "rand"
            "latest" -> { p["orderby"] = "modified"; p["order"] = "desc" }
            "oldest" -> { p["orderby"] = "date"; p["order"] = "asc" }
        }
        val r = request("/animes", p)
        return AnimePage(normalizeList(JSONArray(r.body)), r.total, r.totalPages)
    }

    suspend fun search(query: String, page: Int = 1, limit: Int = 12): AnimePage {
        val params = mapOf("search" to query, "page" to page.toString(), "per_page" to limit.toString(), "_fields" to LIST_FIELDS)
        val response = request("/animes", params)
        val raw = JSONArray(response.body)
        val normalized = normalizeTitle(query)
        val querySlug = slugify(query)
        val output = mutableListOf<Anime>()
        val seen = mutableSetOf<String>()
        if (page == 1 && querySlug.isNotBlank()) {
            try {
                val exact = JSONArray(request("/animes", mapOf("slug" to querySlug, "_fields" to LIST_FIELDS)).body)
                if (exact.length() > 0) {
                    val hit = normalizeAnime(exact.getJSONObject(0), true)
                    if (hit.slug.isNotBlank()) { output += hit; seen += hit.slug }
                }
            } catch (_: Exception) { }
        }
        val scored = (0 until raw.length()).map { normalizeAnime(raw.getJSONObject(it), false) }
            .filter { it.slug !in seen }
            .sortedByDescending { searchScore(it, normalized, querySlug) }
        output += scored
        return AnimePage(output, response.total, response.totalPages)
    }

    suspend fun suggest(query: String, limit: Int = 8): List<Anime> {
        if (query.isBlank()) return emptyList()
        val raw = JSONArray(request("/animes", mapOf(
            "search" to query, "page" to "1", "per_page" to limit.toString(), "_fields" to SUGGEST_FIELDS
        )).body)
        return (0 until raw.length()).map { normalizeAnime(raw.getJSONObject(it), false) }
            .filter { it.slug.isNotBlank() }.take(limit)
    }

    suspend fun getDetail(key: String): Anime? {
        val original = key.trim()
        if (original.isBlank()) return null
        var source: JSONObject? = null
        if (original.matches(Regex("\\d+"))) {
            try { source = JSONObject(request("/animes/$original").body) } catch (_: Exception) { }
        }
        if (source == null) {
            for (candidate in slugCandidates(original)) {
                try {
                    val result = JSONArray(request("/animes", mapOf("slug" to candidate)).body)
                    if (result.length() > 0) { source = result.getJSONObject(0); break }
                } catch (_: Exception) { }
            }
        }
        return source?.let { normalizeDetail(it) }
    }

    suspend fun getGenres(): List<Genre> {
        val all = mutableListOf<Genre>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val result = request("/animegenre", mapOf("per_page" to "100", "page" to page.toString(), "orderby" to "name", "order" to "asc"))
            val raw = JSONArray(result.body)
            for (i in 0 until raw.length()) {
                val item = raw.getJSONObject(i)
                all += Genre(item.optInt("id"), item.optString("name"), item.optString("slug"), item.optInt("count"))
            }
            totalPages = if (result.totalPages > 0) result.totalPages else page
            page++
        }
        return all
    }

    suspend fun getGenre(slugOrId: String, page: Int = 1, limit: Int = 12): Pair<Genre?, AnimePage> {
        val term = getGenres().firstOrNull {
            it.id.toString() == slugOrId || it.slug.equals(slugOrId, true) || it.name.equals(slugOrId, true)
        } ?: return null to AnimePage()
        val params = baseListParams(page, limit).toMutableMap()
        params["animegenre"] = term.id.toString()
        val result = request("/animes", params)
        return term to AnimePage(normalizeList(JSONArray(result.body)), result.total, result.totalPages)
    }

    suspend fun getSchedule(day: String, page: Int = 1, limit: Int = 12): AnimePage {
        val ids = mapOf("senin" to 3058, "selasa" to 3059, "rabu" to 3061, "kamis" to 3062,
            "jumat" to 3063, "sabtu" to 3057, "minggu" to 3064)
        val params = baseListParams(page, limit).toMutableMap()
        val id = ids[day.lowercase(Locale.ROOT)]
        if (id != null) params["jadwalrilis"] = id.toString() else params["orderby"] = "rand"
        val result = request("/animes", params)
        return AnimePage(normalizeList(JSONArray(result.body)), result.total, result.totalPages)
    }

    private fun baseListParams(page: Int, limit: Int) = mapOf(
        "_fields" to LIST_FIELDS,
        "page" to max(1, page).toString(),
        "per_page" to min(limit.coerceAtLeast(1), 24).toString()
    )

    private fun normalizeList(raw: JSONArray) = (0 until raw.length()).map { normalizeAnime(raw.getJSONObject(it), false) }

    private fun normalizeAnime(a: JSONObject, includeGenres: Boolean): Anime {
        val meta = a.optJSONObject("meta_box") ?: JSONObject()
        fun metaString(key: String) = meta.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        val titleObj = a.optJSONObject("title")
        val episodeRaw = metaString("ero_episode").ifBlank { metaString("ero_episodebaru") }
        val episodeCount = episodeRaw.toDoubleOrNull()?.let { if (it >= 99999) 0 else it.toInt() } ?: 0
        return Anime(
            id = a.optInt("id"), slug = a.optString("slug"), title = stripHtml(titleObj?.optString("rendered").orEmpty().ifBlank { a.optString("title") }),
            date = a.optString("date"), modified = a.optString("modified"), link = a.optString("link"),
            cover = metaString("ero_image"), episode = episodeCount, status = metaString("ero_status"),
            type = metaString("ero_type"), score = metaString("ero_skor"), year = metaString("ero_tayang"),
            sub = metaString("ero_sub"), duration = metaString("ero_durasi"), genreText = metaString("ero_genreapp"),
            japanese = metaString("ero_japanese"), trailer = metaString("ero_trailer"),
            genreIds = if (includeGenres || a.has("animegenre")) intArray(a.optJSONArray("animegenre")) else emptyList(),
            statusIds = intArray(a.optJSONArray("animestatus")), typeIds = intArray(a.optJSONArray("animetype"))
        )
    }

    private fun normalizeDetail(a: JSONObject): Anime {
        val normalized = normalizeAnime(a, true)
        val content = a.optJSONObject("content")?.optString("rendered").orEmpty()
        val meta = a.optJSONObject("meta_box") ?: JSONObject()
        val rawEpisodes = meta.optJSONArray("ab_cdngroup") ?: JSONArray()
        val seen = mutableSetOf<String>()
        val episodes = (0 until rawEpisodes.length()).mapNotNull { i ->
            val item = rawEpisodes.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("ab_namaep")
            if (name.isBlank() || !seen.add(name)) return@mapNotNull null
            Episode(name, encodeMedia(item.optString("ab_linkcdn")))
        }.sortedWith(compareBy<Episode> { episodeNumber(it.name) ?: Double.POSITIVE_INFINITY })
        return normalized.copy(synopsis = stripHtml(content), episodes = episodes)
    }

    private fun intArray(array: JSONArray?): List<Int> = if (array == null) emptyList() else (0 until array.length()).map { array.optInt(it) }

    private fun slugCandidates(value: String): List<String> {
        val out = linkedSetOf<String>()
        var frontier = listOf(value)
        out += value
        repeat(3) {
            val next = mutableListOf<String>()
            frontier.forEach { current ->
                listOf("-episode-\\d+$", "-season-\\d+$", "-part-\\d+$", "-\\d+$").forEach { pattern ->
                    val candidate = current.replace(Regex(pattern), "")
                    if (candidate.isNotBlank() && candidate != current && out.add(candidate)) next += candidate
                }
            }
            frontier = next
        }
        return out.toList()
    }

    private fun encodeMedia(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val url = URL(raw)
            val encodedPath = url.path.split("/").joinToString("/") { segment ->
                segment.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25")
                    .replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
            }
            "${url.protocol}://${url.authority}$encodedPath" + (url.query?.let { "?$it" } ?: "") + (url.ref?.let { "#$it" } ?: "")
        } catch (_: Exception) { raw.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D") }
    }

    private fun episodeNumber(value: String): Double? = Regex("\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
    private fun normalizeTitle(value: String) = stripHtml(value).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun slugify(value: String) = normalizeTitle(value).replace(Regex("\\s+"), "-")
    private fun searchScore(a: Anime, query: String, slug: String): Int {
        val title = normalizeTitle(a.title)
        return when {
            title == query -> 100
            a.slug.lowercase(Locale.ROOT) == slug -> 90
            title.startsWith(query) || query.startsWith(title) -> 60
            query.isNotBlank() && title.contains(query) -> 40
            else -> 0
        }
    }

    private fun stripHtml(value: String): String = value.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#038;", "&").replace(Regex("&#(\\d+);"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun normalizeJsonBody(body: String): String {
        val clean = body.removePrefix("\uFEFF").trim()
        if (looksLikeJson(clean)) return clean
        val first = listOf(clean.indexOf('{'), clean.indexOf('[')).filter { it >= 0 }.minOrNull() ?: -1
        return if (first > 0) clean.substring(first).trim().takeIf(::looksLikeJson).orEmpty() else clean
    }
    private fun looksLikeJson(value: String) = value.length >= 2 &&
        ((value.first() == '{' && value.last() == '}') || (value.first() == '[' && value.last() == ']'))
    private fun String.compact() = replace(Regex("\\s+"), " ").trim().take(180)

    private data class ApiResponse(val body: String, val total: Int, val totalPages: Int)
}

open class ApiException(message: String) : Exception(message)
private class RetryableApiException(message: String) : ApiException(message)
