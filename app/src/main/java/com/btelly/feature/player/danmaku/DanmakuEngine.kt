package com.btelly.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import android.content.Context
import com.btelly.core.common.log.AppLog
import com.btelly.feature.player.danmaku.common.BiliDanmakuStyle
import com.btelly.feature.player.danmaku.model.DanmakuCacheState
import com.btelly.feature.player.danmaku.model.DanmakuItem
import com.btelly.feature.player.danmaku.model.DanmakuKind
import com.btelly.feature.player.danmaku.model.RenderSnapshot
import com.btelly.feature.player.danmaku.model.RenderSnapshotStats
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DANMAKU_FONT_BORDER_DEFAULT = 0

internal interface DanmakuEngineMainApi {
    fun lastDrawCachedCount(): Int

    fun lastDrawCacheMissSkippedCount(): Int

    fun stepTime(positionMs: Long, uiFrameId: Int)

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun acquireRenderSnapshot(): RenderSnapshot

    fun releaseRenderSnapshot(snapshot: RenderSnapshot)

    fun renderSnapshotStats(): RenderSnapshotStats

    fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig)
}

internal fun shouldApplyDmCacheResult(
    resultGeneration: Int,
    currentGeneration: Int,
    pendingGeneration: Int,
    rendering: Boolean,
    active: Boolean,
): Boolean = resultGeneration == currentGeneration &&
    pendingGeneration == resultGeneration &&
    rendering &&
    active

internal fun cacheReadyStartTime(motionStarted: Boolean, currentStartTimeMs: Int, nowMs: Int): Int =
    if (motionStarted) currentStartTimeMs else nowMs

internal fun isCacheWaitExpired(motionStarted: Boolean, admittedAtMs: Int, nowMs: Int, timeoutMs: Int): Boolean =
    !motionStarted && nowMs - admittedAtMs >= timeoutMs

internal fun adjustedTimelineIndexAfterPrefixTrim(index: Int, droppedCount: Int): Int =
    (index - droppedCount).coerceAtLeast(0)

internal fun writeDanmakuRenderOrder(
    active: List<DanmakuItem>,
    snapshot: RenderSnapshot,
) {
    snapshot.ensureCapacity(active.size)
    var count = 0
    for (item in active) {
        if (item.kind != DanmakuKind.SCROLL) continue
        snapshot.items[count] = item
        snapshot.yTop[count] = item.layoutTopPx
        snapshot.textWidth[count] = item.textWidthPx
        count++
    }
    for (item in active) {
        if (item.kind == DanmakuKind.SCROLL) continue
        snapshot.items[count] = item
        snapshot.yTop[count] = item.layoutTopPx
        snapshot.textWidth[count] = item.textWidthPx
        count++
    }
    snapshot.count = count
}

internal interface DanmakuEngineActionApi {
    fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int)

    fun updateConfig(newConfig: DanmakuConfig)

    fun stepTime(positionMs: Long, uiFrameId: Int)

    fun currentPositionMs(): Long

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun applyCacheResult(result: CacheBuildResult)

    fun preAct()

    fun act()

    fun setDanmakus(list: List<Danmaku>)

    fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean)

    /** Replaces only the future suffix of the timeline without resetting active items. */
    fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>)

    fun trimToMax(maxItems: Int)

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long)

    fun seekTo(positionMs: Long)

    fun clear()

    fun release()
}

/**
 * Danmaku engine:
 * - Action thread owns timeline admission and lane selection.
 * - Main thread only consumes render snapshot and computes current scroll positions.
 */
