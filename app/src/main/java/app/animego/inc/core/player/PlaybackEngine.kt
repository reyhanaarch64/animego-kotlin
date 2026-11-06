package app.animego.inc.core.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackQueueItem(
    val name: String,
    val source: String
)

data class PlaybackSnapshot(
    val source: String = "",
    val title: String = "",
    val episode: String = "",
    val slug: String = "",
    val cover: String = "",
    val isPlaying: Boolean = false,
    val offline: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val speed: Float = 1f
)

object PlaybackEngine {
    @Volatile private var instance: ExoPlayer? = null
    @Volatile var currentTitle: String = "AnimeGo"
    @Volatile var currentEpisode: String = ""
    @Volatile var currentSlug: String = ""
    @Volatile var currentCover: String = ""
    @Volatile var currentSource: String = ""
    @Volatile var currentOffline: Boolean = false
    @Volatile private var queueItems: List<PlaybackQueueItem> = emptyList()
    @Volatile private var queueIndex: Int = -1

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()
    private val _overlayActive = MutableStateFlow(false)
    val overlayActive: StateFlow<Boolean> = _overlayActive.asStateFlow()

    fun setOverlayActive(active: Boolean) {
        _overlayActive.value = active
    }

    fun refresh() {
        publish()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish(isPlaying)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
    }

    fun player(context: Context): ExoPlayer = instance ?: synchronized(this) {
        instance ?: run {
            val appContext = context.applicationContext
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 AnimeGo/2.0")
                .setDefaultRequestProperties(mapOf(
                    "Referer" to "https://karanime.com/",
                    "Origin" to "https://karanime.com",
                    "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
                ))
            val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, httpFactory))
            ExoPlayer.Builder(appContext).setMediaSourceFactory(sourceFactory)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build().also {
                    it.addListener(playerListener)
                    instance = it
                }
        }
    }

    fun play(
        context: Context,
        source: String,
        title: String,
        episode: String,
        slug: String,
        cover: String,
        positionMs: Long = 0L,
        offline: Boolean = false
    ) {
        val player = player(context)
        currentTitle = title
        currentEpisode = episode
        currentSlug = slug
        currentCover = cover
        currentOffline = offline
        if (currentSource != source || player.mediaItemCount == 0) {
            currentSource = source
            val metadata = MediaMetadata.Builder().setTitle(title).setSubtitle(episode).build()
            val item = MediaItem.Builder().setUri(source).setMediaMetadata(metadata).build()
            player.setMediaItem(item)
            player.prepare()
            if (positionMs > 0) player.seekTo(positionMs)
        } else if (positionMs > 0 && player.currentPosition == 0L) {
            player.seekTo(positionMs)
        }
        player.playWhenReady = true
        publish()
        PlaybackService.start(context)
    }


    fun setQueue(items: List<PlaybackQueueItem>, selectedIndex: Int = -1) {
        queueItems = items.toList()
        val explicit = selectedIndex.takeIf { it in queueItems.indices }
        val bySource = queueItems.indexOfFirst { it.source == currentSource }.takeIf { it >= 0 }
        val byName = queueItems.indexOfFirst { it.name == currentEpisode }.takeIf { it >= 0 }
        queueIndex = explicit ?: bySource ?: byName ?: -1
    }


    fun canSkipQueue(offset: Int): Boolean {
        if (offset == 0 || queueItems.isEmpty()) return false
        val current = queueIndex.takeIf { it in queueItems.indices }
            ?: queueItems.indexOfFirst { it.source == currentSource }
        return current in queueItems.indices && current + offset in queueItems.indices
    }

    fun skipQueue(context: Context, offset: Int): Boolean {
        if (offset == 0 || queueItems.isEmpty()) return false
        val current = queueIndex.takeIf { it in queueItems.indices }
            ?: queueItems.indexOfFirst { it.source == currentSource }
        if (current !in queueItems.indices) return false
        val target = current + offset
        val item = queueItems.getOrNull(target) ?: return false
        queueIndex = target
        play(
            context = context,
            source = item.source,
            title = currentTitle,
            episode = item.name,
            slug = currentSlug,
            cover = currentCover,
            positionMs = 0L,
            offline = false
        )
        return true
    }

    fun toggle(context: Context) {
        val player = player(context)
        if (player.isPlaying) player.pause() else player.play()
        publish()
    }

    fun clear() {
        instance?.stop()
        instance?.clearMediaItems()
        currentTitle = "AnimeGo"
        currentEpisode = ""
        currentSlug = ""
        currentCover = ""
        currentSource = ""
        currentOffline = false
        queueItems = emptyList()
        queueIndex = -1
        _overlayActive.value = false
        publish(false)
    }

    fun stop(context: Context) {
        clear()
        context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java))
        context.applicationContext.stopService(Intent(context.applicationContext, FloatingPlayerService::class.java))
    }

    fun release() {
        instance?.removeListener(playerListener)
        instance?.release()
        instance = null
        currentSource = ""
        currentOffline = false
        queueItems = emptyList()
        queueIndex = -1
        _overlayActive.value = false
        publish(false)
    }

    private fun publish(isPlaying: Boolean? = null) {
        val player = instance
        _snapshot.value = PlaybackSnapshot(
            source = currentSource,
            title = currentTitle,
            episode = currentEpisode,
            slug = currentSlug,
            cover = currentCover,
            isPlaying = isPlaying ?: (player?.isPlaying == true),
            offline = currentOffline,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = player?.duration?.takeIf { it > 0L } ?: 0L,
            volume = player?.volume ?: 1f,
            speed = player?.playbackParameters?.speed ?: 1f
        )
    }
}
