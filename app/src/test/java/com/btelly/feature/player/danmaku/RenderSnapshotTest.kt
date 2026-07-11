package com.btelly.feature.player.danmaku

import com.btelly.feature.player.danmaku.model.RenderSnapshot
import org.junit.Assert.assertFalse
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
}
