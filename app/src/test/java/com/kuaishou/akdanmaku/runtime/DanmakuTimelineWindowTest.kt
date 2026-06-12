package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuTimelineWindowTest {

  private val comparator = Comparator<TestItem> { a, b -> a.positionMs.compareTo(b.positionMs) }

  @Test
  fun syncPendingSortsOutOfOrderItems() {
    val items = ArrayList<TestItem>()
    val pending = arrayListOf(item(3, 300), item(1, 100), item(2, 200))
    val window = window(items, liveHistoryMax = 10)

    assertEquals(3, window.syncPending(pending, liveMode = false))

    assertEquals(emptyList<TestItem>(), pending)
    assertEquals(listOf(100L, 200L, 300L), items.map { it.positionMs })
  }

  @Test
  fun resetSkipsExpiredHistoryBeforeEnqueue() {
    val items = arrayListOf(item(1, 1_000), item(2, 2_000), item(3, 3_000))
    val window = window(items, liveHistoryMax = 10)
    val enqueued = ArrayList<Long>()

    window.reset(positionMs = 3_000, durationMs = 500, rollingDurationMs = 500)
    window.enqueueDue(
      nowMs = 3_000,
      durationMs = 500,
      rollingDurationMs = 500,
      entryAheadMs = 0,
      enqueueBudget = 10,
      shouldSkipItem = { false },
      shouldStopAfterAdded = { false }
    ) { item ->
      enqueued.add(item.positionMs)
      true
    }

    assertEquals(listOf(3_000L), enqueued)
  }

  @Test
  fun enqueueDueStopsAfterBudgetCallback() {
    val items = arrayListOf(item(1, 1_000), item(2, 1_001), item(3, 1_002))
    val window = window(items, liveHistoryMax = 10)
    val enqueued = ArrayList<Long>()

    window.enqueueDue(
      nowMs = 1_002,
      durationMs = 1_000,
      rollingDurationMs = 1_000,
      entryAheadMs = 0,
      enqueueBudget = 10,
      shouldSkipItem = { false },
      shouldStopAfterAdded = { added -> added >= 2 }
    ) { item ->
      enqueued.add(item.positionMs)
      true
    }

    assertEquals(listOf(1_000L, 1_001L), enqueued)
  }

  @Test
  fun liveModeTrimsHistoryAndKeepsScanIndexValid() {
    val items = ArrayList<TestItem>()
    val pending = arrayListOf(item(1, 1_000), item(2, 2_000), item(3, 3_000))
    val window = window(items, liveHistoryMax = 2)

    window.syncPending(pending, liveMode = true)

    assertEquals(listOf(2_000L, 3_000L), items.map { it.positionMs })
    assertEquals(0, window.scanIndex)
  }

  @Test
  fun liveModeTrimsLargeHistoryInOneBatchAndKeepsScanIndexValid() {
    val items = ArrayList<TestItem>()
    val window = window(items, liveHistoryMax = 2_000)
    val firstBatch = ArrayList<TestItem>(2_000)
    repeat(2_000) { index ->
      firstBatch.add(item(index.toLong(), index.toLong()))
    }
    window.syncPending(firstBatch, liveMode = true)
    window.reset(positionMs = 1_500, durationMs = 0, rollingDurationMs = 0)

    val secondBatch = ArrayList<TestItem>(2_000)
    repeat(2_000) { offset ->
      val index = 2_000 + offset
      secondBatch.add(item(index.toLong(), index.toLong()))
    }

    window.syncPending(secondBatch, liveMode = true)

    assertEquals(2_000, items.size)
    assertEquals(2_000L, items.first().positionMs)
    assertEquals(3_999L, items.last().positionMs)
    assertEquals(0, window.scanIndex)
  }

  private fun window(items: MutableList<TestItem>, liveHistoryMax: Int): DanmakuTimelineWindow<TestItem> =
    DanmakuTimelineWindow(items, comparator, liveHistoryMax) { it.positionMs }

  private fun item(id: Long, positionMs: Long): TestItem = TestItem(id, positionMs)

  private data class TestItem(
    val id: Long,
    val positionMs: Long
  )
}
