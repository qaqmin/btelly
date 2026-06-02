package com.btelly.feature.player.view

internal object DanmakuWindowRangePolicy {

    data class Range(
        val startIndex: Int,
        val endIndex: Int,
        val naturalEndIndex: Int,
        val windowStartMs: Long,
        val windowEndMs: Long
    ) {
        val isCapped: Boolean
            get() = endIndex < naturalEndIndex
    }

    fun resolve(
        itemCount: Int,
        positionMs: Long,
        behindMs: Long,
        aheadMs: Long,
        maxItems: Int,
        progressAt: (Int) -> Long,
        anchorAtPositionWhenCapped: Boolean = true
    ): Range {
        val windowStart = (positionMs - behindMs).coerceAtLeast(0L)
        val windowEnd = positionMs + aheadMs
        if (itemCount <= 0) {
            return Range(
                startIndex = 0,
                endIndex = 0,
                naturalEndIndex = 0,
                windowStartMs = windowStart,
                windowEndMs = windowEnd
            )
        }

        val naturalStartIndex = lowerBoundProgress(itemCount, windowStart, progressAt)
        val naturalEndIndex = upperBoundProgress(itemCount, windowEnd, progressAt)
        val cappedEndIndex = if (maxItems == Int.MAX_VALUE) {
            naturalEndIndex
        } else {
            val anchoredStartIndex = if (anchorAtPositionWhenCapped) {
                lowerBoundProgress(itemCount, positionMs, progressAt).coerceAtLeast(naturalStartIndex)
            } else {
                naturalStartIndex
            }
            (anchoredStartIndex + maxItems).coerceAtMost(naturalEndIndex)
        }
        val startIndex = if (maxItems == Int.MAX_VALUE || !anchorAtPositionWhenCapped) {
            naturalStartIndex
        } else {
            (cappedEndIndex - maxItems).coerceAtLeast(naturalStartIndex)
        }

        return Range(
            startIndex = startIndex,
            endIndex = cappedEndIndex.coerceAtLeast(startIndex),
            naturalEndIndex = naturalEndIndex,
            windowStartMs = windowStart,
            windowEndMs = windowEnd
        )
    }

    fun shouldReplaceWindow(
        positionMs: Long,
        activeWindowStartMs: Long,
        activeWindowEndMs: Long,
        hasSubmittedWindow: Boolean,
        force: Boolean
    ): Boolean {
        if (force || !hasSubmittedWindow) return true
        if (activeWindowStartMs == Long.MIN_VALUE || activeWindowEndMs == Long.MIN_VALUE) return true
        return positionMs < activeWindowStartMs || positionMs > activeWindowEndMs
    }

    private fun lowerBoundProgress(itemCount: Int, target: Long, progressAt: (Int) -> Long): Int {
        var lo = 0
        var hi = itemCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (progressAt(mid) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBoundProgress(itemCount: Int, target: Long, progressAt: (Int) -> Long): Int {
        var lo = 0
        var hi = itemCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (progressAt(mid) <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
