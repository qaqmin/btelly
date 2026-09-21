package com.btelly.feature.player.engine

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.btelly.core.common.log.AppLog
import tv.danmaku.ijk.media.player.IjkLibLoader
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * IjkPlayer 内核的 Media3 [Player] 门面。
 *
 * B视机 全栈（MyPlayerView/弹幕/进度/心跳）都面向 Media3 Player 接口，因此不引入
 * 上游式的窄引擎接口，而是让 ijk 引擎实现 Player——复用既有调用面，仅在"源装载"
 * （Exo 的 setMediaSource → 本引擎的 [setIjkSource]）与"实例获取"两处分叉。
 *
 * 事件映射沿用 上游实现 v0.1.18+ 的成熟方案；HTTP 头与 Exo 路径的 OkHttpDataSource 对齐
 * （UA/Referer/Origin，无 Cookie）。DASH 双轨经本地 MPD 合流，内核 .so 随 aar 打包。
 */
@UnstableApi
internal class IjkPlayerEngine(context: android.content.Context) : BasePlayer() {

    private val appContext: android.content.Context = context.applicationContext

    companion object {
        private const val TAG = "IjkPlayerEngine"
        private const val MAX_BUFFERED_FORWARD_ESTIMATE_MS: Long = 5 * 60_000L
        private const val VISIBLE_SEEK_POSITION_TIMEOUT_MS: Long = 10_000L

        /** 与 VideoPlayerViewModel.upstreamDataSourceFactory 的 UA/Referer/Origin 保持一致。 */
        private const val MEDIA_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        private const val MEDIA_HEADERS_STRING =
            "Referer: https://www.bilibili.com\r\n" +
                "Origin: https://www.bilibili.com\r\n" +
                "Accept-Encoding: identity\r\n"

        /** 内核 aar 已按 ABI 打包 libijkplayer.so（cmake 静态合并 ffmpeg），系统加载即可。 */
        private val DEFAULT_LIB_LOADER = IjkLibLoader { libName ->
            System.loadLibrary(libName)
        }

        fun isKernelLoadable(): Boolean = runCatching {
            IjkMediaPlayer.loadLibrariesOnce(DEFAULT_LIB_LOADER)
            true
        }.getOrElse { false }
    }

    private val listeners: MutableSet<Player.Listener> = CopyOnWriteArraySet()

    private var ijk: IjkMediaPlayer? = null
    private var prepared = false
    private var buffering = false
    private var prepareRequested = false
    private var pendingSeekMs: Long? = null
    private var visibleSeekPositionMs: Long? = null
    private var visibleSeekStartedAtMs = 0L
    private var visibleSeekClearsOnFirstFrame = false

    private var playbackStateInternal: Int = Player.STATE_IDLE
    private var playWhenReadyInternal = false
    private var repeatModeInternal: Int = Player.REPEAT_MODE_OFF
    private var speedInternal = 1.0f
    private var volumeInternal = 1.0f
    private var videoSurface: Surface? = null
    private var trackSelectionParametersInternal: TrackSelectionParameters =
        TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private var lastError: PlaybackException? = null

    private var source: IjkPlaybackSource? = null
    private var sourceDurationMs = 0L
    private var sourceIsLive = false
    private var sourceMediaItem: MediaItem? = null
    private var currentTimeline: Timeline = Timeline.EMPTY

    // ==================== 源装载（冷路径入口，替代 setMediaSource） ====================

