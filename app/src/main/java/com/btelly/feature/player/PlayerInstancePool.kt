package com.btelly.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.btelly.core.common.media.VideoCodecSupport

@UnstableApi
object PlayerInstancePool {
    private const val TAG = "PlayerInstancePool"
    private const val IDLE_RELEASE_DELAY_MS = 45_000L
    private const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024 // 20MB

    // PCM AudioTrack 缓冲：日志里 500ms 缓冲出现 underrun，放宽到 750-2000ms 抵御软解/GC 抢占。
    private const val MIN_PCM_BUFFER_DURATION_US = 750_000
    private const val MAX_PCM_BUFFER_DURATION_US = 2_000_000

    // WiFi 下保持较快起播，但提高最小缓冲和重缓冲门槛，减少播放中反复 BUFFERING。
    private const val WIFI_MIN_BUFFER_MS = 12_000
    private const val WIFI_MAX_BUFFER_MS = 40_000
    private const val WIFI_BUFFER_FOR_PLAYBACK_MS = 1_000
    private const val WIFI_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    // 移动数据下的缓冲参数：更保守，减少卡顿
    private const val CELLULAR_MIN_BUFFER_MS = 16_000
    private const val CELLULAR_MAX_BUFFER_MS = 50_000
    private const val CELLULAR_BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val CELLULAR_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_500

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedPlayer: ExoPlayer? = null
    private var isAttached = false
    private var pendingReleaseRunnable: Runnable? = null
    @Volatile
    private var codecPrewarmStarted = false

    @Synchronized
    fun prewarm(context: Context) {
        prewarmCodecSupport()
        if (cachedPlayer != null) return
        mainHandler.post {
            synchronized(this) {
                if (cachedPlayer != null) return@synchronized
                cachedPlayer = buildPlayer(context.applicationContext)
            }
        }
    }

    private fun prewarmCodecSupport() {
        if (codecPrewarmStarted) return
        codecPrewarmStarted = true
        Thread({
            runCatching { VideoCodecSupport.getHardwareSupportedCodecs() }
        }, "player-codec-prewarm").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun isAttached(): Boolean = isAttached

    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        cancelPendingRelease()
        val player = cachedPlayer ?: buildPlayer(context.applicationContext).also {
            cachedPlayer = it
        }
        isAttached = true
        return player
    }

    @Synchronized
    fun softDetach(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        player.pause()
        isAttached = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        schedule_release()
    }

    @Synchronized
    fun hardReset(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        player.playWhenReady = false
        player.clearMediaItems()
        player.stop()
        player.playbackParameters = PlaybackParameters(1f)
    }

    @Synchronized
    fun detach(player: ExoPlayer?, allowReuse: Boolean) {
        if (player == null || player !== cachedPlayer) return
        if (!allowReuse) {
            releaseNow("detach_without_reuse")
            return
        }
        softDetach(player)
    }

    @Synchronized
    fun releaseNow(reason: String) {
        cancelPendingRelease()
        isAttached = false
        cachedPlayer?.let(PlayerAudioNormalizer::release)
        cachedPlayer?.release()
        cachedPlayer = null
    }

    @Synchronized
    private fun schedule_release() {
        cancelPendingRelease()
        val releaseRunnable = Runnable {
            synchronized(this) {
                if (isAttached) return@synchronized
                cachedPlayer?.let(PlayerAudioNormalizer::release)
                cachedPlayer?.release()
                cachedPlayer = null
                pendingReleaseRunnable = null
            }
        }
        pendingReleaseRunnable = releaseRunnable
        mainHandler.postDelayed(releaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    @Synchronized
    private fun cancelPendingRelease() {
        pendingReleaseRunnable?.let(mainHandler::removeCallbacks)
        pendingReleaseRunnable = null
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val isFastNetwork = isOnFastNetwork(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isFastNetwork) WIFI_MIN_BUFFER_MS else CELLULAR_MIN_BUFFER_MS,
                if (isFastNetwork) WIFI_MAX_BUFFER_MS else CELLULAR_MAX_BUFFER_MS,
                if (isFastNetwork) WIFI_BUFFER_FOR_PLAYBACK_MS else CELLULAR_BUFFER_FOR_PLAYBACK_MS,
                if (isFastNetwork) WIFI_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS else CELLULAR_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(createRenderersFactory(context))
            .setLoadControl(loadControl)
            .build()
            .also(PlayerPlaybackPolicy::apply)
            .also(PlayerAudioNormalizer::attach)
    }

    fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context.applicationContext) {
            init {
                setEnableDecoderFallback(true)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioOutputProvider(
                        AudioTrackAudioOutputProvider.Builder(context)
                            .setAudioTrackBufferSizeProvider(
                                DefaultAudioTrackBufferSizeProvider.Builder()
                                    .setMinPcmBufferDurationUs(MIN_PCM_BUFFER_DURATION_US)
                                    .setMaxPcmBufferDurationUs(MAX_PCM_BUFFER_DURATION_US)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            }
        }
    }

    private fun isOnFastNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
