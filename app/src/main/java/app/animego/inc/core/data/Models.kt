package app.animego.inc.core.data

data class Episode(
    val name: String,
    val url: String
)

data class Anime(
    val id: Int = 0,
    val slug: String = "",
    val title: String = "",
    val date: String = "",
    val modified: String = "",
    val link: String = "",
    val cover: String = "",
    val episode: Int = 0,
    val status: String = "",
    val type: String = "",
    val score: String = "",
    val year: String = "",
    val sub: String = "",
    val duration: String = "",
    val genreText: String = "",
    val japanese: String = "",
    val trailer: String = "",
    val synopsis: String = "",
    val genreIds: List<Int> = emptyList(),
    val statusIds: List<Int> = emptyList(),
    val typeIds: List<Int> = emptyList(),
    val episodes: List<Episode> = emptyList()
)

data class AnimePage(
    val items: List<Anime> = emptyList(),
    val total: Int = 0,
    val totalPages: Int = 0
)

data class AnimeHome(
    val ongoing: AnimePage = AnimePage(),
    val top: AnimePage = AnimePage(),
    val movies: AnimePage = AnimePage(),
    val completed: AnimePage = AnimePage()
)

data class Genre(
    val id: Int,
    val name: String,
    val slug: String,
    val count: Int = 0
)

data class FavoriteRecord(
    val slug: String,
    val title: String,
    val cover: String,
    val addedAt: Long
)

data class HistoryRecord(
    val slug: String,
    val title: String,
    val ep: String,
    val cover: String,
    val source: String,
    val position: Double,
    val duration: Double,
    val watchedAt: Long
)

data class DownloadRecord(
    val id: Long,
    val slug: String,
    val animeId: Int,
    val title: String,
    val ep: String,
    val url: String,
    val path: String,
    val filename: String,
    val status: String,
    val progress: Long,
    val total: Long,
    val error: String,
    val headers: String,
    val addedAt: Long,
    val updatedAt: Long
) {
    val percent: Int get() = if (total > 0) ((progress * 100L) / total).toInt().coerceIn(0, 100) else 0
    val exists: Boolean get() = status == "completed" && java.io.File(path).isFile
}
