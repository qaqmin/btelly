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
    }

    var playerPositionProvider: (() -> Long)? = null

    private var rawItems: List<DmModel> = emptyList()
    private var filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var lastSnapshot: MyPlayerDanmakuController.SettingsSnapshot? = null

    // 缓存的 DanmakuConfig（由 applySettings 计算，通过 configProvider 喂给引擎）
    @Volatile
    private var currentConfig: DanmakuConfig = defaultConfig()

    init {
        installProviders()
    }

    private fun installProviders() {
        val view = viewProvider() ?: return
        view.setPositionProvider { playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L }
        view.setIsPlayingProvider { false } // 由 notifyIsPlayingChanged 实时更新
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
        // 配置变化通过 configProvider 在下一帧 onDraw 自动喂给引擎，无需显式调 updateConfig。
        // 但确保 view 可见性跟随 enabled
        viewProvider()?.visibility = if (snapshot.enabled) android.view.View.VISIBLE else android.view.View.GONE
        // 字号变化需重新预处理（宽高变了，但 弹幕引擎自己 measure，无需重算 item）
    }

    fun updatePlaybackSpeed(@Suppress("UNUSED_PARAMETER") speed: Float) {
        // 弹幕引擎按 durationMs 推进，播放速度由 positionProvider 推进速率体现，无需额外处理
    }

    fun notifyPlaybackStateChanged(@Suppress("UNUSED_PARAMETER") playbackState: Int, playWhenReady: Boolean) {
        viewProvider()?.setIsPlayingProvider { playWhenReady }
    }

    fun notifyIsPlayingChanged(playing: Boolean) {
        viewProvider()?.setIsPlayingProvider { playing }
    }

    @Suppress("UNUSED_PARAMETER")
    fun notifyPlaybackFirstFrame() {
        // 首帧后无需特殊处理，positionProvider 会自动跟上
    }

    fun setEnabled(enabled: Boolean) {
        val snap = lastSnapshot ?: return
        applySettings(snap.copy(enabled = enabled))
    }

    fun pause() {
        viewProvider()?.setIsPlayingProvider { false }
    }

    fun resume() {
        viewProvider()?.setIsPlayingProvider { true }
    }

    fun stop() {
        viewProvider()?.let {
            it.setIsPlayingProvider { false }
            it.setDanmakus(emptyList())
        }
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
        return DanmakuConfig(
            enabled = snapshot.enabled,
            opacity = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeSp = snapshot.textSize.toDmTextSizeSp(),
            fontWeight = DanmakuFontWeight.Normal, // 默认非粗体（与 AkDanmaku 默认一致）
            strokeWidthPx = 4, // 描边像素（引擎 outlinePadPx = max(1, strokeWidthPx/2)，4px 描边清晰）
            speedLevel = snapshot.speed.toDmSpeedLevel(),
            area = snapshot.screenArea.toDmArea(),
            laneDensity = DanmakuLaneDensity.Standard,
            showHighLikeIcon = false, // stub 版不支持高赞图标
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
     * 设置面板 textSize(30-55) → 弹幕引擎 textSizeSp。
     *
     * 弹幕引擎内部: textSizePx = sp(textSizeSp) = textSizeSp × density。
     * 我们的 textSize 30-55 对应 scale 0.55-2.8，映射到 14-26 sp 范围（B站标准字号区间）。
     */
    private fun Int.toDmTextSizeSp(): Float {
        // textSize 39（标准）→ 18sp，两端线性拉伸
        val scale = when (this) {
            in 30..39 -> 14f + (this - 30) * 0.4f   // 30→14sp, 39→17.6sp
            in 40..55 -> 18f + (this - 40) * 0.5f   // 40→18sp, 55→25.5sp
            else -> 18f
        }
        return scale
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
