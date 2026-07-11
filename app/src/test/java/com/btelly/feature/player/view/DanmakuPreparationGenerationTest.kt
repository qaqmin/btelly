package com.btelly.feature.player.view

import com.kuaishou.akdanmaku.cache.DanmakuMeasureSizeCache
import com.kuaishou.akdanmaku.cache.commitDanmakuMeasureResultIfCurrent
import com.kuaishou.akdanmaku.cache.isDanmakuCacheBuildCurrent
import com.kuaishou.akdanmaku.cache.shouldCommitDanmakuMeasureResult
import com.kuaishou.akdanmaku.engine.LockWaitHistogram
import com.kuaishou.akdanmaku.utils.Size
import com.btelly.feature.player.mergePublishedDanmakuSnapshot
import com.btelly.feature.player.DanmakuFilterContext
import com.btelly.feature.player.danmakuAvailabilityState
import com.btelly.feature.player.shouldAppendDanmakuUpdate
import com.btelly.feature.player.shouldResetPublishedDanmakuState
import com.btelly.feature.player.danmaku.mergeSortedDanmakuModels
import com.btelly.feature.player.danmaku.canAppendPreparedDanmakuIncrementally
import com.btelly.feature.player.danmaku.canInjectPreparedDanmaku
import com.btelly.feature.player.danmaku.common.DanmakuDuplicateMergePolicy
import com.btelly.feature.player.danmaku.common.nextDanmakuPreparationGeneration
import com.btelly.model.dm.DmModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DanmakuPreparationGenerationTest {

  @Test
  fun replaceStartsANewGeneration() {
    assertEquals(8L, nextDanmakuPreparationGeneration(current = 7L, replace = true))
  }

  @Test
  fun appendKeepsTheCurrentGeneration() {
    assertEquals(7L, nextDanmakuPreparationGeneration(current = 7L, replace = false))
  }

  @Test
  fun appendMergeKeepsBothBatchesInTimelineOrder() {
    val existing = listOf(dm(1, 100), dm(3, 300))
    val incoming = listOf(dm(4, 400), dm(2, 200))

    val merged = mergeSortedDanmakuModels(existing, incoming)

    assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
    assertEquals(listOf(100, 200, 300, 400), merged.map { it.progress })
  }

  @Test
  fun liteAppendOnlyKeepsExistingPreparationWhenTailCannotMergeAcrossBatches() {
    val existing = listOf(dm(1, 1_000, "same"), dm(2, 2_000, "tail"))
    val safeIncoming = listOf(dm(4, 2_500, "different"))

    assertFalse(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(3, 2_500, "same")),
        mergeDuplicate = true
      )
    )
    assertTrue(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = safeIncoming,
        mergeDuplicate = true
      )
    )
    assertTrue(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(5, 4_001, "same")),
        mergeDuplicate = true
      )
    )
    assertFalse(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(6, 1_500, "different")),
        mergeDuplicate = false
      )
    )
    assertEquals(
      DanmakuDuplicateMergePolicy.merge(existing + safeIncoming),
      DanmakuDuplicateMergePolicy.merge(existing) + DanmakuDuplicateMergePolicy.merge(safeIncoming)
    )
  }

  @Test
  fun liteAppendFallsBackWhenFilterContextChanges() {
    val oldContext = DanmakuFilterContext.EMPTY
    val newContext = oldContext.copy(reportFilters = listOf("blocked"))

    assertTrue(canAppendPreparedDanmakuIncrementally(true, oldContext, oldContext))
    assertFalse(canAppendPreparedDanmakuIncrementally(true, oldContext, newContext))
  }

  @Test
  fun stoppedLiteEngineOnlyAcceptsARequestedFullRestore() {
    assertFalse(canInjectPreparedDanmaku(true, false, true))
    assertFalse(canInjectPreparedDanmaku(true, true, false))
    assertTrue(canInjectPreparedDanmaku(true, true, true))
    assertTrue(canInjectPreparedDanmaku(false, false, false))
  }

  @Test
  fun bothEnginesUseTheIndependentMaskHost() {
    val functional = danmakuLayerVisibility(performanceMode = false)
    val performance = danmakuLayerVisibility(performanceMode = true)

    assertTrue(functional.maskHostVisible)
    assertTrue(functional.functionalVisible)
    assertFalse(functional.performanceVisible)
    assertTrue(performance.maskHostVisible)
    assertFalse(performance.functionalVisible)
    assertTrue(performance.performanceVisible)
  }

  @Test
  fun publishedSnapshotSupportsTailAppendAndOutOfOrderMerge() {
    val tailAppended = mergePublishedDanmakuSnapshot(
      existing = listOf(dm(1, 100), dm(2, 200)),
      incoming = listOf(dm(3, 300), dm(4, 400))
    )
    val outOfOrderMerged = mergePublishedDanmakuSnapshot(
      existing = tailAppended,
      incoming = listOf(dm(5, 250))
    )

    assertEquals(listOf(1L, 2L, 3L, 4L), tailAppended.map { it.id })
    assertEquals(listOf(100, 200, 250, 300, 400), outOfOrderMerged.map { it.progress })
  }

  @Test
  fun danmakuAvailabilityUsesAStableMarkerInsteadOfTheTimeline() {
    val first = danmakuAvailabilityState(hasDanmaku = true)
    val second = danmakuAvailabilityState(hasDanmaku = true)

    assertSame(first, second)
    assertEquals(1, first.size)
    assertTrue(danmakuAvailabilityState(hasDanmaku = false).isEmpty())
  }

  @Test
  fun lockWaitHistogramReportsBoundedPercentilesWithoutKeepingSamples() {
    val histogram = LockWaitHistogram(reportEvery = 4L)
    assertNull(histogram.record(10_000L))
    assertNull(histogram.record(60_000L))
    assertNull(histogram.record(600_000L))
    val report = histogram.record(6_000_000L)

    requireNotNull(report)
    assertEquals(4L, report.samples)
    assertEquals(100L, report.p50UpperUs)
    assertEquals(10_000L, report.p95UpperUs)
    assertEquals(10_000L, report.p99UpperUs)
    assertEquals(6_000L, report.maxUs)
    assertNull(histogram.record(10_000L))

    val overflowReport = LockWaitHistogram(reportEvery = 1L).record(100_000_001L)
    requireNotNull(overflowReport)
    assertEquals(100_001L, overflowReport.p95UpperUs)
    assertEquals(100_001L, overflowReport.p99UpperUs)
    assertEquals(100_001L, overflowReport.maxUs)
  }

  @Test
  fun contiguousUpdateUsesIncrementalDelivery() {
    assertTrue(
      shouldAppendDanmakuUpdate(
        previousGeneration = 3L,
        previousSequence = 7L,
        currentGeneration = 3L,
        currentSequence = 8L,
        replace = false
      )
    )
  }

  @Test
  fun missedOrReplacementUpdateUsesSnapshotDelivery() {
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(3L, 7L, 3L, 9L, replace = false)
    )
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(3L, 7L, 3L, 8L, replace = true)
    )
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(null, null, 3L, 8L, replace = false)
    )
  }

  @Test
  fun staleQueuedResetCannotRollBackANewerPublication() {
    assertEquals(
      false,
      shouldResetPublishedDanmakuState(
        queuedGeneration = 4L,
        currentGeneration = 5L,
        publishedGeneration = 5L
      )
    )
    assertTrue(
      shouldResetPublishedDanmakuState(
        queuedGeneration = 5L,
        currentGeneration = 5L,
        publishedGeneration = 4L
      )
    )
  }

  @Test
  fun measureCacheDoesNotReuseSizesAcrossGenerations() {
    val cache = DanmakuMeasureSizeCache()
    val oldSize = Size(100, 20)
    val newSize = Size(200, 40)

    cache.put(danmakuId = 1L, measureGeneration = 4, size = oldSize)
    assertSame(oldSize, cache.get(danmakuId = 1L, measureGeneration = 4))
    assertNull(cache.get(danmakuId = 1L, measureGeneration = 5))
    cache.put(danmakuId = 1L, measureGeneration = 5, size = newSize)
    cache.put(danmakuId = 2L, measureGeneration = 4, size = oldSize)

    assertSame(newSize, cache.get(danmakuId = 1L, measureGeneration = 5))
    assertNull(cache.get(danmakuId = 2L, measureGeneration = 5))
  }

  @Test
  fun staleMeasureTaskCannotCommitAfterGenerationChanges() {
    assertEquals(
      false,
      shouldCommitDanmakuMeasureResult(
        pendingGeneration = 6,
        currentGeneration = 6,
        taskGeneration = 5
      )
    )
    assertTrue(
      shouldCommitDanmakuMeasureResult(
        pendingGeneration = 6,
        currentGeneration = 6,
        taskGeneration = 6
      )
    )
  }

  @Test
  fun measureCommitAndGenerationResetShareOneAtomicBoundary() {
    val lock = Any()
    var pendingGeneration = 5
    var currentGeneration = 5
    val commitEntered = CountDownLatch(1)
    val releaseCommit = CountDownLatch(1)
    val resetStarted = CountDownLatch(1)
    val resetFinished = CountDownLatch(1)

    val commitThread = Thread {
      commitDanmakuMeasureResultIfCurrent(
        lock = lock,
        pendingGeneration = { pendingGeneration },
        currentGeneration = { currentGeneration },
        taskGeneration = 5
      ) {
        commitEntered.countDown()
        releaseCommit.await(2, TimeUnit.SECONDS)
        pendingGeneration = -1
      }
    }
    commitThread.start()
    assertTrue(commitEntered.await(2, TimeUnit.SECONDS))

    val resetThread = Thread {
      resetStarted.countDown()
      synchronized(lock) {
        currentGeneration = 6
        pendingGeneration = -1
      }
      resetFinished.countDown()
    }
    resetThread.start()
    assertTrue(resetStarted.await(2, TimeUnit.SECONDS))
    assertFalse(resetFinished.await(100, TimeUnit.MILLISECONDS))
    releaseCommit.countDown()
    assertTrue(resetFinished.await(2, TimeUnit.SECONDS))
    commitThread.join(2_000)
    resetThread.join(2_000)
    assertEquals(6, currentGeneration)
    assertEquals(-1, pendingGeneration)
  }

  @Test
  fun staleBitmapBuildCannotCommitAfterMeasureOrCacheGenerationChanges() {
    assertTrue(isDanmakuCacheBuildCurrent(6, 8, 8, 6, 8, measuredForTask = true))
    assertFalse(isDanmakuCacheBuildCurrent(7, 8, 8, 6, 8, measuredForTask = true))
    assertFalse(isDanmakuCacheBuildCurrent(6, 9, 8, 6, 8, measuredForTask = true))
    assertFalse(isDanmakuCacheBuildCurrent(6, 8, -1, 6, 8, measuredForTask = true))
  }

  @Test
  fun measureCacheEvictsOldEntriesAtCapacity() {
    val cache = DanmakuMeasureSizeCache(maxEntries = 2)
    cache.put(1L, 1, Size(10, 10))
    cache.put(2L, 1, Size(20, 20))
    cache.put(3L, 1, Size(30, 30))

    assertNull(cache.get(1L, 1))
    assertEquals(20, cache.get(2L, 1)?.width)
    assertEquals(30, cache.get(3L, 1)?.width)
  }

  private fun dm(id: Long, progress: Int, content: String = "dm-$id") =
    DmModel(id = id, progress = progress, content = content)
}
