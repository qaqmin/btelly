package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.ItemState
import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuMeasureSchedulerTest {

  @Test
  fun collectCandidates_prioritizesItemsNearEntry() {
    val late = item(id = 1L, position = 10_900L, content = "late")
    val near = item(id = 2L, position = 10_100L, content = "near")
    val future = item(id = 3L, position = 12_000L, content = "future")

    val result = DanmakuMeasureScheduler.collectCandidates(
      items = listOf(late, near, future),
      nowMs = 10_000L,
      measureGeneration = 0,
      maxCount = 1,
      scheduleAheadMs = 1_000L
    )

    assertEquals(listOf(near), result)
  }

  @Test
  fun collectCandidates_prefersLongerTextWhenEntryDistanceMatches() {
    val short = item(id = 1L, position = 10_100L, content = "short")
    val long = item(id = 2L, position = 10_100L, content = "this is a much longer danmaku")

    val result = DanmakuMeasureScheduler.collectCandidates(
      items = listOf(short, long),
      nowMs = 10_000L,
      measureGeneration = 0,
      maxCount = 1,
      scheduleAheadMs = 1_000L
    )

    assertEquals(listOf(long), result)
  }

  private fun item(id: Long, position: Long, content: String): TestMeasureCandidate =
    TestMeasureCandidate(id, position, content.length)

  private data class TestMeasureCandidate(
    val id: Long,
    override val timePositionMs: Long,
    override val contentLength: Int
  ) : DanmakuMeasureScheduler.MeasureCandidate {
    override val measureState: ItemState = ItemState.Uninitialized

    override fun isMeasured(measureGeneration: Int): Boolean = false
  }
}