    fun setIjkSource(newSource: IjkPlaybackSource, startSeekMs: Long) {
        source = newSource
        sourceDurationMs = (newSource as? IjkPlaybackSource.Dash)?.durationMs ?: 0L
        sourceIsLive = (newSource as? IjkPlaybackSource.Direct)?.isLive == true
        val uri = when (newSource) {
            is IjkPlaybackSource.Dash -> newSource.video.url
            is IjkPlaybackSource.Direct -> newSource.url
        }
        sourceMediaItem = MediaItem.Builder().setUri(uri).build()
        currentTimeline = IjkSingleWindowTimeline(
            sourceMediaItem!!,
            durationUs = sourceDurationMs * 1000L,
            isLive = sourceIsLive,
        )
        // 时间条/拖动逻辑依赖 Timeline（isCurrentMediaItemSeekable 等 BasePlayer 均从
        // Timeline 推导），必须给出真实单窗口，否则进度条不可见且不可拖动。
        listeners.forEach {
            it.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            it.onMediaItemTransition(sourceMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
        lastError = null
        prepared = false
        buffering = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        setStateInternal(Player.STATE_IDLE)

        runCatching { ensurePlayer() }.onFailure { t -> dispatchError(t); return }

        val p = ijk ?: return
        runCatching { p.reset() }
        prepared = false
        buffering = false
        clearVisibleSeekPosition()

        applyCommonOptions(p)
        runCatching { p.setSurface(videoSurface) }
        runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
        runCatching { p.setSpeed(speedInternal) }
        applyInitialSeek(p, startSeekMs)

        try {
            applyHttpOptions(p)
            when (newSource) {
                is IjkPlaybackSource.Dash -> {
                    val mpdFile = writeDashMpd(newSource)
                    p.setDataSource(mpdFile.absolutePath)
                    AppLog.i(
                        TAG,
                        "dash source mpd=${mpdFile.name} bytes=${mpdFile.length()} " +
                            "qn=${newSource.video.id} codec=${newSource.video.codecId} audio=${newSource.audio.id}",
                    )
                }

                is IjkPlaybackSource.Direct -> {
                    if (newSource.isLive) {
                        // 直播：有界包缓冲——infbuf 无限缓冲会让视频积压队列无界增长，
                        // 解码追不上积压表现为音画漂移持续扩大；限制在 3 秒内贴实时解码。
                        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 3000L) }
                        // max_cached_duration 对部分 FLV 直播不生效（vCache 曾涨到 30 秒），
                        // 用格式层缓冲上限掐断预读源头。driftMs(vCache-aCache)即"画面比声音旧
                        // 多少秒"：4MB 在 8.4Mbps 原画流下≈3.8s，用户可闻不同步；收到 1.5MB
                        // （8.4Mbps≈1.4s / 4.8Mbps≈2.5s）把偏移压到 1 秒内，重锚转为兜底。
                        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 1_500_000L) }
                        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fast", 1L) }
                        // 记录连接墙钟时刻：stats 用它推算音频相对直播边缘的滞后（aLiveMs），
                        // 配合 driftMs 得出画面/声音的客观偏移（vLiveMs - aLiveMs）。
                        liveConnectedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    }
                    p.setDataSource(newSource.url)
                    AppLog.i(TAG, "direct source url=${newSource.url.take(120)} live=${newSource.isLive}")
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "setDataSource failed: ${t.message}", t)
            dispatchError(t)
        }
    }

    private fun applyInitialSeek(p: IjkMediaPlayer, startSeekMs: Long) {
        val positionMs = startSeekMs.takeIf { it > 0L } ?: return
        val isDash = source is IjkPlaybackSource.Dash
        if (isDash) {
            // DASH 路径优先用 seek-at-start，比 prepare 后立即 seekTo 更稳（对齐上游实现 经验）。
            pendingSeekMs = null
            runCatching {
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", positionMs)
            }.onSuccess { markVisibleSeekPosition(positionMs, clearsOnFirstFrame = true) }
                .onFailure { clearVisibleSeekPosition() }
        } else {
            pendingSeekMs = positionMs
            markVisibleSeekPosition(positionMs, clearsOnFirstFrame = false)
        }
    }

    // ==================== Listener 管理与事件分发 ====================

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    private fun setStateInternal(state: Int) {
        if (playbackStateInternal == state) {
            notifyIsPlayingChanged()
            return
        }
        playbackStateInternal = state
        listeners.forEach { it.onPlaybackStateChanged(state) }
        notifyIsPlayingChanged()
    }

    private fun notifyIsPlayingChanged() {
        val now = isPlaying
        if (lastNotifiedIsPlaying == now) return
        lastNotifiedIsPlaying = now
        listeners.forEach { it.onIsPlayingChanged(now) }
    }

    private var lastNotifiedIsPlaying: Boolean? = null

    private fun dispatchError(t: Throwable) {
        val e = t as? PlaybackException
            ?: PlaybackException(t.message ?: "ijk error", t, PlaybackException.ERROR_CODE_UNSPECIFIED)
        lastError = e
        listeners.forEach { it.onPlayerError(e) }
    }

    private fun dispatchPositionDiscontinuity(positionMs: Long) {
        listeners.forEach {
            it.onPositionDiscontinuity(
                Player.PositionInfo(null, 0, null, null, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET),
                Player.PositionInfo(null, 0, null, null, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET),
                Player.DISCONTINUITY_REASON_SEEK,
            )
        }
    }

    private fun dispatchPlayWhenReadyChanged() {
        listeners.forEach {
            it.onPlayWhenReadyChanged(
                playWhenReadyInternal,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
        }
        notifyIsPlayingChanged()
    }

    // ==================== Player 状态查询与控制 ====================

    override fun getPlaybackState(): Int = playbackStateInternal

    override fun getPlayWhenReady(): Boolean = playWhenReadyInternal

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReadyInternal == playWhenReady) return
        playWhenReadyInternal = playWhenReady
        dispatchPlayWhenReadyChanged()
        ijk?.let { syncPlayWhenReadyToNative(it) }
    }

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = lastError

    override fun prepare() {
        prepareRequested = true
        startPrepareIfPossible()
    }

    override fun stop() {
        val p = ijk
        if (p != null) runCatching { p.stop() }
        prepared = false
        buffering = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        setStateInternal(Player.STATE_IDLE)
    }

    override fun release() {
        source = null
        playWhenReadyInternal = false
        prepared = false
        buffering = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        playbackStateInternal = Player.STATE_IDLE
        stopStatsTicker()
        val p = ijk
        ijk = null
        if (p != null) {
            runCatching { p.resetListeners() }
            runCatching { p.release() }
        }
        listeners.clear()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long, seekCommand: Int, isRepeating: Boolean) {
        val p = ijk ?: return
        val pos = positionMs.coerceAtLeast(0L)
        val leavesEnded = playbackStateInternal == Player.STATE_ENDED &&
            (sourceDurationMs <= 0L || pos < sourceDurationMs)
        if (!prepared) {
            // 未 prepare 前的 seek：DASH 走 seek-at-start，其余暂存到 prepared 回调。
            if (source is IjkPlaybackSource.Dash) {
                pendingSeekMs = null
                runCatching {
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", pos)
                }.onSuccess { markVisibleSeekPosition(pos, clearsOnFirstFrame = true) }
            } else {
                pendingSeekMs = pos
                markVisibleSeekPosition(pos, clearsOnFirstFrame = false)
            }
            return
        }
        runCatching { p.seekTo(pos) }
            .onSuccess {
                markVisibleSeekPosition(pos, clearsOnFirstFrame = false)
                if (leavesEnded) {
                    setStateInternal(Player.STATE_BUFFERING)
                    syncPlayWhenReadyToNative(p)
                }
            }
            .onFailure { clearVisibleSeekPosition() }
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(speedInternal)

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val v = playbackParameters.speed
            .takeIf { it.isFinite() }
            ?.coerceIn(0.25f, 4.0f)
            ?: 1.0f
        if (v == speedInternal) return
        speedInternal = v
        val params = PlaybackParameters(v)
        listeners.forEach { it.onPlaybackParametersChanged(params) }
        ijk?.let { p -> runCatching { p.setSpeed(v) } }
    }

    override fun getRepeatMode(): Int = repeatModeInternal

    override fun setRepeatMode(repeatMode: Int) {
        if (repeatModeInternal == repeatMode) return
        repeatModeInternal = repeatMode
        ijk?.let { p -> runCatching { p.setLooping(repeatMode == Player.REPEAT_MODE_ONE) } }
        listeners.forEach { it.onRepeatModeChanged(repeatMode) }
    }

    override fun getShuffleModeEnabled(): Boolean = false

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getVolume(): Float = volumeInternal

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        if (v == volumeInternal) return
        volumeInternal = v
        ijk?.let { p -> runCatching { p.setVolume(v, v) } }
        listeners.forEach { it.onVolumeChanged(v) }
    }

    override fun getVideoSize(): VideoSize = videoSizeInternal

    private var videoSizeInternal: VideoSize = VideoSize.UNKNOWN

    override fun setVideoSurface(surface: Surface?) {
        clearSurfaceAttachments()
        videoSurface = surface
        ijk?.let { p -> runCatching { p.setSurface(surface) } }
        startPrepareIfPossible()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        clearSurfaceAttachments()
        surfaceOutput = surfaceView
        if (surfaceView != null) {
            surfaceView.holder.addCallback(surfaceHolderListener)
            attachHolderSurface(surfaceView.holder)
        }
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceOutput === surfaceView) setVideoSurface(null)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        clearSurfaceAttachments()
        textureOutput = textureView
        if (textureView != null) {
            textureView.surfaceTextureListener = textureListener
            if (textureView.isAvailable) {
                attachTextureSurface(textureView)
            }
        }
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (textureOutput === textureView) setVideoSurface(null)
    }

    override fun setVideoSurfaceHolder(holder: SurfaceHolder?) {
        clearSurfaceAttachments()
        holderOutput = holder
        if (holder != null) {
            holder.addCallback(surfaceHolderListener)
            attachHolderSurface(holder)
        }
    }

    override fun clearVideoSurfaceHolder(holder: SurfaceHolder?) {
        if (holderOutput === holder) setVideoSurface(null)
    }

    private var surfaceOutput: SurfaceView? = null
    private var holderOutput: SurfaceHolder? = null
    private var textureOutput: TextureView? = null

    private val surfaceHolderListener = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attachHolderSurface(holder)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (holderOutput === holder || surfaceOutput?.holder === holder) setVideoSurface(null)
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            if (textureOutput != null) attachTextureSurface(textureOutput!!)
        }

        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
            if (textureOutput != null) setVideoSurface(null)
            return true
        }

        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
    }

    private fun clearSurfaceAttachments() {
        surfaceOutput?.holder?.removeCallback(surfaceHolderListener)
        surfaceOutput = null
        holderOutput?.removeCallback(surfaceHolderListener)
        holderOutput = null
        textureOutput?.surfaceTextureListener = null
        textureOutput = null
    }

    private fun attachHolderSurface(holder: SurfaceHolder) {
        val surface = holder.surface ?: return
        videoSurface = surface
        ijk?.let { p -> runCatching { p.setSurface(surface) } }
        startPrepareIfPossible()
    }

    private fun attachTextureSurface(textureView: TextureView) {
        val st = textureView.surfaceTexture ?: return
        val surface = Surface(st)
        videoSurface = surface
        ijk?.let { p -> runCatching { p.setSurface(surface) } }
        startPrepareIfPossible()
    }

    override fun clearVideoSurface() = setVideoSurface(null)

    override fun clearVideoSurface(surface: Surface?) {
        if (videoSurface === surface) setVideoSurface(null)
    }

    // ---- 位置/时长 ----

    override fun getDuration(): Long {
        if (sourceIsLive) return C.TIME_UNSET
        val fromSource = sourceDurationMs.takeIf { it > 0L }
        if (fromSource != null) return fromSource
        return ijk?.duration?.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    override fun getCurrentPosition(): Long =
        visibleSeekPositionOrNull() ?: ijk?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override fun getBufferedPosition(): Long {
        val p = ijk ?: return currentPosition
        visibleSeekPositionOrNull()?.let { seekPos ->
            return seekPos
        }
        val pos = p.currentPosition.coerceAtLeast(0L)
        val vCache = p.videoCachedDuration.coerceAtLeast(0L)
        val aCache = p.audioCachedDuration.coerceAtLeast(0L)
        // DASH 分离双轨时，可播放缓冲窗口受较慢的轨约束，取 min 而非乐观并集（对齐上游实现）。
        val cachedForwardMs = when {
            vCache > 0L && aCache > 0L -> minOf(vCache, aCache)
            else -> maxOf(vCache, aCache)
        }.coerceAtMost(MAX_BUFFERED_FORWARD_ESTIMATE_MS)
        val raw = if (Long.MAX_VALUE - pos < cachedForwardMs) Long.MAX_VALUE else pos + cachedForwardMs
        val dur = duration.takeIf { it > 0L } ?: return raw
        return raw.coerceIn(0L, dur)
    }

    override fun getTotalBufferedDuration(): Long {
        val dur = duration
        if (dur <= 0L) return bufferedPosition
        return bufferedPosition.coerceAtLeast(0L)
    }

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun isLoading(): Boolean = buffering

    // ---- MediaItem / Timeline（ijk 单源模型：固定单窗口） ----

    override fun getCurrentTimeline(): Timeline = currentTimeline

    override fun getCurrentPeriodIndex(): Int = 0

    override fun getCurrentMediaItemIndex(): Int = 0

    // ---- MediaItems 管理：ijk 单源，不支持 playlist 操作 ----

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = Unit

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) = Unit

    /** 池 hardReset 的 ijk 清源钩子：丢弃当前源并归零状态（BasePlayer.clearMediaItems 为 final）。 */
    fun clearIjkSource() {
        source = null
        sourceDurationMs = 0L
        sourceIsLive = false
        sourceMediaItem = null
        currentTimeline = Timeline.EMPTY
        listeners.forEach {
            it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
        val p = ijk
        if (p != null) runCatching { p.stop() }
        prepared = false
        buffering = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        setStateInternal(Player.STATE_IDLE)
    }

    // ---- Tracks / 参数 / 杂项 ----

    override fun getCurrentTracks(): Tracks = Tracks.EMPTY

    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackSelectionParametersInternal

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        // ijk 无轨选择概念：仅记录参数。后台禁视频轨等操作不生效（后台继续解码播放，
        // 回前台 seekTo 当前位置为无操作），行为可接受。
        if (trackSelectionParametersInternal == parameters) return
        trackSelectionParametersInternal = parameters
        listeners.forEach { it.onTrackSelectionParametersChanged(parameters) }
    }

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) = Unit

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getMediaMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = Unit

    override fun getSeekBackIncrement(): Long = 10_000L

    override fun getSeekForwardIncrement(): Long = 10_000L

    override fun getMaxSeekToPreviousPosition(): Long = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS

    override fun getAvailableCommands(): Player.Commands = AVAILABLE_COMMANDS

    private val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_PREPARE)
        .add(Player.COMMAND_STOP)
        .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_SET_VOLUME)
        .add(Player.COMMAND_GET_VOLUME)
        .add(Player.COMMAND_SET_VIDEO_SURFACE)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .add(Player.COMMAND_GET_TRACKS)
        .add(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        .add(Player.COMMAND_RELEASE)
        .build()

    // ---- 广告 / 设备音量 / 会话等未用能力：空实现 ----

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getDeviceInfo(): androidx.media3.common.DeviceInfo = androidx.media3.common.DeviceInfo.UNKNOWN

    override fun getSurfaceSize(): androidx.media3.common.util.Size = androidx.media3.common.util.Size.ZERO

    override fun getDeviceVolume(): Int = 1

    override fun isDeviceMuted(): Boolean = false

    override fun setDeviceVolume(volume: Int) = Unit

    override fun setDeviceVolume(volume: Int, deviceType: Int) = Unit

    override fun increaseDeviceVolume() = Unit

    override fun increaseDeviceVolume(deviceType: Int) = Unit

    override fun decreaseDeviceVolume() = Unit

    override fun decreaseDeviceVolume(deviceType: Int) = Unit

    override fun setDeviceMuted(muted: Boolean) = Unit

    override fun setDeviceMuted(muted: Boolean, deviceType: Int) = Unit

    override fun mute() = Unit

    override fun unmute() = Unit

    // ==================== ijk 内部机制 ====================

    private fun ensurePlayer() {
        if (ijk != null) return
        runCatching { IjkMediaPlayer.loadLibrariesOnce(DEFAULT_LIB_LOADER) }
            .onFailure { t ->
                AppLog.w(TAG, "loadLibrariesOnce failed: ${t.message}", t)
                throw t
            }
        runCatching { IjkMediaPlayer.native_profileBegin("libijkplayer.so") }
        val p = runCatching { IjkMediaPlayer(DEFAULT_LIB_LOADER) }
            .getOrElse { t ->
                AppLog.w(TAG, "create IjkMediaPlayer failed: ${t.message}", t)
                throw t
            }
        ijk = p
        p.setOnPreparedListener {
            prepared = true
            buffering = false
            // 新流就位：重置看门狗基准与恢复预算，给这次连接完整的观察窗口。
            lastWatchdogPosMs = Long.MIN_VALUE
            lastWatchdogAdvanceAtMs = android.os.SystemClock.elapsedRealtime()
            liveStallRecoveries = 0
            val preparedSeekMs = pendingSeekMs?.coerceAtLeast(0L)
            if (preparedSeekMs != null) {
                markVisibleSeekPosition(preparedSeekMs, clearsOnFirstFrame = false)
            }
            setStateInternal(Player.STATE_READY)
            runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
            runCatching { p.setSpeed(speedInternal) }
            preparedSeekMs?.let { target ->
                pendingSeekMs = null
                runCatching { p.seekTo(target) }.onFailure { clearVisibleSeekPosition() }
            }
            syncPlayWhenReadyToNative(p)
        }
        p.setOnCompletionListener {
            prepared = true
            buffering = false
            clearVisibleSeekPosition()
            setStateInternal(Player.STATE_ENDED)
        }
        p.setOnErrorListener { _, what, extra ->
            prepared = false
            buffering = false
            clearVisibleSeekPosition()
            setStateInternal(Player.STATE_IDLE)
            dispatchError(IjkPlaybackException(what, extra))
            true
        }
        p.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    buffering = true
                    setStateInternal(Player.STATE_BUFFERING)
                }

                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    buffering = false
                    setStateInternal(if (prepared) Player.STATE_READY else Player.STATE_BUFFERING)
                    syncPlayWhenReadyToNative(p)
                }

                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    if (visibleSeekClearsOnFirstFrame) clearVisibleSeekPosition()
                    listeners.forEach { it.onRenderedFirstFrame() }
                }

                IMediaPlayer.MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE,
                IMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START,
                IMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START,
                -> {
                    clearVisibleSeekPosition()
                    dispatchPositionDiscontinuity(currentPosition)
                    settleSeekIfReady(p)
                }
            }
            false
        }
        p.setOnSeekCompleteListener {
            clearVisibleSeekPosition()
            dispatchPositionDiscontinuity(currentPosition)
            settleSeekIfReady(p)
        }
        p.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            videoSizeInternal = VideoSize(width.coerceAtLeast(0), height.coerceAtLeast(0))
            listeners.forEach { it.onVideoSizeChanged(videoSizeInternal) }
        }
        p.setOnBufferingUpdateListener { _, _ -> }
        startStatsTicker(p)
    }

    private var statsRunnable: Runnable? = null
    private var tickCount = 0
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 音画漂移检测阈值（vCache - aCache，毫秒）。人耳察觉阈值约 ±300ms，此处设 3000ms——
     * 超过即漂移已实质影响体验；漂移由 50fps 内容在 60Hz 节奏下时钟错配单调累积所致，
     * 且全志 OMX 硬解路径下 framedrop 丢帧追同步不生效（实验 3/4 证实），只能软重锚。
     */
    private val DRIFT_WARN_THRESHOLD_MS = 3_000L

    /** 漂移超过该阈值且为直播源时触发软重锚（丢积压、贴实时）。 */
    private val REANCHOR_DRIFT_THRESHOLD_MS = 3_000L

    /** 完整软重锚的最小间隔：该路径要把播放器拆了重连（实测约 4~5 秒冻结），必须保持稀疏。 */
    private val REANCHOR_MIN_INTERVAL_MS = 45_000L

    /**
     * 解码器 sustain 不住的降级信号阈值（直播）：driftMs 持续超过该值说明解码速率低于
     * 内容帧率（实测 fpsDec ~45 对 60fps 内容，画面每秒比实时慢 ~200ms），软重锚只能
     * 短暂重置、随即重新累积——正解是降画质（低档是平台降帧流，解码器可 sustain）。
     */
    private val LIVE_DESYNC_FALLBACK_MS = 3_000L

    /** 降级信号去抖：连续 N 个采样周期超阈值才上报，排除缓冲充放的瞬时波动。 */
    private val LIVE_DESYNC_FALLBACK_DEBOUNCE = 2

    /** 降级信号最小间隔：给切换画质后的稳定观察留时间。 */
    private val LIVE_DESYNC_FALLBACK_MIN_INTERVAL_MS = 60_000L

    private var liveDesyncConsecutive = 0
    private var lastLiveDesyncSignalAtMs = 0L

    /**
     * 直播解码追不上的通知（driftMs 持续超阈）。由 UI 层接管：沿画质阶梯降一档。
     * 传当前 driftMs 供日志与策略使用。
     */
    var onLiveDecodeStarved: ((driftMs: Long) -> Unit)? = null

    private var lastReanchorAtMs = 0L

    private var reanchoring = false

    /**
     * 直播假死看门狗。两类假死均实测发生且都不会自愈：
     * - prepare 假死：软重锚的 prepareAsync 发出后永不回调（17:27 实测 pos=0 画面冻结
     *   12 分钟，弹幕仍在流、无任何错误上报，用户只能手动退出）；
     * - 播放假死：prepared + playWhenReady 但位置长时间不前进（断流/解码器卡死）。
     * 处置统一为按标准错误上报，由上层重新拉流并重建引擎（LivePlayerFragment 的重试链）。
     */
    private val LIVE_PREPARE_TIMEOUT_MS = 15_000L
    private val LIVE_STALL_WATCHDOG_MS = 10_000L

    /** 看门狗自恢复次数上限：超过后交回上层重试链（Fragment 重试 3 次后报错），避免无限重拉。 */
    private val MAX_LIVE_STALL_RECOVERIES = 3

    private var prepareStartedAtMs = 0L
    private var lastWatchdogPosMs = Long.MIN_VALUE
    private var lastWatchdogAdvanceAtMs = 0L
    private var liveStallRecoveries = 0

    /**
     * 直播漂移修正入口（统计 ticker 每 5 秒调用）。
     *
     * 试过但已废弃的路线（留档，避免重复踩）：
     * - seek 到直播边缘：B 站直播 FLV 的 CDN 不支持 range 定位，avformat_seek_file
     *   直接 "error while seeking"，且要 15 秒才失败；期间音频时钟被拨到目标但视频
     *   队列未被冲刷，漂移不降反升，还会触发看门狗重拉流。已移除。
     * - framedrop：ijk 的 framedrop=1 是"丢一帧保一帧"（continuous_frame_drops_early
     *   与 framedrop 比较），固定丢 50%，无法按需丢 2%。 framedrop=0 保帧率。
     * - playback_rate（音频跟随视频）：视频侧 setpts 受编译开关控制、MediaCodec 路径
     *   下不生效，且两个队列仍会同步增长撞上 max_cached_duration，无法真正同步。
     *
     * 结论：解码器 sustain 不住时，全志 OMX 上唯一能清掉视频积压的动作就是换流重连。
     */
    private fun correctLiveDriftIfNeeded(vCache: Long, aCache: Long) {
        reanchorLiveIfDrifting(vCache, aCache)
    }

    /**
     * 软重锚（seek 修正无效时的回退路径）：直播源下视频积压（driftMs）失控时，在同一个源上
     * stop/reset 后重挂 dataSource 并 prepareAsync。日志证实换流是唯一能清掉全志 OMX 积压的
     * 动作（vCache 立即归零），但实测会把主线程堵 15~30 秒（统计心跳空洞），伴随屏保被
     * 触发、播放被系统掐停的风险，故只在 seek 路线被判无效后使用，且必须限频。
     */
    private fun reanchorLiveIfDrifting(vCache: Long, aCache: Long) {
        if (reanchoring || !prepared || !sourceIsLive) return
        val driftMs = vCache - aCache
        if (driftMs <= REANCHOR_DRIFT_THRESHOLD_MS) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReanchorAtMs < REANCHOR_MIN_INTERVAL_MS) return
        val currentSource = source ?: return
        val p = ijk ?: return
        reanchoring = true
        lastReanchorAtMs = now
        // 重锚等于重新连接：重置墙钟基准，否则 aLiveMs 会把重锚前的滞后累计进去。
        liveConnectedAtElapsedMs = now
        AppLog.w(
            TAG,
            "ijk_reanchor 直播漂移软重锚 driftMs=$driftMs (vCache=$vCache aCache=$aCache) source=${currentSource::class.simpleName}",
        )
        runCatching {
            p.stop()
            p.reset()
        }.onFailure { t ->
            AppLog.w(TAG, "reanchor stop/reset failed: ${t.message}", t)
            reanchoring = false
            return
        }
        prepared = false
        buffering = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        applyCommonOptions(p)
        runCatching { p.setSurface(videoSurface) }
        runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
        runCatching { p.setSpeed(speedInternal) }
        try {
            applyHttpOptions(p)
            when (currentSource) {
                is IjkPlaybackSource.Dash -> {
                    // DASH 点播不属漂移场景（sourceIsLive 已过滤），理论上走不到。
                    val mpdFile = writeDashMpd(currentSource)
                    p.setDataSource(mpdFile.absolutePath)
                }

                is IjkPlaybackSource.Direct -> {
                    runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 3000L) }
                    // 与 setIjkSource 保持一致：软重锚重挂源时同样掐断预读（1.5MB）。
                    runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 1_500_000L) }
                    runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fast", 1L) }
                    p.setDataSource(currentSource.url)
                }
            }
            preparing = true
            prepareStartedAtMs = android.os.SystemClock.elapsedRealtime()
            setStateInternal(Player.STATE_BUFFERING)
            p.prepareAsync()
        } catch (t: Throwable) {
            AppLog.w(TAG, "reanchor setDataSource/prepare failed: ${t.message}", t)
            preparing = false
            reanchoring = false
            dispatchError(t)
            return
        }
        reanchoring = false
    }

    /**
     * 直播假死看门狗（每 5 秒由统计 ticker 驱动）。两类假死都不会自愈，只能靠重新拉流：
     * prepare 假死——prepareAsync 后超时未 prepared（重锚换流后实测卡死 12 分钟）；
     * 播放假死——prepared + playWhenReady 但位置停滞超时（断流/解码器卡死）。
     * 恢复手段是派发标准 PlaybackException，走 LivePlayerFragment 的 onPlayerError 重试链
     * （重新拉流，必要时重建引擎）。恢复预算用尽后静默交回上层，由 Fragment 的重试计数收口。
     */
    private fun checkLivePlaybackHealth(p: IjkMediaPlayer) {
        if (!sourceIsLive) return
        val now = android.os.SystemClock.elapsedRealtime()

        // 1) prepare 假死：preparing 已久但仍未 prepared。重锚进行中不算（它会走完换流流程）。
        if (preparing && !prepared && !reanchoring && prepareStartedAtMs > 0L &&
            now - prepareStartedAtMs > LIVE_PREPARE_TIMEOUT_MS
        ) {
            if (liveStallRecoveries >= MAX_LIVE_STALL_RECOVERIES) return
            liveStallRecoveries++
            val stalledMs = now - prepareStartedAtMs
            android.util.Log.w(
                TAG,
                "ijk_watchdog prepare 假死 ${stalledMs}ms 未 prepared，上报错误触发重新拉流" +
                    "（第 $liveStallRecoveries/$MAX_LIVE_STALL_RECOVERIES 次）",
            )
            preparing = false
            prepareStartedAtMs = 0L
            dispatchError(
                PlaybackException(
                    "live prepare stalled ${stalledMs}ms",
                    null,
                    PlaybackException.ERROR_CODE_TIMEOUT,
                ),
            )
            return
        }

        // 2) 播放假死：已 prepared 且在播，位置却不动。
        if (!prepared || reanchoring || !playWhenReadyInternal) return
        val pos = p.currentPosition
        if (pos != lastWatchdogPosMs) {
            lastWatchdogPosMs = pos
            lastWatchdogAdvanceAtMs = now
            liveStallRecoveries = 0
            return
        }
        if (now - lastWatchdogAdvanceAtMs < LIVE_STALL_WATCHDOG_MS) return
        if (liveStallRecoveries >= MAX_LIVE_STALL_RECOVERIES) return
        liveStallRecoveries++
        val stalledMs = now - lastWatchdogAdvanceAtMs
        android.util.Log.w(
            TAG,
            "ijk_watchdog 播放假死 pos=$pos 停滞 ${stalledMs}ms，上报错误触发重新拉流" +
                "（第 $liveStallRecoveries/$MAX_LIVE_STALL_RECOVERIES 次）",
        )
        // 推进基准，避免下一周期连续重复上报同一次停滞。
        lastWatchdogAdvanceAtMs = now
        dispatchError(
            PlaybackException(
                "live playback stalled at $pos for ${stalledMs}ms",
                null,
                PlaybackException.ERROR_CODE_TIMEOUT,
            ),
        )
    }

    /** 每 5 秒输出内核运行状态：帧率/缓冲/TCP 速度/音画漂移，用于 ADB logcat 远程定位。 */
    private fun startStatsTicker(p: IjkMediaPlayer) {
        stopStatsTicker()
        val runnable = object : Runnable {
            override fun run() {
                if (ijk !== p) return
                runCatching {
                    val vCache = p.videoCachedDuration.coerceAtLeast(0L)
                    val aCache = p.audioCachedDuration.coerceAtLeast(0L)
                    val driftMs = vCache - aCache
                    // 客观音画同步遥测（替代人耳感知）：
                    // pfRate   = ijk 内部播放速率估计，1.0=视频时钟追齐实时；<1 即视频在变慢
                    // dropRate = 迟到帧丢弃率，>0 即渲染器在丢帧追同步
                    // aLiveMs  = 音频相对直播边缘的滞后（连接时刻 + currentPosition 推算），
                    //            即"声音比现场慢多少秒"；vLiveMs 为画面滞后，二者之差即
                    //            画面与声音的客观偏移量。
                    val pfRate = readIjkPropertyFloat(p, IjkMediaPlayer.FFP_PROP_FLOAT_PLAYBACK_RATE)
                    val dropRate = readIjkPropertyFloat(p, IjkMediaPlayer.FFP_PROP_FLOAT_DROP_FRAME_RATE)
                    val audioLiveMs = liveAudioLagMs(p.currentPosition)
                    val videoLiveMs = audioLiveMs + driftMs
                    val msg =
                        "ijk_stats fpsOut=${p.videoOutputFramesPerSecond} fpsDec=${p.videoDecodeFramesPerSecond} " +
                            "pos=${p.currentPosition} vCache=$vCache aCache=$aCache driftMs=$driftMs " +
                            "pfRate=${"%.3f".format(pfRate)} dropRate=${"%.3f".format(dropRate)} " +
                            "aLiveMs=$audioLiveMs vLiveMs=$videoLiveMs " +
                            "tcp=${p.tcpSpeed} bitrate=${p.bitRate}"
                    // 直写 logcat，不受 AppLog"调试日志"开关门禁（远程诊断依赖）。
                    android.util.Log.i(TAG, msg)
                    if (driftMs > DRIFT_WARN_THRESHOLD_MS) {
                        android.util.Log.w(TAG, "ijk_drift 音画漂移超阈值 driftMs=$driftMs (vCache=$vCache aCache=$aCache)")
                    }
                    notifyLiveDecodeStarvedIfNeeded(driftMs)
                    correctLiveDriftIfNeeded(vCache, aCache)
                    checkLivePlaybackHealth(p)
                }
                statsHandler.postDelayed(this, 5_000L)
            }
        }
        statsRunnable = runnable
        statsHandler.postDelayed(runnable, 5_000L)
    }

    /**
     * 直播解码追不上检测：driftMs 连续超阈（去抖 2 个周期）且过了最小间隔时通知 UI 降画质。
     * 仅直播源有效；重锚进行中不重复上报。
     */
    private fun notifyLiveDecodeStarvedIfNeeded(driftMs: Long) {
        if (!sourceIsLive) {
            liveDesyncConsecutive = 0
            return
        }
        if (driftMs > LIVE_DESYNC_FALLBACK_MS) {
            liveDesyncConsecutive++
        } else {
            liveDesyncConsecutive = 0
            return
        }
        if (liveDesyncConsecutive < LIVE_DESYNC_FALLBACK_DEBOUNCE) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLiveDesyncSignalAtMs < LIVE_DESYNC_FALLBACK_MIN_INTERVAL_MS) return
        lastLiveDesyncSignalAtMs = now
        liveDesyncConsecutive = 0
        AppLog.w(TAG, "ijk_decode_starved 直播解码追不上，请求降画质 driftMs=$driftMs")
        onLiveDecodeStarved?.invoke(driftMs)
    }

    /** 反射调用 ijk 私有 native _getPropertyFloat（FFP_PROP_FLOAT_* 无公开 getter）。 */
    private fun readIjkPropertyFloat(p: IjkMediaPlayer, prop: Int): Float {
        return runCatching {
            val m = IjkMediaPlayer::class.java.getDeclaredMethod(
                "_getPropertyFloat",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            m.isAccessible = true
            (m.invoke(p, prop, 0f) as Number).toFloat()
        }.getOrDefault(-1f)
    }

    /** 直播连接建立的墙钟时刻（setIjkSource 时记录），用于推算音频的实时滞后。 */
    private var liveConnectedAtElapsedMs = 0L

    /** 音频当前位置相对直播边缘的滞后毫秒数；非直播或未记录连接时刻返回 -1。 */
    private fun liveAudioLagMs(positionMs: Long): Long {
        if (!sourceIsLive || liveConnectedAtElapsedMs <= 0L) return -1L
        val elapsed = android.os.SystemClock.elapsedRealtime() - liveConnectedAtElapsedMs
        // elapsed 为连接至今的墙钟时长，position 为主时钟（音频）推进的内容时长；
        // 差值即音频落后直播边缘的量（连接瞬间二者都从 0 起算）。
        return (elapsed - positionMs).coerceAtLeast(0L)
    }

    private fun stopStatsTicker() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable = null
    }

    private fun applyCommonOptions(p: IjkMediaPlayer) {
        // 硬解优先：MediaCodec AVC/HEVC；软解兜底交给解码器回退。
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L) }
        // 关键修复：ijk 默认 max-fps=31，任何上报帧率 >31 的流会被标记 high_fps，
        // 强制解码器 skip_frame=NONREF（丢全部 B 帧）+跳 IDCT/环路滤波——60fps 直播
        // （FLV 元数据显式上报 60fps）解码输出因此掉到 ~45fps，内容位置追不上实时，
        // 表现为画面越来越旧/音画不同步。抬到 61 让 60fps 流走完整解码路径。
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 61L) }
        // framedrop=0：framedrop=1 实测输出仅 ~23fps（dropRate=0.48，近半帧被丢）、
        // 且漂移照涨——全志 OMX 上丢帧机制不可用，两次实验（4/7）证实。保帧率优先，
        // 漂移治理见 driftMs 软重锚与音频跟随策略。
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", 0L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 20_000_000L) }
        // 允许本地 MPD（file://）引用远端 https 分段。
        runCatching {
            p.setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "protocol_whitelist",
                "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data",
            )
        }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL") }
    }

    private fun applyHttpOptions(p: IjkMediaPlayer) {
        // UA 走 user_agent 选项（避免与 headers 重复），Referer/Origin 走 headers 透传。
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", MEDIA_USER_AGENT) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user-agent", MEDIA_USER_AGENT) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", MEDIA_HEADERS_STRING) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "referer", "https://www.bilibili.com/") }
    }

    private fun syncPlayWhenReadyToNative(p: IjkMediaPlayer) {
        if (prepared) {
            runCatching {
                if (playWhenReadyInternal) p.start() else p.pause()
            }.onFailure { AppLog.w(TAG, "sync playWhenReady failed: ${it.message}") }
        }
        notifyIsPlayingChanged()
    }

    private fun settleSeekIfReady(p: IjkMediaPlayer) {
        if (prepared && !buffering && playbackStateInternal == Player.STATE_BUFFERING) {
            setStateInternal(Player.STATE_READY)
        }
        if (playbackStateInternal == Player.STATE_READY) {
            syncPlayWhenReadyToNative(p)
        } else {
            notifyIsPlayingChanged()
        }
    }

    private fun startPrepareIfPossible() {
        val p = ijk ?: return
        if (prepared || preparing || !prepareRequested) return
        if (source == null) return
        if (videoSurface == null) {
            // MediaCodec 路径需要有效 Surface，等 surface 挂载后再 prepare（对齐上游实现）。
            if (playbackStateInternal != Player.STATE_BUFFERING) {
                setStateInternal(Player.STATE_BUFFERING)
            }
            return
        }
        preparing = true
        prepareStartedAtMs = android.os.SystemClock.elapsedRealtime()
        setStateInternal(Player.STATE_BUFFERING)
        runCatching { p.prepareAsync() }
            .onFailure { t ->
                preparing = false
                clearVisibleSeekPosition()
                setStateInternal(Player.STATE_IDLE)
                dispatchError(t)
            }
    }

    private var preparing = false

    private fun writeDashMpd(dash: IjkPlaybackSource.Dash): File {
        val mpd = IjkDashMpdGenerator.buildOnDemandMpd(dash)
        val key = buildString {
            append(dash.video.url)
            append('|')
            append(dash.video.initialization)
            append('|')
            append(dash.video.indexRange)
            append('|')
            append(dash.audio.url)
            append('|')
            append(dash.audio.initialization)
            append('|')
            append(dash.audio.indexRange)
            append('|')
            append(dash.video.id)
            append('|')
            append(dash.video.codecId)
            append('|')
            append(dash.audio.id)
            append('|')
            append(dash.durationMs)
        }
        val file = File(appContext.cacheDir, "btelly_ijk_${md5Hex(key)}.mpd")
        file.writeText(mpd, Charsets.UTF_8)
        return file
    }

    private fun markVisibleSeekPosition(positionMs: Long, clearsOnFirstFrame: Boolean) {
        visibleSeekPositionMs = positionMs.coerceAtLeast(0L)
        visibleSeekStartedAtMs = android.os.SystemClock.elapsedRealtime()
        visibleSeekClearsOnFirstFrame = clearsOnFirstFrame
    }

    private fun clearVisibleSeekPosition() {
        visibleSeekPositionMs = null
        visibleSeekStartedAtMs = 0L
        visibleSeekClearsOnFirstFrame = false
    }

    private fun visibleSeekPositionOrNull(): Long? {
        val position = visibleSeekPositionMs ?: return null
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - visibleSeekStartedAtMs
        if (!visibleSeekClearsOnFirstFrame && elapsedMs > VISIBLE_SEEK_POSITION_TIMEOUT_MS) {
            clearVisibleSeekPosition()
            return null
        }
        return position
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { b -> append(String.format(Locale.US, "%02x", b)) }
        }
    }

    internal class IjkPlaybackException(
        val what: Int,
        val extra: Int,
    ) : RuntimeException("IjkMediaPlayer error what=$what extra=$extra")
}

