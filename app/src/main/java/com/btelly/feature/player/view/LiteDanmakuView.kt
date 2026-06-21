package com.btelly.feature.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LongSparseArray
import android.view.View
import com.btelly.core.common.log.AppLog
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量弹幕渲染 View（性能优先引擎）。
 *
 * 单 View、纯主线程、postInvalidateOnAnimation 自驱动、canvas.drawText 直绘。
 * 轨道分配采用 上游实现 参考项目的**固定 lane 模型**：
 *  - lane 数量按屏幕高度 × area 预计算，每个 lane 固定 y 坐标
 *  - 滚动/顶部/底部三套独立 lane
 *  - 弹幕遍历 lane 找空闲或可碰撞的
 *  - startTime/pxPerMs 存 item 自身，lane 只存最后一条弹幕引用
 */
class LiteDanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SLOW_FRAME_MS = 16L
        private const val CANVAS_PAD = 4f
        private const val DEFAULT_DURATION_MS = 8000L
        private const val FIXED_DURATION_MS = 4000L
        private const val MAX_SPEED_RATIO = 1.5f
        // pending 重试
        private const val PENDING_RETRY_STEP_MS = 220L
        private const val MAX_PENDING_DELAY_MS = 1600L
        private const val MAX_PENDING = 260
        private const val MAX_PENDING_RETRY = 1
        private const val MAX_PENDING_PER_FRAME = 48
    }

    private object Mode {
        const val ROLLING = 1
        const val BOTTOM = 4
        const val TOP = 5
    }

    class RollingItem(
        val id: Long,
        val positionMs: Long,
        val mode: Int,
        val content: String,
        val color: Int,
        val textSizePx: Float,
        val width: Float,
        val height: Float
    ) {
        var actualStartMs: Long = Long.MIN_VALUE
        var pxPerMs: Float = 0f
        var itemDurationMs: Long = 0L
        /** 固定弹幕的退场时间。 */
        var expireMs: Long = 0L
        /** 分配到的 lane 索引（-1=未分配）。 */
        var lane: Int = -1
    }

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val density: Float = context.resources.displayMetrics.density

    private var items: List<RollingItem> = emptyList()

    // ---- 固定 lane 模型（对齐 上游实现）----
    private var laneScroll: Array<RollingItem?> = emptyArray()
    private var laneTop: Array<RollingItem?> = emptyArray()
    private var laneBottom: Array<RollingItem?> = emptyArray()
    private var laneTopY: FloatArray = FloatArray(0) // 每个 lane 的 y 坐标（TOP/SCROLL 用，从上往下）
    private var laneBottomY: FloatArray = FloatArray(0) // BOTTOM 弹幕 y 坐标（从下往上排）
    private var laneHeightPx: Float = 0f
    private var configuredLaneCount: Int = 0
    private var lastLaneConfigHeight: Int = 0
    private var lastLaneConfigArea: Float = -1f
    private var lastLaneConfigTextSize: Float = 0f

    /** pending 重试队列 */
    private data class PendingSpawn(
        val item: RollingItem,
        var nextTryMs: Long,
        val firstTryMs: Long,
        var retryCount: Int
    )
    private val pendingQueue = ArrayDeque<PendingSpawn>()

    /** seek 重建期间禁用 pending 重试（对齐 上游实现 rebuildScene(allowPending=false)），失败直接丢弃 */
    private var rebuildActive = false

    /** 已分配过轨道的弹幕 id 集合（防止重复分配 + 快速判断是否已分配） */
    private val assignedIds = LongSparseArray<Boolean>()

    // 播放时钟
    private var basePositionMs = 0L
    private var baseRealtimeMs = -1L   // -1 = 未初始化（初值 0 会与 elapsedRealtime 算出几百万 ms 的 now）
    private var isPlaying = false
    private var playbackSpeed = 1f

    // 设置
    private var enabled = true
    private var globalAlpha = 1f
    private var durationMs = DEFAULT_DURATION_MS
    private var screenPart = 1f
    private var allowTop = true
    private var allowBottom = true

    // perf
    private var perfFrameCount = 0
    private var perfSlowCount = 0
    private var perfTotalMs = 0L
    private var perfMaxMs = 0L
    private var perfWindowStartMs = 0L
    private var perfTrackRejected = 0

    init {
        isClickable = false
        isFocusable = false
    }

    // ---- 数据注入 ----

    fun setItems(newItems: List<RollingItem>) {
        // 只更新数据，不清 lane 运行时状态——否则正在滚的弹幕全部重置（"半路消失"根因）。
        // 新弹幕的 lane=-1、actualStartMs=MIN_VALUE，会自然被 drawRolling 分配。
        items = newItems
        invalidate()
    }

    fun clearItems() {
        items = emptyList()
        clearAll()
        invalidate()
    }

    // ---- 设置 ----

    fun setRenderingConfig(
        enabled: Boolean,
        alpha: Float,
        @Suppress("UNUSED_PARAMETER") textScale: Float,
        screenPart: Float,
        allowTop: Boolean,
        allowBottom: Boolean,
        durationMs: Long
    ) {
        this.enabled = enabled
        this.globalAlpha = alpha.coerceIn(0.1f, 1f)
        this.screenPart = screenPart.coerceIn(0.05f, 1f)
        this.allowTop = allowTop
        this.allowBottom = allowBottom
        this.durationMs = durationMs.coerceAtLeast(1000L)
        visibility = if (enabled) VISIBLE else GONE
        invalidate()
    }

    fun syncPlayback(positionMs: Long, playing: Boolean, speed: Float) {
        basePositionMs = positionMs.coerceAtLeast(0L)
        baseRealtimeMs = SystemClock.elapsedRealtime()
        isPlaying = playing
        playbackSpeed = speed.coerceAtLeast(0.1f)
        invalidate()
    }

    /**
     * seek 后**同步重建**场景（对齐 上游实现 rebuildScene）。
     *
     * 清掉所有 lane 绑定后，立即把当前播放窗口内（now - 2*duration 到 now）的所有弹幕
     * 按时间顺序逐条入轨：碰撞通过的入轨（startTime=now，从右边缘重生），失败的直接丢弃
     * （不进 pending，对齐 上游实现 allowPending=false）。
     *
     * 必须一次性同步完成，不能分帧——分帧会导致跨帧弹幕 startTime 接近但又不完全相同，
     * 碰撞检测在跨帧间失效，反而造成重叠。
     */
    fun seekReset() {
        val now = currentPlaybackPositionMs().coerceAtLeast(0L)
        // 1. 清空所有 lane 绑定 + 分配状态
        for (i in laneScroll.indices) laneScroll[i] = null
        for (i in laneTop.indices) laneTop[i] = null
        for (i in laneBottom.indices) laneBottom[i] = null
        assignedIds.clear()
        pendingQueue.clear()
        rebuildActive = false
        items.forEach {
            it.lane = -1
            it.actualStartMs = Long.MIN_VALUE
        }
        // 2. 同步重建：窗口内弹幕全部尝试入轨（碰撞失败丢弃，不进 pending）
        updateLaneConfigIfNeeded()
        if (width > 0 && height > 0 && configuredLaneCount > 0 && items.isNotEmpty()) {
            val w = width.toFloat()
            val windowStart = now - durationMs * 2
            val startIdx = lowerBound(windowStart)
            var i = startIdx
            while (i < items.size) {
                val item = items[i]
                if (item.positionMs > now) break
                if (item.positionMs >= windowStart) {
                    if (item.mode == Mode.TOP || item.mode == Mode.BOTTOM) {
                        tryAdmitFixed(item, now)
                    } else {
                        // 重建期间不入 pending（tryAdmitScroll 内部用 rebuildActive 判断）
                        rebuildActive = true
                        tryAdmitScroll(item, now, w)
                        rebuildActive = false
                    }
                }
                i++
            }
        }
        invalidate()
    }

    // ---- 绘制 ----

    private val deferredFixed = ArrayList<RollingItem>()

    override fun onDraw(canvas: Canvas) {
        if (!enabled || items.isEmpty() || width <= 0 || height <= 0) return
        val startedAtMs = SystemClock.elapsedRealtime()
        val now = currentPlaybackPositionMs()

        updateLaneConfigIfNeeded()
        if (configuredLaneCount <= 0) return

        val w = width.toFloat()
        val maxBottom = height * screenPart
        deferredFixed.clear()

        var drawn = 0
        val list = items
        // 二分窗口：只看 now-2*duration 到 now 的弹幕
        val startIdx = lowerBound(now - durationMs * 2)
        var i = startIdx
        while (i < list.size) {
            val item = list[i]
            if (item.positionMs > now) break
            if (item.mode == Mode.TOP || item.mode == Mode.BOTTOM) {
                deferredFixed.add(item)
            } else {
                if (drawRolling(canvas, item, now, w)) drawn++
            }
            i++
        }
        // 顶/底弹幕后画（上层，不被滚动弹幕遮挡）
        for (fixedItem in deferredFixed) {
            if (drawFixed(canvas, fixedItem, now, w, maxBottom)) drawn++
        }

        // pending 重试
        processPending(now, w, maxBottom)
        // 回收超时绑定
        recycleExpiredLanes(now)

        val costMs = SystemClock.elapsedRealtime() - startedAtMs
        recordPerf(costMs, drawn, now)

        if (isPlaying && enabled) postInvalidateOnAnimation()
    }

    private fun drawRolling(canvas: Canvas, item: RollingItem, now: Long, screenWidth: Float): Boolean {
        // 已滚完
        if (item.actualStartMs != Long.MIN_VALUE && now > item.actualStartMs + item.itemDurationMs) return false
        // 首次分配轨道（碰撞检测会自然限制每帧入轨量，不需要节流）
        if (item.lane < 0) {
            if (!tryAdmitScroll(item, now, screenWidth)) {
                enqueuePending(item, now)
                perfTrackRejected++
                return false
            }
        }
        val y = laneTopY[item.lane]
        val x = scrollX(screenWidth, now, item.actualStartMs, item.pxPerMs)
        drawText(canvas, item, x, y)
        return true
    }

    private fun drawFixed(canvas: Canvas, item: RollingItem, now: Long, screenWidth: Float, maxBottom: Float): Boolean {
        val allowed = (item.mode == Mode.TOP && allowTop) || (item.mode == Mode.BOTTOM && allowBottom)
        if (!allowed) return false
        // 首次分配
        if (item.lane < 0) {
            if (!tryAdmitFixed(item, now)) return false
        }
        // 超时
        if (now > item.expireMs) return false
        // TOP 用从上往下的 Y，BOTTOM 用从下往上的 Y（对齐 上游实现）
        val y = if (item.mode == Mode.BOTTOM) laneBottomY[item.lane] else laneTopY[item.lane]
        val x = ((screenWidth - item.width) * 0.5f).coerceAtLeast(0f)
        drawText(canvas, item, x, y)
        return true
    }

    private fun scrollX(screenWidth: Float, now: Long, startTimeMs: Long, pxPerMs: Float): Float {
        val elapsed = (now - startTimeMs).coerceAtLeast(0L).toFloat()
        return screenWidth - elapsed * pxPerMs
    }

    // ---- 轨道分配（对齐 上游实现 trySpawnScroll / trySpawnFixed）----

    private fun tryAdmitScroll(item: RollingItem, now: Long, screenWidth: Float): Boolean {
        // 已分配过（防同一条弹幕因 items 列表重复而被分配到多个 lane）
        if (assignedIds.get(item.id) != null) return item.lane >= 0
        // 对齐 上游实现: startTime = 入轨时刻（now），不用 positionMs。
        // 弹幕在"被分配到 lane 的那一刻"从屏幕右边缘出生，而不是按原始时间戳出现在屏幕中间——
        // 后者会导致 seek 后大量弹幕在不同 x 位置挤进同一 lane（重叠根因）。
        if (item.actualStartMs == Long.MIN_VALUE) {
            item.actualStartMs = now
        }

        // 速度模型（对齐 上游实现 trySpawnScroll）:
        //   shortPx = screenWidth / durationMs                 （最短弹幕的基准速度）
        //   rawPx   = (screenWidth + textWidth) / durationMs   （本条弹幕的自然速度）
        //   pxPerMs = min(rawPx, shortPx * MAX_SPEED_RATIO)    （长弹幕允许加速但封顶 1.5×）
        // 这样 isScrollLaneAvailable 的 maxSafe=(pxNew-pxPrev)×prevRemaining 追尾预测才会真正生效——
        // 之前"全统一速度"导致 pxNew==pxPrev 恒成立，碰撞检测退化成"前车右尾进屏即放行"，
        // 是长弹幕堆底部 + seek 后重叠的直接根因。
        val distancePx = (screenWidth + item.width).coerceAtLeast(0f)
        val dur = durationMs.toFloat().coerceAtLeast(1f)
        val shortPx = screenWidth / dur
        val rawPx = distancePx / dur
        val pxPerMs = min(rawPx, shortPx * MAX_SPEED_RATIO)
        item.pxPerMs = pxPerMs
        item.itemDurationMs = if (pxPerMs > 0f)
            (distancePx / pxPerMs).toLong().coerceAtLeast(durationMs)
        else durationMs
        val marginPx = maxOf(12f, item.textSizePx * 0.6f)

        // 单趟遍历 lane 0→N（对齐 上游实现）：第一个可用的 lane 就用，不分"空闲优先"两轮。
        // 空闲=无弹幕或已滚出屏；否则做碰撞检测。单趟保证弹幕从上往下均匀填，避免两轮策略
        // 把新弹幕推到底部空 lane。
        for (lane in 0 until configuredLaneCount) {
            val prev = laneScroll[lane]
            if (prev != null) {
                if (prev.id == item.id) return true // 防御：同一条已在别的 lane
                val prevX = scrollX(screenWidth, now, prev.actualStartMs, prev.pxPerMs)
                if (prevX + prev.width < 0 || now > prev.actualStartMs + prev.itemDurationMs) {
                    laneScroll[lane] = null
                }
            }
            val rear = laneScroll[lane]
            if (rear == null) {
                // 空闲 lane
                item.lane = lane
                laneScroll[lane] = item
                assignedIds.put(item.id, true)
                return true
            }
            // 碰撞检测
            val tailPrev = scrollX(screenWidth, now, rear.actualStartMs, rear.pxPerMs) + rear.width
            if (isScrollLaneAvailable(screenWidth, rear, pxPerMs, marginPx, tailPrev, now)) {
                item.lane = lane
                laneScroll[lane] = item
                assignedIds.put(item.id, true)
                return true
            }
        }
        return false
    }

    private fun isScrollLaneAvailable(
        screenWidth: Float,
        front: RollingItem,
        pxNew: Float,
        marginPx: Float,
        tailPrev: Float,
        now: Long
    ): Boolean {
        if (tailPrev + marginPx > screenWidth) return false
        if (pxNew <= front.pxPerMs) return true
        val elapsedPrev = (now - front.actualStartMs).coerceAtLeast(0L)
        val prevRemaining = (front.itemDurationMs - elapsedPrev).coerceAtLeast(0L).toFloat()
        if (prevRemaining <= 0f) return true
        val gap0 = (screenWidth - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - front.pxPerMs) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun tryAdmitFixed(item: RollingItem, now: Long): Boolean {
        val lanes = if (item.mode == Mode.TOP) laneTop else laneBottom
        val duration = FIXED_DURATION_MS
        item.expireMs = now + duration
        for (lane in 0 until configuredLaneCount) {
            val prev = lanes[lane]
            if (prev != null && now > prev.expireMs) {
                lanes[lane] = null
            }
            if (lanes[lane] != null) continue
            item.lane = lane
            item.actualStartMs = now
            lanes[lane] = item
            assignedIds.put(item.id, true)
            return true
        }
        return false
    }

    // ---- pending 重试 ----

    private fun enqueuePending(item: RollingItem, now: Long) {
        // seek 重建期间不重试——对齐 上游实现 rebuildScene(allowPending=false)，失败直接丢弃
        if (rebuildActive) return
        if (assignedIds.get(item.id) != null) return
        if (pendingQueue.any { it.item.id == item.id }) return
        if (now - item.positionMs > MAX_PENDING_DELAY_MS) return
        if (pendingQueue.size >= MAX_PENDING) pendingQueue.removeFirst()
        pendingQueue.addLast(PendingSpawn(item, now + PENDING_RETRY_STEP_MS, now, 0))
    }

    private fun processPending(now: Long, screenWidth: Float, maxBottom: Float) {
        if (pendingQueue.isEmpty()) return
        var processed = 0
        val count = pendingQueue.size
        var i = 0
        while (i < count && pendingQueue.isNotEmpty()) {
            i++
            val entry = pendingQueue.removeFirst()
            if (entry.nextTryMs > now) { pendingQueue.addLast(entry); continue }
            val age = now - entry.firstTryMs
            if (entry.retryCount >= MAX_PENDING_RETRY || age >= MAX_PENDING_DELAY_MS) continue
            if (processed >= MAX_PENDING_PER_FRAME) {
                entry.retryCount++; entry.nextTryMs = now + PENDING_RETRY_STEP_MS
                pendingQueue.addLast(entry); continue
            }
            processed++
            val item = entry.item
            val admitted = if (item.mode == Mode.TOP || item.mode == Mode.BOTTOM) {
                tryAdmitFixed(item, now)
            } else {
                tryAdmitScroll(item, now, screenWidth)
            }
            if (!admitted) {
                entry.retryCount++; entry.nextTryMs = now + PENDING_RETRY_STEP_MS
                pendingQueue.addLast(entry)
            }
        }
    }

    // ---- lane 配置 ----

    private fun updateLaneConfigIfNeeded() {
        val h = height
        if (h <= 0) return
        // 计算期望的 lane 数量
        val sampleHeight = if (items.isNotEmpty()) items[0].height else density * 18f
        val newLaneHeight = max(sampleHeight * 1.15f, density * 16f)
        val usableHeight = (h * screenPart).toInt().coerceAtLeast(0)
        val newLaneCount = max(1, (usableHeight / newLaneHeight).toInt())
        // 只有 lane 数量变化才重建（高度/区域设置变了）。数量没变不动运行时状态。
        if (newLaneCount == configuredLaneCount && laneScroll.isNotEmpty()) {
            laneHeightPx = newLaneHeight
            return
        }
        lastLaneConfigHeight = h
        lastLaneConfigArea = screenPart
        laneHeightPx = newLaneHeight
        configuredLaneCount = newLaneCount
        laneScroll = arrayOfNulls(newLaneCount)
        laneTop = arrayOfNulls(newLaneCount)
        laneBottom = arrayOfNulls(newLaneCount)
        laneTopY = FloatArray(newLaneCount) { lane -> (lane * newLaneHeight).toFloat() }
        // BOTTOM 弹幕从屏幕底部往上排（对齐 上游实现: maxYTop - laneHeight*lane）
        val maxYTop = (h * screenPart - newLaneHeight).coerceAtLeast(0f)
        laneBottomY = FloatArray(newLaneCount) { lane -> (maxYTop - lane * newLaneHeight).coerceAtLeast(0f) }
        // lane 数量变了，清掉分配状态（让弹幕重新分配到新 lane）
        assignedIds.clear()
        pendingQueue.clear()
        items.forEach { it.lane = -1; it.actualStartMs = Long.MIN_VALUE }
    }

    private fun recycleExpiredLanes(now: Long) {
        val w = width.toFloat()
        // 滚动弹幕：当右边缘滚出屏幕左边（x + width < 0）或超时，释放 lane。
        // 用位置判断比用时间更早释放——弹幕滚出屏幕后 lane 立即可用，不用等 duration 结束。
        for (i in laneScroll.indices) {
            val item = laneScroll[i] ?: continue
            val x = scrollX(w, now, item.actualStartMs, item.pxPerMs)
            if (x + item.width < 0 || now > item.actualStartMs + item.itemDurationMs) {
                laneScroll[i] = null
            }
        }
        for (i in laneTop.indices) {
            val item = laneTop[i] ?: continue
            if (now > item.expireMs) laneTop[i] = null
        }
        for (i in laneBottom.indices) {
            val item = laneBottom[i] ?: continue
            if (now > item.expireMs) laneBottom[i] = null
        }
    }

    private fun clearAll() {
        laneScroll = emptyArray(); laneTop = emptyArray(); laneBottom = emptyArray()
        laneTopY = FloatArray(0); laneBottomY = FloatArray(0)
        configuredLaneCount = 0
        assignedIds.clear()
        pendingQueue.clear()
        lastLaneConfigHeight = 0
        lastLaneConfigArea = -1f
    }

    // ---- 绘制 ----

    private fun drawText(canvas: Canvas, item: RollingItem, x: Float, topY: Float) {
        val alpha255 = (globalAlpha * 255).toInt().coerceIn(0, 255)
        fillPaint.isAntiAlias = true
        fillPaint.textSize = item.textSizePx
        fillPaint.color = item.color or 0xFF000000.toInt()
        fillPaint.alpha = alpha255
        fillPaint.style = Paint.Style.FILL

        strokePaint.isAntiAlias = true
        strokePaint.textSize = item.textSizePx
        strokePaint.color = strokeColorFor(item.color)
        strokePaint.alpha = alpha255
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = (item.textSizePx * 0.09f).coerceIn(1.5f, 3f)

        val baseline = topY - fillPaint.ascent() + CANVAS_PAD * 0.5f
        canvas.drawText(item.content, x, baseline, strokePaint)
        canvas.drawText(item.content, x, baseline, fillPaint)
    }

    private fun strokeColorFor(textColor: Int): Int {
        val r = Color.red(textColor); val g = Color.green(textColor); val b = Color.blue(textColor)
        val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        return if (luminance > 0.5f) (230 shl 24) or 0x000000 else (210 shl 24) or 0xFFFFFF
    }

    // ---- 工具 ----

    private fun currentPlaybackPositionMs(): Long {
        // 时钟未初始化（baseRealtimeMs 还是 -1）时不要按 elapsedRealtime 推算——
        // 否则 now 会算成几百万 ms（设备启动以来的毫秒数），所有弹幕被当成"已到点"一次性塞进来 → 大量重叠。
        if (baseRealtimeMs < 0L || !isPlaying) return basePositionMs
        val elapsedMs = SystemClock.elapsedRealtime() - baseRealtimeMs
        return (basePositionMs + elapsedMs * playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun lowerBound(target: Long): Int {
        val list = items
        var lo = 0; var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].positionMs < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun recordPerf(costMs: Long, drawCount: Int, now: Long) {
        perfFrameCount++
        perfTotalMs += costMs
        if (costMs > perfMaxMs) perfMaxMs = costMs
        if (costMs >= SLOW_FRAME_MS) {
            perfSlowCount++
            AppLog.w("PlaybackPerf", "lite_danmaku_draw cost=${costMs}ms drawn=$drawCount total=${items.size}")
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (perfWindowStartMs == 0L) perfWindowStartMs = nowMs
        if (nowMs - perfWindowStartMs < 2000L) return
        val avg = perfTotalMs.toFloat() / perfFrameCount.coerceAtLeast(1)
        // 详细 lane 诊断：打印每个滚动 lane 上的弹幕信息
        val laneDump = buildString {
            for (lane in laneScroll.indices) {
                val item = laneScroll[lane] ?: continue
                val x = scrollX(width.toFloat(), now, item.actualStartMs, item.pxPerMs)
                append("L$lane:id${item.id % 10000} w${item.width.toInt()} x${x.toInt()} ")
            }
        }
        AppLog.d(
            "PlaybackPerf",
            "lite_danmaku_summary frames=$perfFrameCount slow=$perfSlowCount " +
                "avg=${"%.2f".format(java.util.Locale.US, avg)}ms max=${perfMaxMs}ms " +
                "items=${items.size} rejected=$perfTrackRejected lanes=$configuredLaneCount " +
                "pending=${pendingQueue.size} now=${now} dur=$durationMs " +
                "scrollLanes=${laneScroll.count { it != null }} | $laneDump"
        )
        perfWindowStartMs = nowMs
        perfFrameCount = 0
        perfSlowCount = 0
        perfTotalMs = 0L
        perfMaxMs = 0L
        perfTrackRejected = 0
    }

    fun measureText(content: String, textSizePx: Float): Pair<Float, Float> {
        fillPaint.textSize = textSizePx
        fillPaint.typeface = android.graphics.Typeface.DEFAULT
        val width = fillPaint.measureText(content)
        val fm = fillPaint.fontMetrics
        return width to (fm.descent - fm.ascent)
    }

    fun densityFactor(): Float = density
}
