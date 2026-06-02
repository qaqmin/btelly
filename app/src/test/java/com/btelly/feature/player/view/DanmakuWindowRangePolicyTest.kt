package com.btelly.feature.player.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuWindowRangePolicyTest {

    @Test
    fun resolve_whenCappedAnchorsAtPlaybackPosition() {
        val progresses = List(300) { index -> 49_000L + index * 40L }

        val range = DanmakuWindowRangePolicy.resolve(
            itemCount = progresses.size,
            positionMs = 55_000L,
            behindMs = 6_000L,
            aheadMs = 16_000L,
            maxItems = 96,
            progressAt = progresses::get
        )

        assertEquals(150, range.startIndex)
        assertEquals(246, range.endIndex)
        assertTrue(progresses[range.startIndex] >= 55_000L)
        assertTrue(range.isCapped)
    }

    @Test
    fun resolve_canKeepLegacyWindowStartForAppendRanges() {
        val progresses = List(300) { index -> 49_000L + index * 40L }

        val range = DanmakuWindowRangePolicy.resolve(
            itemCount = progresses.size,
            positionMs = 55_000L,
            behindMs = 6_000L,
            aheadMs = 16_000L,
            maxItems = 96,
            progressAt = progresses::get,
            anchorAtPositionWhenCapped = false
        )

        assertEquals(0, range.startIndex)
        assertEquals(96, range.endIndex)
        assertTrue(progresses[range.endIndex - 1] < 55_000L)
    }

    @Test
    fun forceRefreshAppendRange_startsAtPlaybackPosition() {
        val progresses = List(300) { index -> 49_000L + index * 40L }
        val fullRange = DanmakuWindowRangePolicy.resolve(
            itemCount = progresses.size,
            positionMs = 55_000L,
            behindMs = 6_000L,
            aheadMs = 16_000L,
            maxItems = Int.MAX_VALUE,
            progressAt = progresses::get,
            anchorAtPositionWhenCapped = false
        )

        val appendStartIndex = lowerBound(progresses, 55_000L)
            .coerceIn(fullRange.startIndex, fullRange.naturalEndIndex)

        assertEquals(0, fullRange.startIndex)
        assertEquals(150, appendStartIndex)
        assertTrue(progresses[appendStartIndex] >= 55_000L)
    }

    @Test
    fun shouldReplaceWindow_whenPositionLeavesActiveWindow() {
        assertTrue(
            DanmakuWindowRangePolicy.shouldReplaceWindow(
                positionMs = 65_045L,
                activeWindowStartMs = 0L,
                activeWindowEndMs = 21_403L,
                hasSubmittedWindow = true,
                force = false
            )
        )
    }

    private fun lowerBound(values: List<Long>, target: Long): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
