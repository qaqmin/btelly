package com.btelly.feature.player.danmaku

import com.btelly.feature.player.danmaku.model.DanmakuItem
import com.btelly.feature.player.danmaku.model.DanmakuKind
import com.btelly.feature.player.danmaku.model.RenderSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSnapshotTest {

    @Test
    fun writerCannotReuseSnapshotWhileMainThreadHoldsReadLease() {
        val snapshot = RenderSnapshot()

        assertTrue(snapshot.tryAcquireRead())
        assertFalse(snapshot.tryBeginWrite())

        snapshot.releaseRead()
        assertTrue(snapshot.tryBeginWrite())
        snapshot.endWrite()
    }

    @Test
    fun readerCannotObserveSnapshotDuringPublication() {
        val snapshot = RenderSnapshot()

        assertTrue(snapshot.tryBeginWrite())
        assertFalse(snapshot.tryAcquireRead())

        snapshot.endWrite()
        assertTrue(snapshot.tryAcquireRead())
        snapshot.releaseRead()
    }

    @Test
    fun cacheResultOnlyCommitsToCurrentActiveRenderingItem() {
        assertTrue(shouldApplyDmCacheResult(3, 3, 3, rendering = true, active = true))
        assertFalse(shouldApplyDmCacheResult(2, 3, 2, rendering = true, active = true))
        assertFalse(shouldApplyDmCacheResult(3, 3, 2, rendering = true, active = true))
        assertFalse(shouldApplyDmCacheResult(3, 3, 3, rendering = false, active = true))
        assertFalse(shouldApplyDmCacheResult(3, 3, 3, rendering = true, active = false))
    }

    @Test
    fun firstCacheResultStartsMotionAtReadyTime() {
        assertEquals(1_240, cacheReadyStartTime(false, currentStartTimeMs = 1_000, nowMs = 1_240))
    }

    @Test
    fun styleCacheRebuildDoesNotRestartExistingMotion() {
        assertEquals(1_000, cacheReadyStartTime(true, currentStartTimeMs = 1_000, nowMs = 1_240))
    }

    @Test
    fun itemWaitingForCacheEventuallyReleasesItsLane() {
        assertFalse(isCacheWaitExpired(false, admittedAtMs = 1_000, nowMs = 2_599, timeoutMs = 1_600))
        assertTrue(isCacheWaitExpired(false, admittedAtMs = 1_000, nowMs = 2_600, timeoutMs = 1_600))
        assertFalse(isCacheWaitExpired(true, admittedAtMs = 1_000, nowMs = 9_000, timeoutMs = 1_600))
    }

    @Test
    fun trimmingConsumedLiveHistoryOnlyAdjustsTimelineIndex() {
        assertEquals(1_900, adjustedTimelineIndexAfterPrefixTrim(index = 2_000, droppedCount = 100))
        assertEquals(0, adjustedTimelineIndexAfterPrefixTrim(index = 40, droppedCount = 100))
    }

    @Test
    fun fixedDanmakusAreWrittenAfterRollingDanmakus() {
        val top = item(DanmakuKind.TOP, "top")
        val rolling = item(DanmakuKind.SCROLL, "rolling")
        val bottom = item(DanmakuKind.BOTTOM, "bottom")
        val laterRolling = item(DanmakuKind.SCROLL, "later rolling")
        val snapshot = RenderSnapshot()

        writeDanmakuRenderOrder(listOf(top, rolling, bottom, laterRolling), snapshot)

        assertEquals(
            listOf("rolling", "later rolling", "top", "bottom"),
            (0 until snapshot.count).map { snapshot.items[it]?.data?.text },
        )
    }

    private fun item(kind: DanmakuKind, text: String): DanmakuItem =
        DanmakuItem(
            Danmaku(
                timeMs = 0,
                mode = 1,
                text = text,
                color = 0xFFFFFF,
                fontSize = 25,
                weight = 0,
            )
        ).also { it.kind = kind }
}
