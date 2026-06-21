package com.btelly.feature.player.danmaku

import android.content.Context
import com.btelly.core.common.log.AppLog
import com.btelly.feature.player.DanmakuFilterContext
import com.btelly.feature.player.PlaybackStartupTrace
import com.btelly.feature.player.view.BiliDanmakuFilterPolicy
import com.btelly.feature.player.view.DanmakuDuplicateMergePolicy
import com.btelly.feature.player.view.MyPlayerDanmakuController
import com.btelly.model.dm.DmModel

/**
 * 上游实现 弹幕引擎适配控制器（性能优先模式）。
 *
 * 和 [com.btelly.feature.player.view.MyPlayerDanmakuController] 提供**同形 API**，
 * 让 [com.btelly.feature.player.view.MyPlayerView] 用相同的调用模式驱动两个引擎
 * （功能优先=原 AkDanmaku，性能优先=本类 + [DanmakuView]）。
 *
 * 职责：
 *  - 数据预处理：过滤（[BiliDanmakuFilterPolicy]）+ 合并重复（[DanmakuDuplicateMergePolicy]）
 *    + DmModel→Danmaku 转换（[toDanmakus]）。
 *  - 设置映射：把 [MyPlayerDanmakuController.SettingsSnapshot] 翻译成引擎的 [DanmakuConfig]。
 *  - 播放同步：通过 positionProvider 回调让引擎自驱动，seek 时主动通知。
 *
 * 不支持（性能优先模式）：直播、VIP 渐变、特殊弹幕（已过滤）、防挡蒙版、智能过滤、表情/高赞图标（stub）。
 */
