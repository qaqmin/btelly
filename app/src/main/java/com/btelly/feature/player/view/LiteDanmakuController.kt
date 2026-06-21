package com.btelly.feature.player.view

import android.content.Context
import android.graphics.Color
import androidx.media3.common.Player
import com.btelly.core.common.log.AppLog
import com.btelly.feature.player.DanmakuFilterContext
import com.btelly.feature.player.PlaybackStartupTrace
import com.btelly.model.dm.DmModel

/**
 * 轻量弹幕控制器（性能优先引擎）。
 *
 * 和 [MyPlayerDanmakuController] 提供**同形 API**，让 [MyPlayerView] 用相同的调用模式
 * 驱动两个引擎（功能优先=原 AkDanmaku，性能优先=本类 + [LiteDanmakuView]）。
 *
 * 职责：
 *  - 数据预处理：过滤 mode 7/9（[BiliDanmakuFilterPolicy]）+ 合并重复
 *    （[DanmakuDuplicateMergePolicy]）+ 预计算宽高（复用 [LiteDanmakuView.measureText]）。
 *  - 设置映射：把 [MyPlayerDanmakuController.SettingsSnapshot] 翻译成 LiteDanmakuView 的配置。
 *  - 播放同步：时钟推算（basePosition + elapsed * speed），照搬 SpecialDanmakuOverlayView。
 *
 * 不支持（性能优先模式）：直播、VIP 渐变（降级纯色）、特殊弹幕（已过滤）、防挡蒙版、智能过滤。
 */