/**
 * 单窗口静态 Timeline：ijk 单源模型。时长/可拖动性来自当前源，
 * 供 BasePlayer 的 getCurrentMediaItem/isCurrentMediaItemSeekable 等 final 方法
 * 以及 MyPlayerControlView 的时间条正确工作。
 */
private class IjkSingleWindowTimeline(
    private val mediaItem: MediaItem,
    private val durationUs: Long,
    private val isLive: Boolean,
) : Timeline() {

    private val uid: Any = "ijk_single_window"

    override fun getWindowCount(): Int = 1

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        window.set(
            uid,
            mediaItem,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            /* isSeekable = */ !isLive,
            /* isDynamic = */ isLive,
            if (isLive) MediaItem.LiveConfiguration.Builder().build() else MediaItem.LiveConfiguration.UNSET,
            /* defaultPositionUs = */ 0L,
            durationUs,
            /* firstPeriodIndex = */ 0,
            /* lastPeriodIndex = */ 0,
            /* positionInFirstPeriodUs = */ 0L,
        )
        return window
    }

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        C.INDEX_UNSET

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        C.INDEX_UNSET

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = 0

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = 0

    override fun getPeriodCount(): Int = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(
            /* id = */ null,
            /* uid = */ uid,
            /* periodIndex = */ 0,
            /* durationUs = */ durationUs,
            /* positionInWindowUs = */ 0L,
        )

    override fun getIndexOfPeriod(uid: Any): Int = if (uid == this.uid) 0 else C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = uid
}