class DanmakuCoreController(
    private val context: Context,
    private val viewProvider: () -> DanmakuView?
) {

    companion object {
        private const val TAG = "BTellyDmCtrl"
        /**
         * B站弹幕基准字号。protobuf 协议(DmProtoParser)默认 fontSize=25，绝大多数弹幕都是这个值。
         * 对齐 AkDanmaku 的 clamp(biliFontSize, 12, 25) —— 弹幕引擎是全局字号（不读 per-item），
         * 用 25 作基准值才能和 AkDanmaku 视觉一致。
         */
        private const val BILI_BASE_FONT_SIZE = 25f
    }

    /** 屏幕密度，用于对齐 AkDanmaku 字号公式。 */
    private val density: Float = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

    var playerPositionProvider: (() -> Long)? = null

    private var rawItems: List<DmModel> = emptyList()
    private var filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var lastSnapshot: MyPlayerDanmakuController.SettingsSnapshot? = null

    // 缓存的 DanmakuConfig（由 applySettings 计算，通过 configProvider 喂给引擎）
    @Volatile
    private var currentConfig: DanmakuConfig = defaultConfig()

    // isPlaying 状态：用 volatile 字段而不是每次替换 lambda，
    // 避免 notifyPlaybackStateChanged 和 notifyIsPlayingChanged 的事件顺序竞争导致状态错乱。
    @Volatile
    private var isPlaying = false

    init {
        installProviders()
    }

    private fun installProviders() {
        val view = viewProvider() ?: return
        view.setPositionProvider { playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L }
        view.setIsPlayingProvider { isPlaying }
        view.setPlaybackSpeedProvider { 1f } // 引擎内部按播放速度算 duration，这里固定 1
        view.setConfigProvider { currentConfig }
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
    }

    fun appendData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    ) {
        if (data.isEmpty()) return
        this.filterContext = filterContext
        rawItems = mergeSorted(rawItems, data)
        applyDataToView(append = true)
    }

    fun applySettings(snapshot: MyPlayerDanmakuController.SettingsSnapshot) {
        if (lastSnapshot == snapshot) return
        lastSnapshot = snapshot
        currentConfig = buildConfig(snapshot)
        viewProvider()?.visibility = if (snapshot.enabled) android.view.View.VISIBLE else android.view.View.GONE
        // 弹幕引擎的 DanmakuConfig 不含 allowTop/allowBottom/mergeDuplicate——这些靠数据层
        // (BiliDanmakuFilterPolicy + DanmakuDuplicateMergePolicy)在注入前过滤。
        // 所以这些设置变化后必须重新预处理已有数据并重新注入，否则开关不生效。
        // （APP设置-弹幕设置生效是因为它走的是同样的 setData→applyDataToView 路径，会重新过滤。）
        if (rawItems.isNotEmpty()) {
            applyDataToView()
        }
    }

    fun updatePlaybackSpeed(@Suppress("UNUSED_PARAMETER") speed: Float) {
        // 弹幕引擎按 durationMs 推进，播放速度由 positionProvider 推进速率体现，无需额外处理
    }

    fun notifyPlaybackStateChanged(@Suppress("UNUSED_PARAMETER") playbackState: Int, playWhenReady: Boolean) {
        // playWhenReady 作为 isPlaying 的候选值之一（buffering 时 isPlaying=false 会由
        // notifyIsPlayingChanged 覆盖），用 volatile 字段避免事件顺序竞争。
        val wasPlaying = isPlaying
        isPlaying = playWhenReady
        // isPlaying 从 false→true 时必须主动 invalidate，否则引擎 Choreographer 已停，
        // 没有 onDraw 触发就不会重启渲染循环 → 弹幕卡住/消失。
        if (!wasPlaying && playWhenReady) {
            viewProvider()?.invalidate()
        }
    }

    fun notifyIsPlayingChanged(playing: Boolean) {
        // onIsPlayingChanged 是 ExoPlayer 对"实际解码播放中"的权威信号，优先级高于 playWhenReady。
        val wasPlaying = isPlaying
        isPlaying = playing
        if (!wasPlaying && playing) {
            viewProvider()?.invalidate()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun notifyPlaybackFirstFrame() {
        // 首帧渲染是镜像切换/surface 重建后恢复弹幕的关键时机。
        // 此时 isPlaying 可能还是 false（READY/onIsPlayingChanged 尚未回调），
        // 主动 invalidate 触发一次 onDraw，让引擎有机会重启渲染循环；
        // 同时兜底设 isPlaying=true（首帧出来说明解码器已就绪，弹幕应跟随播放）。
        val wasPlaying = isPlaying
        if (!wasPlaying) isPlaying = true
        viewProvider()?.invalidate()
    }

    fun setEnabled(enabled: Boolean) {
        val snap = lastSnapshot ?: return
        applySettings(snap.copy(enabled = enabled))
    }

    fun pause() {
        isPlaying = false
    }

    fun resume() {
        val wasPlaying = isPlaying
        isPlaying = true
        if (!wasPlaying) viewProvider()?.invalidate()
    }

    fun stop() {
        isPlaying = false
        viewProvider()?.setDanmakus(emptyList())
        rawItems = emptyList()
    }

    fun resetForPlaybackStart(positionMs: Long) {
        stop()
        viewProvider()?.notifySeek(positionMs.coerceAtLeast(0L))
    }

    fun syncPosition(positionMs: Long, forceSeek: Boolean) {
        if (forceSeek) {
            // seek 后通知引擎重建场景（清旧弹幕，从新位置重新分配）
            viewProvider()?.notifySeek(positionMs.coerceAtLeast(0L))
        }
        // 非 seek 时靠 positionProvider 自动跟，无需处理
    }

    fun release() {
        stop()
    }

    // ---- 内部 ----

    private fun applyDataToView(append: Boolean = false) {
        val view = viewProvider() ?: return
        if (rawItems.isEmpty()) {
            if (!append) view.setDanmakus(emptyList())
            return
        }
        // 1. 过滤（复用现有策略，engine 无关）
        val filtered = BiliDanmakuFilterPolicy.apply(
            items = rawItems,
            context = filterContext,
            settings = lastSnapshot,
            stage = "danmaku"
        )
        // 2. 合并重复（复用现有策略）
        val mergeDuplicate = lastSnapshot?.mergeDuplicate ?: true
        val prepared = if (mergeDuplicate) DanmakuDuplicateMergePolicy.merge(filtered) else filtered
        if (mergeDuplicate && prepared.size < filtered.size) {
            AppLog.i(
                TAG,
                "merge reduced: filtered=${filtered.size} merged=${prepared.size} " +
                    "dropped=${filtered.size - prepared.size}"
            )
        }
        // 3. 转 Danmaku + 注入引擎
        val danmakus = prepared.toDanmakus()
        if (append && viewHasData()) {
            view.appendDanmakus(danmakus, alreadySorted = true)
        } else {
            view.setDanmakus(danmakus)
        }
        AppLog.i(
            TAG,
            "applied raw=${rawItems.size} filtered=${filtered.size} prepared=${prepared.size} " +
                "danmakus=${danmakus.size} merge=$mergeDuplicate"
        )
    }

    private fun viewHasData(): Boolean = rawItems.isNotEmpty()

    private fun buildConfig(snapshot: MyPlayerDanmakuController.SettingsSnapshot): DanmakuConfig {
        // 字号对齐 AkDanmaku SimpleRenderer.updatePaint 公式：
        //   AkDanmaku textSizePx = clamp(biliFontSize, 12, 25) × (density - 0.6) × textSizeScale
        // 弹幕引擎内部 textSizePx = sp(textSizeSp) = textSizeSp × density
        // 反推: textSizeSp = AkDanmakuPx / density
        val textSizeScale = snapshot.textSize.toDmTextScale()
        val akDanmakuPx = BILI_BASE_FONT_SIZE * (density - 0.6f).coerceAtLeast(0.4f) * textSizeScale
        val textSizeSp = (akDanmakuPx / density).coerceAtLeast(1f)

        // 描边对齐 AkDanmaku resolveStrokeWidth（FONT_BORDER_DEFAULT 模式）：
        //   strokeWidth = (textSizePx × 0.09).coerceIn(1.5, 3)
        val strokeWidthPx = (akDanmakuPx * 0.09f).coerceIn(1.5f, 3f).toInt()

        return DanmakuConfig(
            enabled = snapshot.enabled,
            opacity = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeSp = textSizeSp,
            fontWeight = DanmakuFontWeight.Normal, // AkDanmaku 默认 config.bold=false → Typeface.DEFAULT
            strokeWidthPx = strokeWidthPx,
            speedLevel = snapshot.speed.toDmSpeedLevel(),
            area = snapshot.screenArea.toDmArea(),
            laneDensity = DanmakuLaneDensity.Standard,
            showHighLikeIcon = false,
        )
    }

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = 1f,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Normal,
            strokeWidthPx = 4,
            speedLevel = 5,
            area = 0.5f,
            laneDensity = DanmakuLaneDensity.Standard,
            showHighLikeIcon = false,
        )

    /** 归并两条按 progress 升序的弹幕时间线。 */
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

    /**
     * 设置面板 textSize(30-55) → textSizeScale。
     *
     * 完全对齐 MyPlayerDanmakuController.toDanmakuTextScale 的映射表，
     * 保证两引擎同一档位产生相同比例的字号。
     */
    private fun Int.toDmTextScale(): Float = when (this) {
        30 -> 0.55f; 31 -> 0.6f; 32 -> 0.65f; 33 -> 0.7f; 34 -> 0.75f
        35 -> 0.8f; 36 -> 0.85f; 37 -> 0.9f; 38 -> 0.95f; 39 -> 1.0f
        40 -> 1.14f; 41 -> 1.3f; 42 -> 1.4f; 43 -> 1.5f; 44 -> 1.6f
        45 -> 1.7f; 46 -> 1.8f; 47 -> 2.0f; 48 -> 2.1f; 49 -> 2.2f
        50 -> 2.3f; 51 -> 2.4f; 52 -> 2.5f; 53 -> 2.6f; 54 -> 2.7f; 55 -> 2.8f
        else -> 1.14f
    }

    /** speed(1-9) → 引擎 speedLevel(1-10)。直接对应，9 以上不动（不使用 level 10）。 */
    private fun Int.toDmSpeedLevel(): Int = coerceIn(1, 10)

    /**
     * screenArea → 引擎 area(0-1)。
     * 对齐 MyPlayerDanmakuController.toDanmakuScreenPart 的映射。
     */
    private fun Int.toDmArea(): Float = when (this) {
        -1 -> 1f / 8f
        0 -> 0.16f
        1 -> 1f / 4f
        3 -> 1f / 2f
        7 -> 3f / 4f
        else -> 1f
    }
}
