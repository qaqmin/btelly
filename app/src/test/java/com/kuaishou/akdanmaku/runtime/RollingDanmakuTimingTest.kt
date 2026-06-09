package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingDanmakuTimingTest {

  @Test
  fun predictedStartTime_usesActualEntryTimeWhenOriginalTimeHasPassed() {
    assertEquals(
      2_500L,
      RollingDanmakuTiming.predictedStartTime(
        startTimeMs = ROLLING_START_TIME_UNSET,
        nowMs = 2_500L,
        timePositionMs = 1_000L
      )
    )
  }

  @Test
  fun positionX_startsAtRightEdgeWhenStartTimeEqualsNow() {
    assertEquals(
      1_280f,
      RollingDanmakuTiming.positionX(
        screenWidth = 1_280,
        itemWidth = 320f,
        nowMs = 2_500L,
        startTimeMs = 2_500L,
        durationMs = 8_000L
      ),
      0.001f
    )
  }

  @Test
  fun positionX_usesActualStartTimeWhenMeasureFinishesLate() {
    val screenWidth = 1920
    val itemWidth = 3200f
    val durationMs = 3800L
    val originalTime = 10_000L
    val firstLayoutTime = 10_600L

    val oldPosition = RollingDanmakuTiming.positionX(
      screenWidth = screenWidth,
      itemWidth = itemWidth,
      nowMs = firstLayoutTime,
      startTimeMs = originalTime,
      durationMs = durationMs
    )
    val fixedPosition = RollingDanmakuTiming.positionX(
      screenWidth = screenWidth,
      itemWidth = itemWidth,
      nowMs = firstLayoutTime,
      startTimeMs = RollingDanmakuTiming.predictedStartTime(
        startTimeMs = ROLLING_START_TIME_UNSET,
        nowMs = firstLayoutTime,
        timePositionMs = originalTime
      ),
      durationMs = durationMs
    )

    assertTrue(oldPosition < screenWidth)
    assertEquals(screenWidth.toFloat(), fixedPosition, 0.001f)
  }

  @Test
  fun timeout_usesActualStartTimeForDelayedRollingItems() {
    val durationMs = 3800L
    val startTime = 10_600L

    assertFalse(RollingDanmakuTiming.isTimeout(14_300L, startTime, durationMs))
    assertTrue(RollingDanmakuTiming.isTimeout(14_401L, startTime, durationMs))
  }
}
