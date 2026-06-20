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
import com.btelly.feature.player.settings.PlayerSettingsStore

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

    /**
     * player 当前实际挂载的 MediaSource 标识（"bvid#cid"）。
     *
     * 这是 zero_overhead_reuse 判定"player 上是否真是这个视频"的**唯一事实源**。
     * VM 缓存按 bvid+cid 存（容量 2），但 player 单例只能挂 1 个 MediaSource——
     * 两套键空间基数不同。VM 命中缓存不代表 player 上挂的就是同一视频
     * （退出→看别的→退出→看回原来的，player 已被覆盖）。此处显式记录挂载状态，
     * 供 VM 在走暖路径前查询，消除"VM 在无 player 状态信息下决策"的缺陷。
     *
     * 由 setMediaSource 的调用方通过 [rememberAttachedSource] 记录，
     * 由所有"清除 MediaItems 或销毁 player"的路径清空。softDetach 不清空
     * （其语义本就是保留 MediaSource 供热重播）。
     */
    private var attachedSourceKey: String? = null

    /**
     * 查询 player 当前挂载的 MediaSource 是否与请求的 bvid+cid 一致。
     * zero_overhead_reuse 走暖路径前必须调用此方法确认。
     */
    @Synchronized
    fun isAttachedSource(bvid: String?, cid: Long): Boolean {
        return attachedSourceKey != null && attachedSourceKey == sourceKey(bvid, cid)
    }

    /**
     * setMediaSource 成功挂载后，由调用方记录。
     * 仅在冷路径（player.setMediaSource(...)）调用，暖路径跳过 setMediaSource 故不调用。
     */
    @Synchronized
    fun rememberAttachedSource(bvid: String?, cid: Long) {
        attachedSourceKey = sourceKey(bvid, cid)
    }

    private fun sourceKey(bvid: String?, cid: Long): String = "${bvid.orEmpty()}#$cid"

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
        player.clearVideoSurface()
        // 不调用 clearMediaItems()，保留 MediaSource 以便同一视频热重播。
        // reuseSameSource 路径只需 prepare() + seekTo()，跳过 setMediaSource()。
        schedule_release()
    }

    @Synchronized
    fun hardReset(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        player.playWhenReady = false
        player.clearMediaItems()
        player.stop()
        player.playbackParameters = PlaybackParameters(1f)
        // MediaItems 被清除，挂载状态归零。
        attachedSourceKey = null
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
        // player 销毁，挂载状态归零。
        attachedSourceKey = null
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
                // player 销毁，挂载状态归零。
                attachedSourceKey = null
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
            .also {
                // 音量均衡默认关闭：DynamicsProcessing/LoudnessEnhancer 在部分 TV 设备上会引入失真（电音）。
                if (PlayerSettingsStore.load(context).audioNormalize) {
                    PlayerAudioNormalizer.attach(it)
                }
            }
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
