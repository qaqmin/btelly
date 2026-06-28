package com.btelly.feature.player.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import com.btelly.feature.player.danmaku.model.DanmakuItem
import com.btelly.feature.player.danmaku.model.SharedCacheEntry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

internal data class CacheStyle(
    val textSizePx: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Float,
    val outlinePadPx: Float,
    val generation: Int,
)

internal class CacheManager(
    private val appContext: Context,
    private val density: Float,
    private val mainLooper: Looper,
    private val onRenderSign: () -> Unit,
) {
    companion object {
        private const val TAG = "DanmakuCache"

        private const val MSG_BUILD_CACHE = 2001
        private const val MSG_CLEAR = 2002
        private const val MSG_RELEASE = 2099

        private const val CACHE_POOL_MAX_BYTES: Long = 50L * 1024L * 1024L
        private const val CACHE_POOL_MAX_COUNT: Int = 72

        private const val MAX_RELEASE_PER_DRAIN = 24
        private const val MAX_SHARED_CACHE = 256

        // FNV-1a 64-bit（与 akdanmaku sharedCacheKey 同算法）
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }

    private val mainHandler = Handler(mainLooper)

    private val thread: HandlerThread =
        HandlerThread("Danmaku-Cache").apply {
            start()
            runCatching { Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_BACKGROUND) }
        }

    private val handler: Handler = CacheHandler(thread.looper)

    private val pool = BitmapPool(maxBytes = CACHE_POOL_MAX_BYTES, maxCount = CACHE_POOL_MAX_COUNT)

    /**
     * 共享缓存表：相同内容（text+color+textSize+stroke+typeface）的弹幕共享同一个 SharedCacheEntry。
     * accessOrder=true 实现 LRU，超 MAX_SHARED_CACHE 时淘汰最久未访问条目。
     * 只在 CacheHandler 线程访问（buildCache/查询/淘汰），无需加锁。
     * 淘汰时 release 表持有的那份引用；bitmap 是否回收由引用计数决定（item 可能还在用）。
     */
    private val sharedCacheStore = object : java.util.LinkedHashMap<Long, SharedCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SharedCacheEntry>?): Boolean {
            if (size <= MAX_SHARED_CACHE) return false
            eldest?.value?.let { entry ->
                // 释放共享表持有的那份引用。若此时已无 item 引用，bitmap 立刻可回收；
                // 若仍有 item 在用，bitmap 等最后一个 item release 时才回收。
                if (entry.release()) {
                    enqueueRelease(entry, releaseAtFrameId = 0)
                }
            }
            return true
        }
    }
    private var sharedCacheGeneration = -1
    private val sharedHit = AtomicLong(0L)

    private val queueDepth = AtomicInteger(0)
    private val releaseQueue: ConcurrentLinkedQueue<PendingRelease> = ConcurrentLinkedQueue()

    private val bitmapCreated = AtomicLong(0L)
    private val bitmapReused = AtomicLong(0L)
    private val bitmapPutToPool = AtomicLong(0L)
    private val bitmapRecycled = AtomicLong(0L)

    // Cache draw tools (cache thread only).
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        // 对齐 akdanmaku SimpleRenderer + DanmakuEngine.drawFill：不开 subpixel，
        // 避免 TV/OLED 上纯色文字边缘 RGB 子像素分离导致整体发灰。
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        // 对齐 akdanmaku：ROUND 连接，拐角不尖刺，保留更多彩色像素。
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fontMetrics = Paint.FontMetrics()

    fun queueDepth(): Int = queueDepth.get().coerceAtLeast(0)

    fun poolSnapshot(): PoolSnapshot = pool.snapshot()

    fun statsSnapshot(): StatsSnapshot =
        StatsSnapshot(
            bitmapCreated = bitmapCreated.get(),
            bitmapReused = bitmapReused.get(),
            bitmapPutToPool = bitmapPutToPool.get(),
            bitmapRecycled = bitmapRecycled.get(),
            sharedHit = sharedHit.get(),
        )

    fun requestBuildCache(
        item: DanmakuItem,
        textWidthPx: Float,
        style: CacheStyle,
        releaseAtFrameId: Int,
    ) {
        val payload = CacheRequest(item = item, textWidthPx = textWidthPx, style = style, releaseAtFrameId = releaseAtFrameId)
        queueDepth.incrementAndGet()
        handler.obtainMessage(MSG_BUILD_CACHE, payload).sendToTarget()
    }

    fun enqueueRelease(entry: SharedCacheEntry?, releaseAtFrameId: Int) {
        if (entry == null) return
        if (entry.isRecycled) return
        releaseQueue.add(PendingRelease(entry = entry, releaseAtFrameId = releaseAtFrameId))
    }

    fun drainReleasedBitmaps(currentFrameId: Int) {
        var drained = 0
        while (drained < MAX_RELEASE_PER_DRAIN) {
            val head = releaseQueue.peek() ?: break
            if (head.releaseAtFrameId > currentFrameId) break
            releaseQueue.poll()
            drained++
            val entry = head.entry
            if (entry.isRecycled) continue
            // release() 归零说明没有其他持有者（item 已退场 + 共享表已淘汰），可安全回收。
            if (!entry.release()) continue
            val bmp = entry.bitmap
            if (bmp.isRecycled) continue
            val pooled = pool.tryPut(bmp)
            if (!pooled) {
                runCatching { bmp.recycle() }
                bitmapRecycled.incrementAndGet()
            } else {
                bitmapPutToPool.incrementAndGet()
            }
        }
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_CLEAR)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_RELEASE)
    }

    private inner class CacheHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_BUILD_CACHE -> {
                    val req = msg.obj as? CacheRequest ?: return
                    queueDepth.decrementAndGet()
                    buildCache(req)
                }
                MSG_CLEAR -> {
                    queueDepth.set(0)
                    clearSharedCacheStore()
                    pool.clear()
                    releaseQueue.clear()
                }
                MSG_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    queueDepth.set(0)
                    clearSharedCacheStore()
                    pool.clear()
                    releaseQueue.clear()
                    runCatching { thread.quitSafely() }
                }
            }
        }
    }

    private fun buildCache(req: CacheRequest) {
        val item = req.item
        val style = req.style

        // Skip if already has a matching cache.
        val existing = item.cacheEntry
        if (existing != null && !existing.isRecycled && item.cacheGeneration == style.generation) {
            item.cacheState = com.btelly.feature.player.danmaku.model.DanmakuCacheState.Rendered
            return
        }

        if (style.textSizePx <= 0f || !style.textSizePx.isFinite()) return

        // 样式 generation 变化时清空共享表（旧 bitmap 内容已不匹配新样式）。
        if (sharedCacheGeneration != style.generation) {
            clearSharedCacheStore()
            sharedCacheGeneration = style.generation
        }

        // 共享缓存命中：相同内容（text+color+textSize+stroke+typeface）的弹幕复用同一 bitmap。
        val danmaku = item.data
        val key = sharedCacheKey(
            text = danmaku.text,
            color = danmaku.color and 0xFFFFFF,
            textSizePx = style.textSizePx,
            typefaceOrdinal = style.fontWeight.ordinal,
            strokeWidthPx = style.strokeWidthPx,
            outlinePadPx = style.outlinePadPx,
        )
        val shared = sharedCacheStore[key]
        if (shared != null && !shared.isRecycled) {
            // 命中：item 持有一份引用，跳过绘制。
            shared.acquire()
            assignEntryToItem(item, shared, style)
            sharedHit.incrementAndGet()
            mainHandler.post {
                runCatching { onRenderSign() }.onFailure { Log.w(TAG, "renderSign failed", it) }
            }
            return
        }
        // 命中失败（bitmap 已回收）时剔除脏条目。
        if (shared != null) {
            sharedCacheStore.remove(key)
            if (shared.release()) {
                enqueueRelease(shared, releaseAtFrameId = 0)
            }
        }

        // ---- 未命中：构建新 bitmap（原有逻辑）----
        val outlinePad = style.outlinePadPx.coerceAtLeast(0f)
        val strokeWidth = style.strokeWidthPx.coerceAtLeast(0f)

        val desiredTypeface = style.fontWeight.typeface
        if (fill.typeface != desiredTypeface) fill.typeface = desiredTypeface
        if (stroke.typeface != desiredTypeface) stroke.typeface = desiredTypeface

        fill.textSize = style.textSizePx
        stroke.textSize = style.textSizePx
        stroke.strokeWidth = strokeWidth

        fill.getFontMetrics(fontMetrics)
        // 度量高度对齐 akdanmaku 与 DanmakuEngine.act()：descent - ascent + leading。
        val textHeightPx = (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading).coerceAtLeast(1f)
        val boxHeight = ceil(textHeightPx + outlinePad * 2f).toInt().coerceAtLeast(1)
        val boxWidth = ceil(req.textWidthPx.coerceAtLeast(outlinePad * 2f)).toInt().coerceAtLeast(1)

        val bmp =
            pool.acquire(minWidth = boxWidth, minHeight = boxHeight)
                ?.also { bitmapReused.incrementAndGet() }
                ?: runCatching { Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888) }.getOrNull()
                    ?.also { bitmapCreated.incrementAndGet() }
                ?: return

        // Always clear when reusing.
        runCatching { bmp.eraseColor(0x00000000) }

        val canvas = Canvas(bmp)

        val rgb = danmaku.color and 0xFFFFFF
        // 描边色用老版本逻辑（对齐 AkDanmaku SimpleRenderer）：亮字配黑描边，暗字配白描边。
        // Bitmap 烘焙时用完全不透明（alpha 255），整体透明度由 drawTextDirect/drawBitmap 时按
        // opacityAlpha 统一施加。
        stroke.color = resolveStandardStrokeColor(rgb, opacityAlpha = 255)
        fill.color = (0xFF shl 24) or rgb

        val baseline = outlinePad - fontMetrics.ascent
        val text = danmaku.text
        val drawStrokeEnabled = strokeWidth > 0.01f
        if (text.isNotBlank()) {
            // 性能优先引擎只渲染纯文字（描边+填充），不支持内联表情/高赞图标。
            if (drawStrokeEnabled) canvas.drawText(text, outlinePad, baseline, stroke)
            canvas.drawText(text, outlinePad, baseline, fill)
        }

        // 构建完成：包成 SharedCacheEntry，item 一份引用 + 共享表一份引用。
        val entry = SharedCacheEntry(bmp)
        entry.acquire() // item 持有
        entry.acquire() // 共享表持有（避免最后一个 item 离场后 bitmap 立刻回收）
        sharedCacheStore[key] = entry
        assignEntryToItem(item, entry, style)

        // Signal renderer to refresh. Throttle is intentionally handled by UI frame pacing.
        mainHandler.post {
            runCatching { onRenderSign() }.onFailure { Log.w(TAG, "renderSign failed", it) }
        }
    }

    /**
     * 把 entry 挂到 item 上，并释放 item 之前持有的旧 entry 引用。
     * item 在 buildCache 命中/构建完成时调用。
     */
    private fun assignEntryToItem(item: DanmakuItem, entry: SharedCacheEntry, style: CacheStyle) {
        val old = item.cacheEntry
        item.cacheEntry = entry
        item.cacheGeneration = style.generation
        item.cacheState = com.btelly.feature.player.danmaku.model.DanmakuCacheState.Rendered
        if (old != null && old !== entry) {
            // 旧 entry 引用 -1，归零则排队回收（帧号 0 立即回收，因为这是 cache 线程内部替换）。
            if (old.release()) {
                enqueueRelease(old, releaseAtFrameId = 0)
            }
        }
    }

    /** 清空共享表，释放每项表持有的引用（item 引用不受影响）。 */
    private fun clearSharedCacheStore() {
        if (sharedCacheStore.isEmpty()) return
        val entries = sharedCacheStore.values.toList()
        sharedCacheStore.clear()
        for (entry in entries) {
            if (entry.release()) {
                enqueueRelease(entry, releaseAtFrameId = 0)
            }
        }
    }

    /**
     * 共享缓存内容指纹（FNV-1a 块混，与 akdanmaku sharedCacheKey 同算法）。
     * 必须包含所有影响 bitmap 渲染输出的字段，漏一个会出现"不同弹幕共享同一 bitmap"的视觉错乱。
     */
    private fun sharedCacheKey(
        text: String,
        color: Int,
        textSizePx: Float,
        typefaceOrdinal: Int,
        strokeWidthPx: Float,
        outlinePadPx: Float,
    ): Long {
        var acc = FNV_OFFSET
        acc = mix(acc, text.hashCode().toLong())
        acc = mix(acc, text.length.toLong())
        acc = mix(acc, color.toLong())
        acc = mix(acc, textSizePx.toBits().toLong())
        acc = mix(acc, typefaceOrdinal.toLong())
        acc = mix(acc, strokeWidthPx.toBits().toLong())
        acc = mix(acc, outlinePadPx.toBits().toLong())
        return acc
    }

    private fun mix(acc: Long, value: Long): Long = (acc xor value) * FNV_PRIME

    private data class CacheRequest(
        val item: DanmakuItem,
        val textWidthPx: Float,
        val style: CacheStyle,
        val releaseAtFrameId: Int,
    )

    private data class PendingRelease(
        val entry: SharedCacheEntry,
        val releaseAtFrameId: Int,
    )

    private class BitmapPool(
        private val maxBytes: Long,
        private val maxCount: Int,
    ) {
        private val pool = ArrayDeque<Bitmap>()
        private var pooledBytes: Long = 0L

        @Synchronized
        fun acquire(minWidth: Int, minHeight: Int): Bitmap? {
            if (pool.isEmpty()) return null
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.isRecycled) {
                    it.remove()
                    continue
                }
                if (!isReusable(b, minWidth, minHeight)) continue
                it.remove()
                pooledBytes -= b.allocationByteCount.toLong().coerceAtLeast(0L)
                return b
            }
            return null
        }

        @Synchronized
        fun tryPut(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return true
            val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
            if (bytes <= 0L) return false
            if (bytes > maxBytes) return false
            if (pool.size >= maxCount) return false
            if (pooledBytes + bytes > maxBytes) return false
            pool.addLast(bitmap)
            pooledBytes += bytes
            return true
        }

        @Synchronized
        fun clear() {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                it.remove()
                runCatching { if (!b.isRecycled) b.recycle() }
            }
            pooledBytes = 0L
        }

        @Synchronized
        fun snapshot(): PoolSnapshot =
            PoolSnapshot(
                count = pool.size,
                bytes = pooledBytes,
                maxBytes = maxBytes,
            )

        private fun isReusable(bitmap: Bitmap, minWidth: Int, minHeight: Int): Boolean {
            if (bitmap.config != Bitmap.Config.ARGB_8888) return false
            if (bitmap.width < minWidth || bitmap.height < minHeight) return false
            val dw = bitmap.width - minWidth
            val dh = bitmap.height - minHeight
            return dw <= 48 && dh <= 24
        }
    }

    internal data class PoolSnapshot(
        val count: Int,
        val bytes: Long,
        val maxBytes: Long,
    )

    internal data class StatsSnapshot(
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val sharedHit: Long = 0L,
    )
}
