package com.btelly.feature.player.danmaku

import android.content.Context
import com.btelly.core.common.ext.isVipColorfulDanmakuAllowed
import com.btelly.core.common.log.AppLog
import com.btelly.feature.player.DanmakuFilterContext
import com.btelly.feature.player.PlaybackStartupTrace
import com.btelly.feature.player.view.BiliDanmakuFilterPolicy
import com.btelly.feature.player.view.DanmakuDuplicateMergePolicy
import com.btelly.feature.player.view.MyPlayerDanmakuController
import com.btelly.feature.player.view.VipDanmakuTextureCache
import com.btelly.model.dm.DmModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 不支持（性能优先模式）：直播、特殊弹幕（已过滤）、防挡蒙版、智能过滤、表情/高赞图标（已移除，电视端看不清且拖累性能）。
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

    // playWhenReady：用户"想播放"的意图，独立于 isPlaying（是否真在解码）。
    // 后台返回恢复时 ExoPlayer 可能还在 buffering，isPlaying 尚未变 true，但 playWhenReady 已为 true。
    // 用它驱动渲染循环，避免弹幕卡死直到首帧。
    @Volatile
    private var playWhenReady = false

    /**
     * 数据预处理协程作用域：把排序/过滤/合并/转换丢到后台线程，避免阻塞主线程。
     * 参考 MyPlayerDanmakuController.controllerScope 的模式。
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 数据预处理代际：新数据到来时自增，后台协程完成后校验，过期则丢弃结果（防竞态）。 */
    private val prepareGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var preloadTextureJob: Job? = null
    private var preloadedVipTextureKeys: Set<String> = emptySet()

    /**
     * 引擎数据是否已被 stop 清空（切后台）。stop() 置 true；重新喂数据后置 false。
     * 切回前台首帧时若为 true 且 rawItems 非空，用 rawItems 恢复弹幕。
     */
    @Volatile
    private var dataStopped: Boolean = false

    init {
        installProviders()
    }

    private fun installProviders() {
        val view = viewProvider() ?: return
        view.setPositionProvider { playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L }
        view.setIsPlayingProvider { isPlaying }
        view.setPlayWhenReadyProvider { playWhenReady }
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
        // 排序 + 过滤/合并/转换丢到后台线程，避免大数据（可达 2 万条）阻塞主线程。
        // 代际校验防止"旧数据处理完时新数据已到"的竞态。
        val generation = prepareGeneration.incrementAndGet()
        controllerScope.launch {
            val sorted = data.sortedBy { it.progress }
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                rawItems = sorted
            }
            applyDataToViewAsync(generation, append = false)
        }
    }

    fun appendData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    ) {
        if (data.isEmpty()) return
        this.filterContext = filterContext
        val generation = prepareGeneration.incrementAndGet()
        controllerScope.launch {
            // 合并需要读 rawItems，在主线程快照避免并发修改。
            val existing = withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext null
                rawItems
            } ?: return@launch
            val merged = mergeSorted(existing, data)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                rawItems = merged
            }
            applyDataToViewAsync(generation, append = true)
        }
    }

    fun applySettings(snapshot: MyPlayerDanmakuController.SettingsSnapshot) {
        if (lastSnapshot == snapshot) return
        val old = lastSnapshot
        lastSnapshot = snapshot
        currentConfig = buildConfig(snapshot)
        viewProvider()?.visibility = if (snapshot.enabled) android.view.View.VISIBLE else android.view.View.GONE
        if (old == null) {
            // 首次：必须走完整数据预处理。
            if (rawItems.isNotEmpty()) applyDataToView()
            return
        }
        // 字段级 diff：区分"config 级"与"数据级"设置。
        // config 级（alpha/textSize/speed/screenArea/enabled/trackSpacing）只改渲染参数，
        //   引擎 updateConfig 已正确处理——opacity/speed/area 不失效已缓存的文字 bitmap，
        //   仅 textSize/strokeWidth 变化才失效 bitmap（引擎内部按需分帧重建）。
        //   所以这类设置变化只需通过 configProvider 下发新 config，无需重跑过滤/合并/转换。
        // 数据级（allowTop/allowBottom/mergeDuplicate/smartFilterLevel）影响
        //   过滤/合并结果，必须重新预处理已有数据并重新注入，否则开关不生效。
        val dataLevelChanged = old.allowTop != snapshot.allowTop ||
            old.allowBottom != snapshot.allowBottom ||
            old.mergeDuplicate != snapshot.mergeDuplicate ||
            old.smartFilterLevel != snapshot.smartFilterLevel
        if (dataLevelChanged && rawItems.isNotEmpty()) {
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
        this.playWhenReady = playWhenReady
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
        if (!wasPlaying) {
            playWhenReady = true
            isPlaying = true
        }
        // 修复 bug2：切后台 stop 清空了引擎数据，切回前台首帧时用保留的 rawItems 恢复弹幕。
        if (dataStopped && rawItems.isNotEmpty()) {
            dataStopped = false
            resumeDataFromBackground()
        }
        viewProvider()?.invalidate()
    }

    fun setEnabled(enabled: Boolean) {
        val snap = lastSnapshot ?: return
        applySettings(snap.copy(enabled = enabled))
    }

    fun pause() {
        playWhenReady = false
        isPlaying = false
    }

    fun resume() {
        val wasPlaying = isPlaying
        playWhenReady = true
        isPlaying = true
        if (!wasPlaying) viewProvider()?.invalidate()
    }

    fun stop() {
        playWhenReady = false
        isPlaying = false
        // 仅清引擎 active 数据，保留 rawItems：切后台 stop 后，切回前台播放时
        // notifyIsPlayingChanged/notifyPlaybackFirstFrame 会用 rawItems 重新喂数据恢复弹幕。
        // （此前清 rawItems 导致切后台再回来弹幕永久消失，直到重新播放。）
        viewProvider()?.setDanmakus(emptyList())
        preloadedVipTextureKeys = emptySet()
        dataStopped = rawItems.isNotEmpty()
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
        preloadTextureJob?.cancel()
        controllerScope.cancel()
    }

    /**
     * 切后台 stop 后，切回前台首帧时用保留的 rawItems 恢复弹幕。
     * 对齐 ak 引擎：ak 的 stop 不碰 danmakuTimeline，恢复时靠 rebuildAndApplyData 重建窗口；
     * lite 的 stop 此前会清 rawItems 导致切回后弹幕永久消失，现改为保留 rawItems 并在此恢复。
     */
    private fun resumeDataFromBackground() {
        if (rawItems.isEmpty()) return
        val generation = prepareGeneration.incrementAndGet()
        controllerScope.launch {
            applyDataToViewAsync(generation, append = false)
        }
    }

    // ---- 内部 ----

    /**
     * 同步预处理并注入（数据级设置变更时走这里：数据已在内存，重处理以应用新的过滤/合并设置）。
     * 仍跑在调用线程（主线程），但设置变更触发的频率远低于首屏加载，可接受。
     */
    private fun applyDataToView(append: Boolean = false) {
        if (rawItems.isEmpty()) {
            if (!append) viewProvider()?.setDanmakus(emptyList())
            return
        }
        val prepared = preprocess(rawItems, append)
        injectToView(prepared, append)
    }

    /**
     * 异步预处理并注入（setData/appendData 走这里：把重活丢后台）。
     * [generation] 用于代际校验，过期结果丢弃。
     */
    private suspend fun applyDataToViewAsync(generation: Int, append: Boolean) {
        // 快照当前数据（在主线程读，避免与 setData/appendData 并发修改 rawItems）。
        val snapshot = withContext(Dispatchers.Main.immediate) {
            if (prepareGeneration.get() != generation) return@withContext null
            if (rawItems.isEmpty()) return@withContext emptyList<DmModel>()
            rawItems.toList() // 防御性拷贝，后台处理期间不被并发修改
        } ?: return
        if (snapshot.isEmpty()) {
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                if (!append) viewProvider()?.setDanmakus(emptyList())
            }
            return
        }
        // 后台线程：过滤 + 合并 + 转换（重活，可能上万条）。
        val prepared = preprocess(snapshot, append)
        // 回主线程注入引擎（setDanmakus/appendDanmakus 操作 View，必须主线程）。
        withContext(Dispatchers.Main.immediate) {
            if (prepareGeneration.get() != generation) return@withContext
            injectToView(prepared, append)
        }
    }

    /**
     * 数据预处理：过滤 + 合并重复。纯 CPU 计算，无 View/主线程依赖，可安全在后台线程执行。
     */
    private fun preprocess(
        items: List<DmModel>,
        @Suppress("UNUSED_PARAMETER") append: Boolean
    ): List<Danmaku> {
        // 1. 过滤（复用现有策略，engine 无关）
        val filtered = BiliDanmakuFilterPolicy.apply(
            items = items,
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
        // 3. 转 Danmaku（读 VIP 渐变开关，关闭时 vipGradient 全 false，走普通路径零开销）
        val allowVipColorful = isVipColorfulDanmakuAllowed()
        val danmakus = prepared.toDanmakus(allowVipColorful = allowVipColorful)
        scheduleVipTexturePreload(danmakus)
        return danmakus
    }

    private fun scheduleVipTexturePreload(danmakus: List<Danmaku>) {
        val styles = danmakus.asSequence()
            .filter { it.vipGradient }
            .map { it.vipGradientStyle }
            .filter { it.hasTexture }
            .distinct()
            .toList()
        if (styles.isEmpty()) return
        val keys = styles.mapTo(LinkedHashSet()) { it.textureKey() }
        val missingKeys = keys - preloadedVipTextureKeys
        if (missingKeys.isEmpty()) return
        val missingStyles = styles.filter { it.textureKey() in missingKeys }
        val generation = prepareGeneration.get()
        preloadTextureJob?.cancel()
        preloadTextureJob = controllerScope.launch(Dispatchers.IO) {
            VipDanmakuTextureCache.preloadStyles(missingStyles)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                // 仅记录已预加载的纹理，不重跑 applyDataToView()。
                // 弹幕在首次 injectToView 时已注入引擎，渲染层会按需查询纹理缓存
                // （未命中走降级渲染），重跑只会把整批弹幕再注入一遍，导致每条弹幕
                // 在两个轨道上重复出现。
                preloadedVipTextureKeys = preloadedVipTextureKeys + missingKeys
            }
        }
    }

    private fun com.kuaishou.akdanmaku.data.DanmakuVipGradientStyle.textureKey(): String =
        "$fillTextureUrl#$strokeTextureUrl"

    /** 把预处理结果注入引擎（操作 View，必须在主线程调用）。 */
    private fun injectToView(danmakus: List<Danmaku>, append: Boolean) {
        val view = viewProvider() ?: return
        // 注入的 danmakus 永远是全量 rawItems 经 preprocess 后的结果（applyDataToViewAsync
        // 用 rawItems 全量快照做预处理）。因此无论 setData 还是 appendData，都必须走
        // setDanmakus 全量替换。
        // 之前 append 路径调 appendDanmakus（引擎层 items.add 增量追加），会把全量列表反复
        // 追加进引擎 items，导致每次 appendData 后引擎弹幕成倍重复——seek 来回几次就满屏重影。
        view.setDanmakus(danmakus)
        // 数据已重新注入引擎，清除"切后台 stop"标记。
        dataStopped = false
        AppLog.i(
            TAG,
            "applied danmakus=${danmakus.size} merge=${lastSnapshot?.mergeDuplicate ?: true} append=$append"
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

        val strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
            textSizePx = akDanmakuPx,
            fontBorder = com.kuaishou.akdanmaku.DanmakuConfig.FONT_BORDER_DEFAULT
        )

        return DanmakuConfig(
            enabled = snapshot.enabled,
            opacity = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeSp = textSizeSp,
            // 对齐 akdanmaku：DanmakuConfig.bold 默认 true → Typeface.DEFAULT_BOLD。
            // （原注释误写为"bold=false"，与 DanmakuConfig.kt:106 实际默认值矛盾，已修正。）
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = strokeWidthPx,
            speedLevel = snapshot.speed.toDmSpeedLevel(),
            area = snapshot.screenArea.toDmArea(),
            laneDensity = DanmakuLaneDensity.Standard,
            trackSpacing = DanmakuTrackSpacing.fromPrefValue(snapshot.trackSpacing),
        )
    }

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = 18f * density,
                fontBorder = com.kuaishou.akdanmaku.DanmakuConfig.FONT_BORDER_DEFAULT
            ),
            speedLevel = 4,
            area = 0.5f,
            laneDensity = DanmakuLaneDensity.Standard,
            trackSpacing = DanmakuTrackSpacing.DEFAULT,
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
