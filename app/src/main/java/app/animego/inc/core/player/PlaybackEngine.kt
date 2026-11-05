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

data class PlaybackSnapshot(
    val source: String = "",
    val title: String = "",
    val episode: String = "",
    val slug: String = "",
    val cover: String = "",
    val isPlaying: Boolean = false,
    val offline: Boolean = false
)

object PlaybackEngine {
    @Volatile private var instance: ExoPlayer? = null
    @Volatile var currentTitle: String = "AnimeGo"
    @Volatile var currentEpisode: String = ""
    @Volatile var currentSlug: String = ""
    @Volatile var currentCover: String = ""
    @Volatile var currentSource: String = ""
    @Volatile var currentOffline: Boolean = false

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

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
            offline = currentOffline
        )
    }
}