internal class DanmakuEngine(
    private val appContext: Context,
    private val displayMetrics: DisplayMetrics,
    private val cacheManager: CacheManager,
) : DanmakuEngineMainApi, DanmakuEngineActionApi {
    private val density: Float = displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    // ---- Data ----
    private val actionStateLock = Any()
    private var items: MutableList<DanmakuItem> = mutableListOf()
    private var index: Int = 0
    private val active: ArrayList<DanmakuItem> = ArrayList(64)
    private val pending: ArrayDeque<PendingSpawn> = ArrayDeque()
    // 最近一个重建窗口内已实际入场的条目。全量替换/乱序重排会生成新 DanmakuItem，
    // 不能只依赖 active 或新实例上的 consumed 标记来防止已离屏条目再次入场。
    private val admissionHistory = DanmakuAdmissionHistory()
    private var lastAdmissionHistoryPruneBeforeMs: Int = 0

    // Monotonic time within a session (action thread).
    private var lastNowMs: Int = 0

    // ---- Viewport / Config (action thread writes; main reads) ----
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    @Volatile private var viewportTopInsetPx: Int = 0
    @Volatile private var viewportBottomInsetPx: Int = 0

    @Volatile
    private var config: DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = sp(18f),
                fontBorder = DANMAKU_FONT_BORDER_DEFAULT
            ),
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
        )

    @Volatile private var textSizePx: Float = sp(18f)
    @Volatile private var strokeWidthPx: Float = BiliDanmakuStyle.strokeWidthForCache(
        textSizePx = sp(18f),
        fontBorder = DANMAKU_FONT_BORDER_DEFAULT
    ).toFloat()
    @Volatile private var outlinePadPx: Float = 2f

    @Volatile private var cacheStyleGeneration: Int = 0
    @Volatile private var measureGeneration: Int = 0
    @Volatile private var debugPendingCount: Int = 0
    @Volatile private var debugNextAtMs: Int? = null

    @Volatile private var lastDrawCachedCount: Int = 0
    @Volatile private var lastDrawCacheMissSkippedCount: Int = 0

    override fun lastDrawCachedCount(): Int = lastDrawCachedCount

    override fun lastDrawCacheMissSkippedCount(): Int = lastDrawCacheMissSkippedCount

    // ---- Time (main writes; action reads) ----
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var currentUiFrameId: Int = 0

    // ---- Render snapshot (double buffer) ----
    private val snapshots = Array(3) { RenderSnapshot() }
    @Volatile private var latestSnapshot: RenderSnapshot = snapshots[0]
    private var snapshotDirty: Boolean = true
    private var rebuildRequested: Boolean = true
    // rebuildScene 触发来源（配合诊断日志定位"弹幕重复入场/半路消失"根因）。
    private var rebuildReason: String? = "init"

    /** 统一标记需要 rebuild，并记录来源，便于日志区分是 viewport/config/数据/几何哪类变化触发。 */
    private fun requestRebuild(reason: String) {
        rebuildRequested = true
        rebuildReason = reason
    }

    // ---- Layout state (action thread only) ----
    private val actionFontMetrics = Paint.FontMetrics()
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    // actionPaint 度量是否已算（字号/描边变化后置 false，强制下帧重算）。
    @Volatile private var actionMetricsValid = false
    private var configuredLaneCount: Int = 0
    private var configuredLaneHeightPx: Float = 0f
    private var configuredTopInsetPx: Int = 0
    private var configuredUsableHeightPx: Int = 0
    private var configuredMaxYTopPx: Float = 0f
    private var laneLastScroll: Array<DanmakuItem?> = emptyArray()
    private var laneLastTop: Array<DanmakuItem?> = emptyArray()
    private var laneLastBottom: Array<DanmakuItem?> = emptyArray()
    private var cacheProbeCursor: Int = 0

    // ---- Draw (main thread only) ----
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 对齐 akdanmaku drawPaint：关闭 isFilterBitmap，避免 drawBitmap 双线性滤波糊化烘焙好的
        // 锐利文字边缘（边缘被混合稀释 → 颜色发浅/不饱满）。弹幕 bitmap 按整数像素 1:1 绘制，无需滤波。
    }

    override fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        requestRebuild("viewport")
    }

    override fun updateConfig(newConfig: DanmakuConfig) {
        synchronized(actionStateLock) {
            config = newConfig
            val tsPx = sp(newConfig.textSizeSp).coerceAtLeast(1f)
            val newStrokeWidthPx = newConfig.strokeWidthPx.coerceAtLeast(0).toFloat()
            val newTypeface = newConfig.fontWeight.typeface

            val oldTs = textSizePx
            val oldStrokeWidthPx = strokeWidthPx
            val oldTypeface = actionPaint.typeface

            textSizePx = tsPx
            strokeWidthPx = newStrokeWidthPx
            outlinePadPx = max(1f, newStrokeWidthPx / 2f)

            actionPaint.textSize = tsPx
            if (actionPaint.typeface != newTypeface) actionPaint.typeface = newTypeface

            val styleChanged = oldTs != tsPx || oldStrokeWidthPx != newStrokeWidthPx || oldTypeface != newTypeface
            if (styleChanged) {
                cacheStyleGeneration++
                measureGeneration++
                // 样式变化后 actionPaint 度量需重算（act 帧循环里按此标志判断）。
                actionMetricsValid = false
                // Invalidate current caches to avoid mixing styles.
                val releaseAt = currentUiFrameId + 1
                val size = active.size
                for (i in 0 until size) {
                    val a = active[i]
                    val entry = a.cacheEntry
                    if (entry != null) {
                        cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAt)
                        a.cacheEntry = null
                    }
                    a.cacheState = DanmakuCacheState.Init
                    a.cacheGeneration = -1
                    a.pendingCacheGeneration = -1
                }
            }
            requestRebuild("config")
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, displayMetrics)

    override fun stepTime(positionMs: Long, uiFrameId: Int) {
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentUiFrameId = uiFrameId
    }

    override fun currentPositionMs(): Long = currentPositionMs

    override fun drainReleasedBitmaps(uiFrameId: Int) {
        cacheManager.drainReleasedBitmaps(uiFrameId)
    }

    override fun applyCacheResult(result: CacheBuildResult) {
        val item = result.item
        if (!shouldApplyDmCacheResult(
                resultGeneration = result.generation,
                currentGeneration = cacheStyleGeneration,
                pendingGeneration = item.pendingCacheGeneration,
                rendering = item.cacheState == DanmakuCacheState.Rendering,
                active = item in active,
            )) {
            if (item.pendingCacheGeneration == result.generation) {
                item.cacheState = DanmakuCacheState.Init
                item.pendingCacheGeneration = -1
            }
            // 结果被拒（generation过期/已不在active/状态不匹配），缓存白建了。
            android.util.Log.w(
                TAG,
                "cacheResult REJECT t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                    "gen=${result.generation}/${cacheStyleGeneration} active=${item in active} " +
                    "state=${item.cacheState} pending=${item.pendingCacheGeneration}"
            )
            return
        }
        val entry = result.entry
        if (entry == null || !entry.tryAcquire()) {
            item.cacheState = DanmakuCacheState.Init
            item.pendingCacheGeneration = -1
            // entry 为空=内存不足建图失败；tryAcquire 失败=bitmap已被回收。
            android.util.Log.w(
                TAG,
                "cacheResult FAIL t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                    "entryNull=${entry == null} recycled=${entry?.isRecycled}"
            )
            return
        }
        val old = item.cacheEntry
        if (!item.motionStarted) {
            val prevStart = item.startTimeMs
            item.startTimeMs = cacheReadyStartTime(
                motionStarted = false,
                currentStartTimeMs = item.startTimeMs,
                nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            item.motionStarted = true
            // 缓存就绪，startTimeMs 重锚到当前播放位置。admit→ready 的等待时长若接近 MAX_CACHE_WAIT_MS，
            // 说明缓存构建接近超时边缘，下一步可能被 pruneExpired 误杀。
            android.util.Log.w(
                TAG,
                "cacheReady t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                    "admit=${prevStart}ms→start=${item.startTimeMs}ms waited=${item.startTimeMs - prevStart}ms lane=${item.lane}"
            )
        }
        if (old === entry) {
            entry.release()
            item.cacheGeneration = result.generation
            item.pendingCacheGeneration = -1
            item.cacheState = DanmakuCacheState.Rendered
            return
        }
        item.cacheEntry = entry
        item.cacheGeneration = result.generation
        item.pendingCacheGeneration = -1
        item.cacheState = DanmakuCacheState.Rendered
        if (old != null && old !== entry) {
            cacheManager.enqueueRelease(old, releaseAtFrameId = currentUiFrameId + 1)
        }
        snapshotDirty = true
    }

    override fun preAct() {
        // No-op: action thread owns state, draw thread only consumes snapshots.
    }

    override fun act() {
        synchronized(actionStateLock) {
            val cfg = config
            if (!cfg.enabled) {
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            val width = viewportWidth
            val height = viewportHeight
            if (width <= 0 || height <= 0) {
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            val outlinePad = outlinePadPx
            val rawNowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val nowMs = if (rawNowMs >= lastNowMs) rawNowMs else lastNowMs
            lastNowMs = nowMs

            val topInset = viewportTopInsetPx.coerceIn(0, height)
            val bottomInset = viewportBottomInsetPx.coerceIn(0, height - topInset)
            val availableHeight = (height - topInset - bottomInset).coerceAtLeast(0)

            // actionPaint 度量只随字号/描边变化，缓存比对避免每帧 getFontMetrics。
            if (actionPaint.textSize != textSizePx || !actionMetricsValid) {
                actionPaint.textSize = textSizePx
                actionPaint.getFontMetrics(actionFontMetrics)
                actionMetricsValid = true
            }
            // 度量高度对齐 akdanmaku SimpleRenderer.getCacheHeight：descent - ascent + leading。
            // leading 对 CJK 字体通常为 0，但西文/混排时可能有值，补上保证两套引擎行高基准一致。
            val textBoxHeight = (actionFontMetrics.descent - actionFontMetrics.ascent + actionFontMetrics.leading) + outlinePad * 2f
            // 统一行高倍率：laneHeight = textBoxHeight × factor，factor 来自 DanmakuTrackSpacing。
            // 与 akdanmaku（margin = itemHeight × (factor-1)）共用同一语义，两套引擎视觉间距一致。
            // factor<1 时 laneHeight<textBoxHeight，吃掉 fontMetrics 度量留白使同屏容纳更多行；
            // 下限 0.65×textBoxHeight 保证相邻 lane 字形不重叠。
            val laneHeight = (textBoxHeight * cfg.trackSpacing.factor).coerceAtLeast(textBoxHeight * 0.65f)
            val usableHeight = (availableHeight * cfg.area.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
            val laneCount = max(1, (usableHeight / laneHeight).toInt())
            val maxYTop = (topInset + usableHeight - textBoxHeight).toFloat().coerceAtLeast(topInset.toFloat())

            val geometryChanged =
                configuredLaneCount != laneCount ||
                    configuredTopInsetPx != topInset ||
                    configuredUsableHeightPx != usableHeight ||
                    kotlin.math.abs(configuredLaneHeightPx - laneHeight) > 0.01f ||
                    kotlin.math.abs(configuredMaxYTopPx - maxYTop) > 0.01f

            if (geometryChanged) {
                configuredLaneCount = laneCount
                configuredLaneHeightPx = laneHeight
                configuredTopInsetPx = topInset
                configuredUsableHeightPx = usableHeight
                configuredMaxYTopPx = maxYTop
                requestRebuild("geometry")
            }

            val rollingDurationMs = computeRollingDurationMs(speedLevel = cfg.speedLevel)
            val fixedDurationMs = FIXED_DURATION_MS

            pruneAdmissionHistory(
                (nowMs - max(rollingDurationMs, fixedDurationMs)).coerceAtLeast(0),
            )

            if (rebuildRequested) {
                rebuildScene(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    reason = rebuildReason ?: "rebuildFlag",
                )
            } else {
                pruneExpired(width = width, nowMs = nowMs)
                processPendingItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
                spawnNewItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
            }

            requestCacheBuilds(outlinePad = outlinePad, cfg = cfg)
            debugPendingCount = pending.size
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            publishSnapshotIfDirty(nowMs)
        }
    }

    override fun acquireRenderSnapshot(): RenderSnapshot {
        while (true) {
            val candidate = latestSnapshot
            if (!candidate.tryAcquireRead()) continue
            if (candidate === latestSnapshot) return candidate
            candidate.releaseRead()
        }
    }

    override fun releaseRenderSnapshot(snapshot: RenderSnapshot) {
        snapshot.releaseRead()
    }

    override fun renderSnapshotStats(): RenderSnapshotStats {
        val snapshot = acquireRenderSnapshot()
        return try {
            RenderSnapshotStats(
                positionMs = currentPositionMs,
                count = snapshot.count,
                pendingCount = snapshot.pendingCount,
                nextAtMs = snapshot.nextAtMs,
            )
        } finally {
            releaseRenderSnapshot(snapshot)
        }
    }

    override fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig) {
        val cfg = config
        if (!cfg.enabled) return

        val opacityAlpha = (cfg.opacity * 255f).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha
        val styleGen = cacheStyleGeneration
        val width = viewportWidth.coerceAtLeast(0)
        val nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        var cachedDrawn = 0
        var cacheMissSkipped = 0
        // 缓存缺失分类统计：定位"半路消失"到底是无缓存、bitmap 被回收还是样式代际不匹配。
        var missNoEntry = 0
        var missRecycled = 0
        var missGenMismatch = 0
        var missMotionWait = 0
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            // x 坐标在主线程 draw 时现算（用 draw 当前的 nowMs），保证滚动位置与画面同步。
            // 此前曾在 act 线程预算填入 snapshot.x，但 act 线程的 nowMs 滞后于 draw，
            // 导致弹幕位置抖动/卡顿。对齐参考实现（上游弹幕库）。
            val x =
                when (item.kind) {
                    DanmakuKind.SCROLL -> scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs)
                    DanmakuKind.TOP -> centerX(width = width, contentWidth = item.textWidthPx)
                    DanmakuKind.BOTTOM -> centerX(width = width, contentWidth = item.textWidthPx)
                }
            val yTop = snapshot.yTop[i]
            // The action thread may replace item.cacheEntry while this snapshot is still
            // being drawn. Read the snapshot-owned lease instead of mutable item state.
            val entry = snapshot.cacheEntries[i]
            if (entry != null && !entry.isRecycled && snapshot.cacheGenerations[i] == styleGen) {
                canvas.drawBitmap(entry.bitmap, x, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }
            // 性能优先模式不在主线程直接绘制文字。缓存未完成时跳过本帧，
            // Action 线程会继续提高可见条目的缓存优先级。
            cacheMissSkipped++
            when {
                entry == null && !item.motionStarted -> missMotionWait++
                entry == null -> missNoEntry++
                entry.isRecycled -> missRecycled++
                else -> missGenMismatch++
            }
        }
        lastDrawCachedCount = cachedDrawn
        lastDrawCacheMissSkippedCount = cacheMissSkipped
        // 仅在有缓存缺失时输出（正常帧不刷屏）。missing>0 说明弹幕在屏幕上但没画出来 → "半路消失"。
        if (cacheMissSkipped > 0) {
            android.util.Log.w(
                TAG,
                "draw miss=$cacheMissSkipped/$snapshot.count hit=$cachedDrawn " +
                    "wait=$missMotionWait noEntry=$missNoEntry recycled=$missRecycled genMismatch=$missGenMismatch " +
                    "gen=$styleGen now=${nowMs}ms"
            )
        }
    }

    override fun setDanmakus(list: List<Danmaku>) {
        synchronized(actionStateLock) {
            if (list.isEmpty()) clearAdmissionHistory()
            val newItems =
                list
                    .sortedBy { it.timeMs }
                    .mapTo(ArrayList(list.size.coerceAtLeast(0))) { DanmakuItem(it) }
            pending.clear()
            // 空时间线 = 停播/切后台清屏语义，必须整屏清空；仅非空替换且屏幕上有在播弹幕时走保留路径。
            if (list.isEmpty() || items.isEmpty() || active.isEmpty()) {
                clearActives()
                resetLaneState()
            } else {
                // 全量替换时间线但屏幕上有在播弹幕（appendData 合并冲突回退/设置重建时出现）：
                // 不整屏清空，把新时间线里与在场条目相同（同发送时间+同内容）的实例标记 consumed，
                // rebuildScene 会把它们跳过 → 在播弹幕保持运动状态与缓存，不再"清屏+近 6 秒重放"。
                markMatchedNewItemsConsumed(newItems)
            }
            items = newItems
            index = 0
            lastNowMs = 0
            requestRebuild("setDanmakus")
            debugPendingCount = 0
            debugNextAtMs = items.firstOrNull()?.timeMs()
            android.util.Log.w(TAG, "setDanmakus count=${items.size} onScreenPreserved=${active.size}")
            if (active.isEmpty()) publishEmptySnapshot()
        }
    }

    /**
     * 把新时间线中与当前在场条目值相等（Danmaku data class 按 发送时间+内容+样式 判等）的实例
     * 标记 consumed。使用计数表处理同一条弹幕在时间线里出现多次的情况（逐个匹配，不重复占用）。
     */
    private fun markMatchedNewItemsConsumed(newItems: List<DanmakuItem>) {
        if (active.isEmpty()) return
        val byData = HashMap<Danmaku, ArrayList<DanmakuItem>>(newItems.size.coerceAtLeast(16))
        for (item in newItems) byData.getOrPut(item.data) { ArrayList(2) }.add(item)
        for (a in active) {
            val candidates = byData[a.data] ?: continue
            if (candidates.isEmpty()) continue
            candidates.removeAt(candidates.lastIndex).consumed = true
        }
    }

    override fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean) {
        synchronized(actionStateLock) {
        if (list.isEmpty()) return
        if (items.isEmpty()) {
            setDanmakus(list)
            return
        }
        val newItems =
            if (alreadySorted) {
                list
            } else {
                list.sortedBy { it.timeMs }
            }
        val lastTime = items.lastOrNull()?.timeMs() ?: Int.MIN_VALUE
        val firstIncoming = newItems.firstOrNull()?.timeMs ?: Int.MIN_VALUE
        if (firstIncoming >= lastTime) {
            for (d in newItems) items.add(DanmakuItem(d))
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            android.util.Log.w(
                TAG,
                "append(ok) add=${newItems.size} firstIn=$firstIncoming lastExist=$lastTime items=${items.size}"
            )
            return
        }
        // 时间倒序：新批次首条早于已注入末条（分段补全/seek回看时出现）。
        // 只改写当前滚动寿命之外的未来尾段，绝不能 sort 全量时间线再 seek，
        // 否则在场条目和最近已入场条目都会被重新投放。
        val rollingDurationMs = computeRollingDurationMs(config.speedLevel)
        val patchFrom = resolveOutOfOrderAppendPatchStartMs(
            firstIncomingTimeMs = firstIncoming,
            currentPositionMs = currentPositionMs,
            rollingDurationMs = rollingDurationMs,
        )
        val replaceIndex = lowerBound(patchFrom)
        val replacement = mergeDanmakuFutureTail(
            existing = items.subList(replaceIndex, items.size).map { it.data },
            incoming = newItems,
            minTimeMs = patchFrom,
        )
        android.util.Log.w(
            TAG,
            "append(out-of-order!) add=${newItems.size} firstIn=$firstIncoming lastExist=$lastTime " +
                "items=${items.size} → patchFuture from=${patchFrom}ms replace=${replacement.size}"
        )
        if (replaceIndex < items.size) items.subList(replaceIndex, items.size).clear()
        items.addAll(replacement.map(::DanmakuItem))
        index = minOf(index, replaceIndex)
        requestRebuild("append-out-of-order-tail-patch")
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        }
    }

    override fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        synchronized(actionStateLock) {
            val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val replacement =
                list
                    .asSequence()
                    .filter { it.timeMs >= min }
                    .sortedBy { it.timeMs }
                    .map { DanmakuItem(it) }
                    .toList()
            val replaceIndex = lowerBound(min)
            markMatchedNewItemsConsumed(replacement)
            if (replaceIndex < items.size) items.subList(replaceIndex, items.size).clear()
            items.addAll(replacement)
            index = minOf(index, replaceIndex)
            requestRebuild("replace-from")
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            android.util.Log.w(
                TAG,
                "replaceFrom min=${min}ms replace=${replacement.size} prefix=$replaceIndex items=${items.size} active=${active.size}",
            )
        }
    }

    override fun trimToMax(maxItems: Int) {
        synchronized(actionStateLock) {
        if (maxItems <= 0) return
        val drop = items.size - maxItems
        if (drop <= 0) return
        items.subList(0, drop).clear()
        index = adjustedTimelineIndexAfterPrefixTrim(index, drop)
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        }
    }

    override fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        synchronized(actionStateLock) {
        if (items.isEmpty()) return
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (max <= min) return

        val start = lowerBound(min)
        val end = lowerBound(max)
        if (start <= 0 && end >= items.size) return
        if (start >= end) {
            items.clear()
            index = 0
            clearActives()
            resetLaneState()
            pending.clear()
            lastNowMs = 0
            requestRebuild("trim-clear")
            publishEmptySnapshot()
            return
        }
        items = items.subList(start, end).toMutableList()
        index = (index - start).coerceIn(0, items.size)
        requestRebuild("trim-range")
        seekTo(currentPositionMs, reason = "trim-range")
        }
    }

    override fun seekTo(positionMs: Long) {
        seekTo(positionMs, reason = "seek")
    }

    private fun seekTo(positionMs: Long, reason: String) {
        synchronized(actionStateLock) {
            val pos = positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            rebuildScene(
                nowMs = pos,
                width = viewportWidth.coerceAtLeast(0),
                outlinePad = outlinePadPx,
                rollingDurationMs = computeRollingDurationMs(config.speedLevel),
                fixedDurationMs = FIXED_DURATION_MS,
                laneCount = configuredLaneCount.coerceAtLeast(1),
                laneHeight = configuredLaneHeightPx.takeIf { it > 0f } ?: max(18f, textSizePx * 1.15f),
                topInset = configuredTopInsetPx,
                maxYTop = configuredMaxYTopPx.coerceAtLeast(configuredTopInsetPx.toFloat()),
                reason = reason,
            )
            publishSnapshotIfDirty(pos)
        }
    }

    override fun clear() {
        synchronized(actionStateLock) {
            clearActives()
            pending.clear()
            clearAdmissionHistory()
            resetLaneState()
            requestRebuild("clear")
            debugPendingCount = 0
            debugNextAtMs = null
            publishEmptySnapshot()
        }
    }

    override fun release() {
        synchronized(actionStateLock) {
            clear()
            releaseAllSnapshotCacheLeases()
        }
    }

    private fun clearActives() {
        if (active.isEmpty()) return
        val releaseAt = currentUiFrameId + 1
        for (i in active.size - 1 downTo 0) {
            val a = active.removeAt(i)
            releaseItemCache(a, releaseAtFrameId = releaseAt)
        }
        cacheProbeCursor = 0
        snapshotDirty = true
    }

    private fun clearAdmissionHistory() {
        admissionHistory.clear()
        lastAdmissionHistoryPruneBeforeMs = 0
    }

    /** History is only needed for the rolling rebuild window; avoid retaining an entire long video. */
    private fun pruneAdmissionHistory(minimumTimeMs: Int, force: Boolean = false) {
        if (!force && minimumTimeMs - lastAdmissionHistoryPruneBeforeMs < ADMISSION_HISTORY_PRUNE_INTERVAL_MS) return
        admissionHistory.pruneBefore(minimumTimeMs)
        lastAdmissionHistoryPruneBeforeMs = minimumTimeMs
    }

    private fun resetLaneState() {
        if (laneLastScroll.isNotEmpty()) java.util.Arrays.fill(laneLastScroll, null)
        if (laneLastTop.isNotEmpty()) java.util.Arrays.fill(laneLastTop, null)
        if (laneLastBottom.isNotEmpty()) java.util.Arrays.fill(laneLastBottom, null)
    }

    private fun publishEmptySnapshot() {
        val out = writableSnapshot()
        try {
            releaseSnapshotCacheLeases(out)
            out.clear()
            latestSnapshot = out
            snapshotDirty = false
        } finally {
            out.endWrite()
        }
    }

    private fun writableSnapshot(): RenderSnapshot {
        while (true) {
            val published = latestSnapshot
            for (candidate in snapshots) {
                if (candidate !== published && candidate.tryBeginWrite()) return candidate
            }
            Thread.yield()
        }
    }

    private fun publishSnapshotIfDirty(nowMs: Int) {
        if (!snapshotDirty) return
        val out = writableSnapshot()
        try {
            releaseSnapshotCacheLeases(out)
            out.clear()
            out.positionMs = nowMs.toLong()
            out.pendingCount = pending.size
            out.nextAtMs = items.getOrNull(index)?.timeMs()
            writeDanmakuRenderOrder(active, out)
            retainSnapshotCacheLeases(out)
            latestSnapshot = out
            snapshotDirty = false
        } finally {
            out.endWrite()
        }
    }

    /** The writable snapshot has no UI reader, so its old cache leases may now be returned. */
    private fun releaseSnapshotCacheLeases(snapshot: RenderSnapshot) {
        val releaseAt = currentUiFrameId + 1
        for (i in 0 until snapshot.count) {
            val entry = snapshot.cacheEntries[i] ?: continue
            cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAt)
        }
    }

    /** Keep bitmap ownership stable for the lifetime of a published render snapshot. */
    private fun retainSnapshotCacheLeases(snapshot: RenderSnapshot) {
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            val entry = item.cacheEntry
            if (entry != null && entry.tryAcquire()) {
                snapshot.cacheEntries[i] = entry
                snapshot.cacheGenerations[i] = item.cacheGeneration
            }
        }
    }

    /**
     * Player release stops future UI drawing before this action runs, so no
     * published snapshot can still be consumed. Return leases retained by the
     * two buffers that would otherwise never become writable again.
     */
    private fun releaseAllSnapshotCacheLeases() {
        for (snapshot in snapshots) {
            if (!snapshot.tryBeginWrite()) continue
            try {
                releaseSnapshotCacheLeases(snapshot)
                snapshot.clear()
            } finally {
                snapshot.endWrite()
            }
        }
    }

    private fun rebuildScene(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        reason: String,
    ) {
        val activeBefore = active.size
        // 位置倒退（用户 seek 回更早位置）时允许把 consumed 标记的条目重新入场（回看本来就是重放）；
        // 同位置/前进的重建必须跳过，否则"已滚过/正在滚"的弹幕会再次从右侧入场。
        val positionWentBack = nowMs < lastNowMs
        if (positionWentBack) clearAdmissionHistory()
        pending.clear()
        ensureLaneBuffers(laneCount)
        rebuildRequested = false
        lastNowMs = nowMs
        if (width <= 0 || laneCount <= 0) {
            clearActives()
            resetLaneState()
            index = lowerBound(nowMs)
            debugPendingCount = 0
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            snapshotDirty = true
            return
        }
        // 保留仍在场的条目（运动状态/缓存/轨道原样，仅重算布局坐标）：
        // 此前每次重建都整屏清空再重放近一个滚动时长，导致"滚动弹幕半路消失"与"同一条弹幕重复入场"。
        preserveActiveForRebuild(
            nowMs = nowMs,
            width = width,
            outlinePad = outlinePad,
            rollingDurationMs = rollingDurationMs,
            laneCount = laneCount,
            laneHeight = laneHeight,
            topInset = topInset,
            maxYTop = maxYTop,
        )
        val preserved = active.size
        // 重新入场的起点：nowMs 往前回退一个最长滚动/固定时长，这部分弹幕会重新从右侧入场。
        // 仅未在场的（含新注入/替换时间线的未消费条目）会被投放，避免重复。
        val admitSinceMs = (nowMs - max(rollingDurationMs, fixedDurationMs)).coerceAtLeast(0)
        pruneAdmissionHistory(admitSinceMs, force = true)
        val priorAdmissions = if (positionWentBack) null else admissionHistory.replayBudget()
        index = lowerBound(admitSinceMs)
        var admitted = 0
        var skippedPreviouslyAdmitted = 0
        while (index < items.size && items[index].timeMs() <= nowMs) {
            val item = items[index]
            index++
            if (item.data.text.isBlank()) continue
            if (item.timeMs() < admitSinceMs) continue
            // 时间线整体替换后：新实例与在场条目相同 → 已标记，跳过（除非用户回看）。
            if (!positionWentBack && (item in active || item.consumed)) {
                priorAdmissions?.consume(item.data)
                continue
            }
            if (priorAdmissions?.consume(item.data) == true) {
                skippedPreviouslyAdmitted++
                continue
            }
            if (tryAdmitItem(
                item = item,
                nowMs = nowMs,
                width = width,
                outlinePad = outlinePad,
                rollingDurationMs = rollingDurationMs,
                fixedDurationMs = fixedDurationMs,
                laneCount = laneCount,
                laneHeight = laneHeight,
                topInset = topInset,
                maxYTop = maxYTop,
                // 入场失败进受控 pending 队列（有上限+重试），不再静默丢弃。
                allowPending = true,
            )) {
                admitted++
            }
        }
        debugPendingCount = pending.size
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        snapshotDirty = true
        android.util.Log.w(
            TAG,
            "rebuildScene reason=$reason now=${nowMs}ms width=$width lanes=$laneCount " +
                "items=${items.size} activeBefore=$activeBefore preserved=$preserved activeAfter=${active.size} " +
                "reAdmit=$admitted skippedHistory=$skippedPreviouslyAdmitted since=${admitSinceMs}ms"
        )
    }

    /**
     * 重建场景时保留仍在场的条目：
     * - 运动状态（startTimeMs/pxPerMs/motionStarted）、缓存（cacheEntry）、轨道原样保留；
     * - 仅重算布局坐标（laneHeight/maxYTop 随 config/geometry 变化）并重建轨道引用；
     * - 清理三类条目：已过期（含缓存等待超时）、发送时间超出新位置（回看时属于未来）、
     *   轨道越界（lane 数变小，释放缓存后交给窗口循环重新入场）。
     * 这样 viewport/config/数据替换等重建不再整屏清空，也不再有"滚到一半消失"。
     */
    private fun preserveActiveForRebuild(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        resetLaneState()
        val releaseAt = currentUiFrameId + 1
        var write = 0
        for (read in 0 until active.size) {
            val a = active[read]
            if (a.timeMs() > nowMs || a.lane >= laneCount || expireReason(a, width = width, nowMs = nowMs) != null) {
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            // 样式/度量代际变化后重算文本宽度与滚动时长，保证保留条目的运动参数与当前样式一致。
            if (a.measureGeneration != measureGeneration) {
                a.textWidthPx = measureTextWidth(a, outlinePad)
                if (a.kind == DanmakuKind.SCROLL) {
                    val distancePx = (width.toFloat() + a.textWidthPx).coerceAtLeast(0f)
                    val rawPx = distancePx / rollingDurationMs.toFloat()
                    val shortPx = width.toFloat() / rollingDurationMs.toFloat()
                    val maxPx = shortPx * MAX_LONG_SCROLL_SPEED_RATIO
                    a.pxPerMs = min(rawPx, maxPx)
                    a.durationMs = computeScrollDurationMs(distancePx, a.pxPerMs, rollingDurationMs)
                }
            }
            // 重算布局坐标：config/geometry 变化后 laneHeight/maxYTop 可能已变，保留条目必须跟随。
            a.layoutTopPx =
                when (a.kind) {
                    DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                    DanmakuKind.BOTTOM -> (maxYTop - laneHeight * a.lane).coerceAtLeast(topInset.toFloat())
                    DanmakuKind.SCROLL -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                }
            when (a.kind) {
                DanmakuKind.SCROLL -> laneLastScroll[a.lane] = a
                DanmakuKind.TOP -> laneLastTop[a.lane] = a
                DanmakuKind.BOTTOM -> laneLastBottom[a.lane] = a
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < active.size) {
            active.subList(write, active.size).clear()
            snapshotDirty = true
        }
        if (cacheProbeCursor >= active.size) cacheProbeCursor = 0
    }

    private fun pruneExpired(width: Int, nowMs: Int) {
        if (active.isEmpty()) return
        val size = active.size
        var write = 0
        val releaseAt = currentUiFrameId + 1
        var droppedTimeout = 0
        var droppedNormal = 0
        for (read in 0 until size) {
            val a = active[read]
            val reason = expireReason(a, width = width, nowMs = nowMs)
            if (reason != null) {
                if (reason == "cacheWaitTimeout") droppedTimeout++ else droppedNormal++
                // cacheWaitTimeout = 缓存超时丢弃（弹幕从未可见），是"半路消失"的可疑来源，单独详记。
                if (reason == "cacheWaitTimeout") {
                    android.util.Log.w(
                        TAG,
                        "pruneExpired DROP cacheTimeout t=${a.timeMs()}ms text='${a.data.text.take(12)}' " +
                            "start=${a.startTimeMs}ms now=${nowMs}ms age=${nowMs - a.startTimeMs}ms " +
                            "motion=${a.motionStarted} lane=${a.lane} dur=${a.durationMs}ms"
                    )
                }
                clearLaneReferenceIfMatch(a)
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < size) {
            active.subList(write, size).clear()
            if (droppedTimeout > 0 || droppedNormal > 0) {
                android.util.Log.w(
                    TAG,
                    "pruneExpired removed=${size - write} timeout=$droppedTimeout normal=$droppedNormal " +
                        "now=${nowMs}ms remain=${active.size}"
                )
            }
        }
        if (cacheProbeCursor >= active.size) cacheProbeCursor = 0
    }

    private fun processPendingItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        if (pending.isEmpty()) return
        val pendingCount = pending.size
        var processed = 0
        var indexInQueue = 0
        while (indexInQueue < pendingCount && pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            indexInQueue++
            if (entry.nextTryMs > nowMs) {
                pending.addLast(entry)
                continue
            }
            if (processed >= MAX_PENDING_RETRY_PER_FRAME) {
                pending.addLast(entry)
                continue
            }
            processed++
            val admitted =
                tryAdmitItem(
                    item = entry.item,
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    allowPending = false,
                )
            if (admitted) continue
            val age = nowMs - entry.firstTryMs
            if (entry.retryCount >= MAX_PENDING_RETRY_COUNT || age >= MAX_DELAY_MS) continue
            entry.retryCount += 1
            entry.nextTryMs = nowMs + DELAY_STEP_MS
            pending.addLast(entry)
        }
    }

    private fun spawnNewItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        skipOld(nowMs, rollingDurationMs)
        dropIfLagging(nowMs)
        var spawnAttempts = 0
        while (index < items.size && items[index].timeMs() <= nowMs) {
            if (spawnAttempts >= MAX_SPAWN_PER_FRAME) break
            val item = items[index]
            index++
            spawnAttempts++
            if (item.data.text.isBlank()) continue
            tryAdmitItem(
                item = item,
                nowMs = nowMs,
                width = width,
                outlinePad = outlinePad,
                rollingDurationMs = rollingDurationMs,
                fixedDurationMs = fixedDurationMs,
                laneCount = laneCount,
                laneHeight = laneHeight,
                topInset = topInset,
                maxYTop = maxYTop,
                allowPending = true,
            )
        }
    }

    private fun tryAdmitItem(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        allowPending: Boolean,
    ): Boolean {
        val textWidth = measureTextWidth(item, outlinePad)
        val kind = kindOf(item.data)
        val marginPx = max(12f, (textSizePx + outlinePad * 2f) * 0.6f)
        val admitted =
            when (kind) {
                DanmakuKind.SCROLL ->
                    trySpawnScroll(
                        item = item,
                        nowMs = nowMs,
                        width = width,
                        textWidth = textWidth,
                        rollingDurationMs = rollingDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                        marginPx = marginPx,
                    )
                DanmakuKind.TOP ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.TOP,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
                DanmakuKind.BOTTOM ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.BOTTOM,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
            }
        if (!admitted && allowPending) {
            enqueuePending(item = item, nowMs = nowMs)
        }
        return admitted
    }

    private fun trySpawnScroll(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        textWidth: Float,
        rollingDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        marginPx: Float,
    ): Boolean {
        val distancePx = (width.toFloat() + textWidth).coerceAtLeast(0f)
        val rawPx = distancePx / rollingDurationMs.toFloat()
        val shortPx = width.toFloat() / rollingDurationMs.toFloat()
        val maxPx = shortPx * MAX_LONG_SCROLL_SPEED_RATIO
        val pxNew = min(rawPx, maxPx)
        val durationMs =
            computeScrollDurationMs(
                distancePx = distancePx,
                pxPerMs = pxNew,
                fallbackDurationMs = rollingDurationMs,
            )
        for (lane in 0 until laneCount) {
            val prev = laneLastScroll[lane]
            if (prev != null && isExpired(prev, width = width, nowMs = nowMs)) {
                laneLastScroll[lane] = null
            }
            val rear = laneLastScroll[lane]
            if (rear == null) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
            val tailPrev = scrollX(width = width, nowMs = nowMs, startTimeMs = rear.startTimeMs, pxPerMs = rear.pxPerMs) + rear.textWidthPx
            if (isScrollLaneAvailable(width.toFloat(), nowMs, rear, tailPrev, pxNew, marginPx)) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
        }
        return false
    }

    private fun trySpawnFixed(
        item: DanmakuItem,
        kind: DanmakuKind,
        nowMs: Int,
        textWidth: Float,
        durationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ): Boolean {
        val lanes =
            when (kind) {
                DanmakuKind.TOP -> laneLastTop
                DanmakuKind.BOTTOM -> laneLastBottom
                DanmakuKind.SCROLL -> return false
            }
        for (lane in 0 until laneCount) {
            val prev = lanes[lane]
            if (prev != null && isExpired(prev, width = viewportWidth, nowMs = nowMs)) {
                lanes[lane] = null
            }
            if (lanes[lane] != null) continue
            activate(
                item = item,
                kind = kind,
                lane = lane,
                textWidth = textWidth,
                pxPerMs = 0f,
                durationMs = durationMs,
                startTimeMs = nowMs,
                layoutTopPx =
                    when (kind) {
                        DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop)
                        DanmakuKind.BOTTOM -> (maxYTop - laneHeight * lane).coerceAtLeast(topInset.toFloat())
                        DanmakuKind.SCROLL -> topInset.toFloat()
                    },
            )
            lanes[lane] = item
            return true
        }
        return false
    }

    private fun requestCacheBuilds(
        outlinePad: Float,
        cfg: DanmakuConfig,
    ) {
        if (active.isEmpty()) return
        if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) return
        val style =
            CacheStyle(
                textSizePx = textSizePx,
                fontWeight = cfg.fontWeight,
                strokeWidthPx = strokeWidthPx,
                outlinePadPx = outlinePad,
                generation = cacheStyleGeneration,
            )
        val releaseAtFrameId = currentUiFrameId + 1
        val scanCount = min(active.size, MAX_CACHE_SCAN_PER_FRAME)
        var requested = 0
        for (offset in 0 until scanCount) {
            if (requested >= MAX_CACHE_REQUESTS_PER_FRAME) break
            if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) break
            val indexInActive = (cacheProbeCursor + offset) % active.size
            val item = active[indexInActive]
            val entry = item.cacheEntry
            val hasValidCache = entry != null && !entry.isRecycled && item.cacheGeneration == style.generation
            if (hasValidCache) continue
            if (item.cacheState == DanmakuCacheState.Rendering) continue
            item.cacheState = DanmakuCacheState.Rendering
            item.pendingCacheGeneration = style.generation
            cacheManager.requestBuildCache(
                item = item,
                textWidthPx = item.textWidthPx,
                style = style,
                releaseAtFrameId = releaseAtFrameId,
            )
            requested++
        }
        cacheProbeCursor = if (active.isEmpty()) 0 else (cacheProbeCursor + scanCount) % active.size
    }

    private fun releaseItemCache(item: DanmakuItem, releaseAtFrameId: Int) {
        val entry = item.cacheEntry
        if (entry != null) {
            cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAtFrameId)
            item.cacheEntry = null
        }
        item.cacheState = DanmakuCacheState.Init
        item.cacheGeneration = -1
        item.pendingCacheGeneration = -1
    }

    private fun clearLaneReferenceIfMatch(item: DanmakuItem) {
        when (item.kind) {
            DanmakuKind.SCROLL -> if (item.lane in laneLastScroll.indices && laneLastScroll[item.lane] === item) laneLastScroll[item.lane] = null
            DanmakuKind.TOP -> if (item.lane in laneLastTop.indices && laneLastTop[item.lane] === item) laneLastTop[item.lane] = null
            DanmakuKind.BOTTOM -> if (item.lane in laneLastBottom.indices && laneLastBottom[item.lane] === item) laneLastBottom[item.lane] = null
        }
    }

    private fun isExpired(
        item: DanmakuItem,
        width: Int,
        nowMs: Int,
    ): Boolean = expireReason(item, width, nowMs) != null

    /**
     * 返回过期原因，null 表示未过期。拆出来是为了让 pruneExpired 的日志能区分：
     * - cacheWaitTimeout：缓存等待超时（MAX_CACHE_WAIT_MS 内没拿到缓存）→ 弹幕从未可见就被丢
     * - duration：已运动满 durationMs（正常退场）
     * - scrolledOut：滚动弹幕已完全离开屏幕左侧（正常退场）
     */
    private fun expireReason(
        item: DanmakuItem,
        width: Int,
        nowMs: Int,
    ): String? {
        if (isCacheWaitExpired(
                motionStarted = item.motionStarted,
                admittedAtMs = item.startTimeMs,
                nowMs = nowMs,
                timeoutMs = MAX_CACHE_WAIT_MS,
            )) {
            return "cacheWaitTimeout"
        }
        if (!item.motionStarted) return null
        val elapsed = nowMs - item.startTimeMs
        if (elapsed >= item.durationMs) return "duration"
        if (item.kind != DanmakuKind.SCROLL) return null
        return if (scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs) + item.textWidthPx < 0f) {
            "scrolledOut"
        } else {
            null
        }
    }

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isScrollLaneAvailable(
        width: Float,
        nowMs: Int,
        front: DanmakuItem,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        // 尚未有可绘制缓存的前一条弹幕仍占用轨道；它开始运动前不能在后面塞新弹幕。
        if (!front.motionStarted) return false
        val elapsedPrev = nowMs - front.startTimeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true
        if (tailPrev + marginPx > width) return false
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true
        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun activate(
        item: DanmakuItem,
        kind: DanmakuKind,
        lane: Int,
        textWidth: Float,
        pxPerMs: Float,
        durationMs: Int,
        startTimeMs: Int,
        layoutTopPx: Float,
    ) {
        item.kind = kind
        item.lane = lane
        item.textWidthPx = textWidth
        item.pxPerMs = pxPerMs
        item.durationMs = durationMs
        item.startTimeMs = startTimeMs
        item.motionStarted = false
        item.layoutTopPx = layoutTopPx
        active.add(item)
        admissionHistory.record(item.data)
        snapshotDirty = true
        // 弹幕入场记录。若同一 t=ms + text 出现两次 activate，即为"重复入场"。
        android.util.Log.w(
            TAG,
            "admit kind=$kind t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                "lane=$lane pxPerMs=${String.format("%.4f", pxPerMs)} dur=${durationMs}ms start=$startTimeMs active=${active.size}"
        )
    }

    private fun computeScrollDurationMs(distancePx: Float, pxPerMs: Float, fallbackDurationMs: Int): Int {
        val safeFallback = fallbackDurationMs.coerceAtLeast(1)
        if (!distancePx.isFinite() || distancePx <= 0f) return safeFallback
        if (!pxPerMs.isFinite() || pxPerMs <= 0f) return safeFallback
        val travel = ceil((distancePx / pxPerMs).toDouble()).toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return max(safeFallback, travel)
    }

    private fun skipOld(nowMs: Int, rollingDurationMs: Int) {
        val ignoreBefore = nowMs - rollingDurationMs
        while (index < items.size && items[index].timeMs() < ignoreBefore) {
            index++
        }
    }

    private fun dropIfLagging(nowMs: Int) {
        val dropBefore = nowMs - MAX_CATCH_UP_LAG_MS
        while (index < items.size && items[index].timeMs() < dropBefore) {
            index++
        }
    }

    private fun enqueuePending(item: DanmakuItem, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(
            PendingSpawn(
                item = item,
                nextTryMs = nowMs + DELAY_STEP_MS,
                firstTryMs = nowMs,
                retryCount = 0,
            ),
        )
    }

    private data class PendingSpawn(
        val item: DanmakuItem,
        var nextTryMs: Int,
        val firstTryMs: Int,
        var retryCount: Int,
    )

    private fun ensureLaneBuffers(laneCount: Int) {
        if (laneLastScroll.size != laneCount) laneLastScroll = arrayOfNulls(laneCount)
        if (laneLastTop.size != laneCount) laneLastTop = arrayOfNulls(laneCount)
        if (laneLastBottom.size != laneCount) laneLastBottom = arrayOfNulls(laneCount)
    }

    private fun kindOf(d: Danmaku): DanmakuKind =
        when (d.mode) {
            5 -> DanmakuKind.TOP
            4 -> DanmakuKind.BOTTOM
            else -> DanmakuKind.SCROLL
        }

    private fun measureTextWidth(item: DanmakuItem, outlinePad: Float): Float {
        if (item.measureGeneration == measureGeneration && item.measuredWidthPx.isFinite() && item.measuredWidthPx >= 0f) {
            return item.measuredWidthPx
        }
        val text = item.data.text
        val width = if (text.isBlank()) {
            outlinePad * 2f
        } else {
            actionPaint.measureText(text) + outlinePad * 2f
        }
        item.measuredWidthPx = width
        item.measureGeneration = measureGeneration
        return width
    }

    private fun lowerBound(pos: Int): Int {
        var l = 0
        var r = items.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (items[m].timeMs() < pos) l = m + 1 else r = m
        }
        return l
    }

    private fun centerX(width: Int, contentWidth: Float): Float {
        if (width <= 0) return 0f
        val x = (width.toFloat() - contentWidth) / 2f
        return x.coerceAtLeast(0f)
    }

    private fun computeRollingDurationMs(speedLevel: Int): Int {
        // Keep the speed scale aligned with the project's previous implementation.
        // (User feedback: new 10 ~= old 4 was too slow.)
        val speed = speedMultiplier(speedLevel)
        val duration = (DEFAULT_ROLLING_DURATION_MS / speed).toInt()
        return duration.coerceIn(MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS)
    }

    private fun speedMultiplier(level: Int): Float =
        // 对齐 akdanmaku toDanmakuDurationMs 各档时长：multiplier = 6000 / akMs。
        // （akdanmaku 档位 1→12000ms ... 9→2160ms，缺 4 档补 6000ms 对齐 level 4/5。）
        when (min(10, max(1, level))) {
            1 -> 0.50f   // 6000/12000
            2 -> 0.588f  // 6000/10200
            3 -> 0.714f  // 6000/8400
            4 -> 1.0f    // 6000/6000（akdanmaku 原 else 回退，此处显式对齐）
            5 -> 1.0f    // 6000/6000
            6 -> 1.25f   // 6000/4800
            7 -> 1.5625f // 6000/3840
            8 -> 2.0f    // 6000/3000
            9 -> 2.778f  // 6000/2160
            else -> 2.778f
        }

    private companion object {
        private const val TAG = "BTellyDmEngine"
        private const val DEFAULT_ROLLING_DURATION_MS = 6_000f
        private const val MIN_ROLLING_DURATION_MS = 2_000
        private const val MAX_ROLLING_DURATION_MS = 20_000

        private const val FIXED_DURATION_MS = 4_000
        private const val MAX_LONG_SCROLL_SPEED_RATIO = 1.5f

        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
        private const val MAX_SPAWN_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_COUNT = 1
        private const val MAX_CATCH_UP_LAG_MS = 1_200

        private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
        private const val MAX_CACHE_SCAN_PER_FRAME = 16
        private const val MAX_CACHE_QUEUE_DEPTH = 48
        private const val MAX_CACHE_WAIT_MS = 1_600
        private const val ADMISSION_HISTORY_PRUNE_INTERVAL_MS = 1_000

    }

}