class LiteDanmakuController(
    private val context: Context,
    private val viewProvider: () -> LiteDanmakuView?
) {

    companion object {
        private const val TAG = "LiteDmCtrl"
        private const val MODE_ROLLING = 1
        private const val MODE_BOTTOM = 4
        private const val MODE_TOP = 5
    }

    var playerPositionProvider: (() -> Long)? = null

    private var rawItems: List<DmModel> = emptyList()
    private var filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var lastSnapshot: MyPlayerDanmakuController.SettingsSnapshot? = null

    // 设置（由 applySettings 写入，预计算字号时用）
    private var textScale = 1f
    private var durationMs = 8000L
    private var screenPart = 1f
    private var mergeDuplicate = true
    private var enabled = true
    private var isPlaying = false
    private var playbackSpeed = 1f

    init {
    }

    fun setData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY,
        @Suppress("UNUSED_PARAMETER") startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        @Suppress("UNUSED_PARAMETER") startupTraceStartElapsedMs: Long = 0L
    ) {
        this.filterContext = filterContext
        rawItems = data.sortedBy { it.progress }
        applyDataToView()
        // 首次设数据后用当前播放位置同步时钟
        syncClockFromProvider()
    }

    fun appendData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    ) {
        if (data.isEmpty()) return
        this.filterContext = filterContext
        rawItems = mergeSorted(rawItems, data)
        applyDataToView()
    }

    fun applySettings(snapshot: MyPlayerDanmakuController.SettingsSnapshot) {
        if (lastSnapshot == snapshot) return
        applySettingsInternal(snapshot)
    }

    private fun applySettingsInternal(snapshot: MyPlayerDanmakuController.SettingsSnapshot) {
        lastSnapshot = snapshot
        enabled = snapshot.enabled
        textScale = snapshot.textSize.toLiteTextScale()
        durationMs = snapshot.speed.toLiteDurationMs()
        screenPart = snapshot.screenArea.toLiteScreenPart()
        mergeDuplicate = snapshot.mergeDuplicate
        val view = viewProvider() ?: return
        // durationMs 直接由设置面板的"滚动速度"决定（toLiteDurationMs），不做屏幕宽度调整。
        // 轻量引擎追求简单直接，不引入老版 viewportSizeFactor 那层复杂度。
        view.setRenderingConfig(
            enabled = snapshot.enabled,
            alpha = snapshot.alpha,
            textScale = textScale,
            screenPart = screenPart,
            allowTop = snapshot.allowTop,
            allowBottom = snapshot.allowBottom,
            durationMs = durationMs
        )
        // 字号变化需重新预计算宽高
        if (rawItems.isNotEmpty()) {
            applyDataToView()
        }
    }

    fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceAtLeast(0.1f)
        viewProvider()?.syncPlayback(currentClockPosition(), isPlaying, playbackSpeed)
    }

    fun notifyPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (playWhenReady) isPlaying = false
            }
            Player.STATE_READY -> {
                isPlaying = playWhenReady
            }
            Player.STATE_ENDED -> isPlaying = false
        }
        viewProvider()?.syncPlayback(currentClockPosition(), isPlaying, playbackSpeed)
    }

    fun notifyIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        viewProvider()?.syncPlayback(currentClockPosition(), isPlaying, playbackSpeed)
    }

    @Suppress("UNUSED_PARAMETER")
    fun notifyPlaybackFirstFrame() {
        // 首帧后同步一次时钟，确保弹幕起点对齐
        syncClockFromProvider()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        val snap = lastSnapshot ?: return
        applySettings(snap.copy(enabled = enabled))
    }

    fun pause() {
        isPlaying = false
        viewProvider()?.syncPlayback(currentClockPosition(), false, playbackSpeed)
    }

    fun resume() {
        syncClockFromProvider()
        isPlaying = true
        viewProvider()?.syncPlayback(currentClockPosition(), true, playbackSpeed)
    }

    fun stop() {
        isPlaying = false
        rawItems = emptyList()
        viewProvider()?.let {
            it.syncPlayback(0L, false, playbackSpeed)
            it.clearItems()
        }
    }

    fun resetForPlaybackStart(positionMs: Long) {
        stop()
        syncClockTo(positionMs.coerceAtLeast(0L))
    }

    fun syncPosition(positionMs: Long, forceSeek: Boolean) {
        if (forceSeek) {
            syncClockTo(positionMs.coerceAtLeast(0L))
            // seek 后清掉旧弹幕的 lane 绑定，让它们在新位置重新分配（防重叠）
            viewProvider()?.seekReset()
        }
    }

    fun release() {
        stop()
    }

    // ---- 内部 ----

    private fun applyDataToView() {
        val view = viewProvider() ?: return
        if (rawItems.isEmpty()) {
            view.clearItems()
            return
        }
        // 1. 过滤（复用现有策略，engine 无关）
        val filtered = BiliDanmakuFilterPolicy.apply(
            items = rawItems,
            context = filterContext,
            settings = lastSnapshot,
            stage = "lite"
        )
        // 2. 合并重复（复用现有策略）
        val prepared = if (mergeDuplicate) DanmakuDuplicateMergePolicy.merge(filtered) else filtered
        if (mergeDuplicate && prepared.size < filtered.size) {
            AppLog.i(
                TAG,
                "merge reduced: filtered=${filtered.size} merged=${prepared.size} " +
                    "dropped=${filtered.size - prepared.size}"
            )
        }
        // 3. 预计算宽高，转成 RollingItem
        val rollingItems = ArrayList<LiteDanmakuView.RollingItem>(prepared.size)
        for (item in prepared) {
            val renderContent = item.toRenderableContent() ?: continue
            val textSizePx = item.fontSize.toLiteTextSizePx(view.densityFactor(), textScale)
            val (width, height) = view.measureText(renderContent, textSizePx)
            rollingItems.add(
                LiteDanmakuView.RollingItem(
                    id = item.stableId(),
                    positionMs = item.progress.toLong().coerceAtLeast(0L),
                    mode = item.mode.toLiteMode(),
                    content = renderContent,
                    color = item.color.toLiteColor(),
                    textSizePx = textSizePx,
                    width = width,
                    height = height
                )
            )
        }
        view.setItems(rollingItems)
        AppLog.i(
            TAG,
            "applied raw=${rawItems.size} filtered=${filtered.size} prepared=${prepared.size} " +
                "rolling=${rollingItems.size} merge=$mergeDuplicate durationMs=$durationMs speed=${lastSnapshot?.speed}"
        )
    }

    private fun syncClockFromProvider() {
        val pos = playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L
        syncClockTo(pos)
    }

    private fun syncClockTo(positionMs: Long) {
        viewProvider()?.syncPlayback(positionMs, isPlaying, playbackSpeed)
    }

    private fun currentClockPosition(): Long {
        return playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L
    }

    /** 归并两条按 progress 升序的弹幕时间线（照搬 MyPlayerDanmakuController.mergeSortedTimelines）。 */
    private fun mergeSorted(existing: List<DmModel>, incoming: List<DmModel>): List<DmModel> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.sortedBy { it.progress }
        val sortedIncoming = if (incoming.size <= 1) incoming else incoming.sortedBy { it.progress }
        val result = ArrayList<DmModel>(existing.size + sortedIncoming.size)
        var i = 0; var j = 0
        while (i < existing.size && j < sortedIncoming.size) {
            if (existing[i].progress <= sortedIncoming[j].progress) {
                result.add(existing[i]); i++
            } else {
                result.add(sortedIncoming[j]); j++
            }
        }
        while (i < existing.size) { result.add(existing[i]); i++ }
        while (j < sortedIncoming.size) { result.add(sortedIncoming[j]); j++ }
        return result
    }

    private fun DmModel.toRenderableContent(): String? {
        return when {
            content.isBlank() -> null
            mode == 7 || mode == 9 -> null
            content.contains("def text", ignoreCase = true) -> null
            else -> content
        }
    }

    private fun DmModel.stableId(): Long {
        if (id > 0L) return id
        return (progress.toLong() shl 32) xor content.hashCode().toLong()
    }

    private fun Int.toLiteMode(): Int = when (this) {
        MODE_TOP -> MODE_TOP
        MODE_BOTTOM -> MODE_BOTTOM
        else -> MODE_ROLLING
    }

    private fun Int.toLiteColor(): Int = if (this == 0) Color.WHITE else this or 0xFF000000.toInt()

    /**
     * 字号映射（完全对齐 MyPlayerDanmakuController.toDanmakuTextScale，保证两引擎视觉一致）。
     * 设置面板的 KEY_DM_TEXT_SIZE(30-55) → textSizeScale。
     */
    private fun Int.toLiteTextScale(): Float = when (this) {
        30 -> 0.55f; 31 -> 0.6f; 32 -> 0.65f; 33 -> 0.7f; 34 -> 0.75f
        35 -> 0.8f; 36 -> 0.85f; 37 -> 0.9f; 38 -> 0.95f; 39 -> 1.0f
        40 -> 1.14f; 41 -> 1.3f; 42 -> 1.4f; 43 -> 1.5f; 44 -> 1.6f
        45 -> 1.7f; 46 -> 1.8f; 47 -> 2.0f; 48 -> 2.1f; 49 -> 2.2f
        50 -> 2.3f; 51 -> 2.4f; 52 -> 2.5f; 53 -> 2.6f; 54 -> 2.7f; 55 -> 2.8f
        else -> 1.14f
    }

    /** speed(1-9) → 滚动时长 ms（完全对齐 MyPlayerDanmakuController.toDanmakuDurationMs）。 */
    private fun Int.toLiteDurationMs(): Long = when (this) {
        1 -> 12000L; 2 -> 10200L; 3 -> 8400L; 4 -> 7200L; 5 -> 6000L
        6 -> 4800L; 7 -> 3840L; 8 -> 3000L; 9 -> 2160L
        else -> 6600L
    }

    /** screenArea → 屏幕占比（完全对齐 MyPlayerDanmakuController.toDanmakuScreenPart）。 */
    private fun Int.toLiteScreenPart(): Float = when (this) {
        -1 -> 1f / 8f; 0 -> 0.16f; 1 -> 1f / 4f; 3 -> 1f / 2f; 7 -> 3f / 4f; else -> 1f
    }

    /**
     * B 站 fontSize → 像素字号（完全对齐 AkDanmaku SimpleRenderer.updatePaint 公式）：
     *   textSizePx = clamp(biliFontSize, 12, 25) * (density - 0.6) * textSizeScale
     * 不用 applyDimension（那是 SP→PX，会多乘一次 density）。
     */
    private fun Int.toLiteTextSizePx(density: Float, scale: Float): Float {
        val base = coerceIn(12, 25).toFloat()
        return (base * (density - 0.6f).coerceAtLeast(0.4f) * scale).coerceAtLeast(8f)
    }
}
