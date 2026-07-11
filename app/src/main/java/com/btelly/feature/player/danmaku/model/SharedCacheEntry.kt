package com.btelly.feature.player.danmaku.model

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 共享缓存条目：相同内容（text+color+textSize+stroke+typeface）的弹幕共享同一个 bitmap。
 *
 * 引用计数管理生命周期：
 * - 构建后入共享表时 acquire（表持有一份）
 * - item 命中复用时 acquire（每个 item 持有一份）
 * - item 退场/失效时 release；共享表淘汰时 release
 * - refCount 归零时 bitmap 可安全回收
 *
 * 只在 CacheManager 的 HandlerThread（构建/查询/淘汰）和 release 队列排空时访问，
 * 但 refCount 用 AtomicInteger 保证跨线程可见性（item 在 action 线程读写引用）。
 */
internal class SharedCacheEntry(val bitmap: Bitmap) {
    private val refCount = AtomicInteger(0)

    /** 引用计数 +1（item 命中或共享表持有时调用）。 */
    fun acquire() {
        refCount.incrementAndGet()
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = refCount.get()
            if (current <= 0 || bitmap.isRecycled) return false
            if (refCount.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * 引用计数 -1，返回 true 表示归零，调用方应回收 bitmap。
     * 返回 false 表示仍有引用持有，bitmap 必须保持存活。
     */
    fun release(): Boolean = refCount.decrementAndGet() <= 0

    val isRecycled: Boolean get() = bitmap.isRecycled
}
