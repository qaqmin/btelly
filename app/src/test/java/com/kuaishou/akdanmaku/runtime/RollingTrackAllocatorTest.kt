package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RollingTrackAllocator 通过 DanmakuItem 驱动，而 DanmakuItem 的静态依赖
 * (DanmakuContext.NONE_CONTEXT → Looper.myLooper) 无法在纯 JVM 单元测试中初始化。
 * 因此这里不构造 DanmakuItem，而是直接验证 allocator 分配决策所依赖的纯函数链：
 * 延迟批量到达的弹幕 → predictedStartTime 统一对齐到 nowMs → willCollide 判定它们同屏互斥。
 * 这正是 RollingTrackAllocator 把同批延迟弹幕分到不同轨道的判据。
 */
class RollingTrackAllocatorTest {

  private val nowMs = 10_000L
  private val durationMs = 4_000L
  private val screenWidth = 1_280
  private val width = 500f

  @Test
  fun delayedBatchAlignsToPredictedStartTime() {
    // 五条弹幕的原始位置都早于 now（9_000~9_004），属于"延迟到达"的批量。
    // 未设置 rollingStartTimeMs 时，预测开始时间统一对齐到 nowMs，而非各自的位置时间。
    val predictedStarts = (0 until 5).map { index ->
      RollingDanmakuTiming.predictedStartTime(
        startTimeMs = ROLLING_START_TIME_UNSET,
        nowMs = nowMs,
        timePositionMs = 9_000L + index
      )
    }
    assertEquals(List(5) { nowMs }, predictedStarts)
  }

  @Test
  fun alignedBatchCollidesSoTracksMustDiffer() {
    val predictedStart = RollingDanmakuTiming.predictedStartTime(
      startTimeMs = ROLLING_START_TIME_UNSET,
      nowMs = nowMs,
      timePositionMs = 9_000L
    )
    assertEquals(nowMs, predictedStart)

    // 同一预测开始时间、同宽度的两条弹幕，必然在屏幕上重叠，
    // allocator 因此必须把它们分到不同轨道（这是原测试 positionY 各异的根因）。
    assertTrue(
      RollingCollision.willCollide(
        previousStartTime = predictedStart,
        previousWidth = width,
        previousMotionWidth = width,
        nextStartTime = predictedStart,
        nextWidth = width,
        nextMotionWidth = width,
        screenWidth = screenWidth,
        nowMs = nowMs,
        durationMs = durationMs,
        overlapFraction = 0f
      )
    )
  }

  @Test
  fun predictedStartNeverPrecedesTimePosition() {
    // 即便 nowMs 早于弹幕自身位置（未来才该出现的弹幕），预测开始时间也不能早于其位置，
    // 否则会被误判为已过期而漏分配。
    val futurePosition = 12_000L
    val predicted = RollingDanmakuTiming.predictedStartTime(
      startTimeMs = ROLLING_START_TIME_UNSET,
      nowMs = nowMs,
      timePositionMs = futurePosition
    )
    assertEquals(futurePosition, predicted)
    assertFalse(
      RollingDanmakuTiming.isTimeout(nowMs = nowMs, startTimeMs = predicted, durationMs = durationMs)
    )
  }

  private companion object {
    // DanmakuItem.ROLLING_START_TIME_UNSET 的值，这里内联为 Long.MIN_VALUE，
    // 与源码保持一致，同时避免在测试中触发 DanmakuItem / DanmakuContext 的类初始化。
    const val ROLLING_START_TIME_UNSET = Long.MIN_VALUE
  }
}