/**
 * 仅保存当前重建窗口内已经真正 activate 过的弹幕，用计数处理同一数据在同一时点的多条实例。
 * 每次重建使用独立预算；消费预算不会改写历史，下一次前进重建仍会继续跳过同一条目。
 */
internal class DanmakuAdmissionHistory {
    private val counts = HashMap<Danmaku, Int>()

    fun record(data: Danmaku) {
        counts[data] = (counts[data] ?: 0) + 1
    }

    fun clear() {
        counts.clear()
    }

    fun pruneBefore(minimumTimeMs: Int) {
        val iterator = counts.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().timeMs < minimumTimeMs) iterator.remove()
        }
    }

    fun replayBudget(): ReplayBudget = ReplayBudget(HashMap(counts))

    internal class ReplayBudget(private val remaining: MutableMap<Danmaku, Int>) {
        /** Returns true only once for each previously admitted matching instance. */
        fun consume(data: Danmaku): Boolean {
            val count = remaining[data] ?: return false
            if (count <= 1) remaining.remove(data) else remaining[data] = count - 1
            return true
        }
    }
}

/**
 * Direct engine callers may deliver an old segment after a later one. The part
 * inside the active rolling lifetime is deliberately left untouched; changing
 * it would restart visible comments or replay items admitted in this pass.
 */
internal fun resolveOutOfOrderAppendPatchStartMs(
    firstIncomingTimeMs: Int,
    currentPositionMs: Long,
    rollingDurationMs: Int,
): Int = maxOf(
    firstIncomingTimeMs.toLong(),
    currentPositionMs.coerceAtLeast(0L) + rollingDurationMs.coerceAtLeast(0),
).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Builds the replacement suffix for a protected out-of-order append. */
internal fun mergeDanmakuFutureTail(
    existing: List<Danmaku>,
    incoming: List<Danmaku>,
    minTimeMs: Int,
): List<Danmaku> =
    (existing.asSequence() + incoming.asSequence())
        .filter { it.timeMs >= minTimeMs }
        .sortedBy { it.timeMs }
        .toList()
