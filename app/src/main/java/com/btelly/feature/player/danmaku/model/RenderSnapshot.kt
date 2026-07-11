package com.btelly.feature.player.danmaku.model

import java.util.concurrent.atomic.AtomicInteger

internal class RenderSnapshot(
    var positionMs: Long = 0L,
) {
    private val accessState = AtomicInteger(AVAILABLE)

    var items: Array<DanmakuItem?> = emptyArray()
        private set

    var x: FloatArray = FloatArray(0)
        private set

    var yTop: FloatArray = FloatArray(0)
        private set

    var textWidth: FloatArray = FloatArray(0)
        private set

    var count: Int = 0
    var pendingCount: Int = 0
    var nextAtMs: Int? = null

    fun tryAcquireRead(): Boolean {
        while (true) {
            val current = accessState.get()
            if (current < 0) return false
            if (accessState.compareAndSet(current, current + 1)) return true
        }
    }

    fun releaseRead() {
        check(accessState.decrementAndGet() >= 0) { "RenderSnapshot read lease underflow" }
    }

    fun tryBeginWrite(): Boolean = accessState.compareAndSet(AVAILABLE, WRITING)

    fun endWrite() {
        check(accessState.compareAndSet(WRITING, AVAILABLE)) { "RenderSnapshot is not write-locked" }
    }

    fun ensureCapacity(required: Int) {
        if (required <= items.size) return
        val cap = required.coerceAtLeast(items.size * 2 + 8)
        items = arrayOfNulls(cap)
        x = FloatArray(cap)
        yTop = FloatArray(cap)
        textWidth = FloatArray(cap)
    }

    fun clear() {
        for (i in 0 until count) {
            items[i] = null
        }
        count = 0
        pendingCount = 0
        nextAtMs = null
        positionMs = 0L
    }

    companion object {
        private const val AVAILABLE = 0
        private const val WRITING = -1
        val EMPTY = RenderSnapshot()
    }
}

internal data class RenderSnapshotStats(
    val positionMs: Long,
    val count: Int,
    val pendingCount: Int,
    val nextAtMs: Int?,
)

