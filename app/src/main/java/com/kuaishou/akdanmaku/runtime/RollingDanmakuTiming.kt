package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET

internal object RollingDanmakuTiming {

  // 热路径：避免 takeIf 的内联函数开销（会生成额外分支与临时装箱），直接 if 判断。
  fun resolvedStartTime(startTimeMs: Long, timePositionMs: Long): Long =
    if (startTimeMs != ROLLING_START_TIME_UNSET) startTimeMs else timePositionMs

  fun predictedStartTime(startTimeMs: Long, nowMs: Long, timePositionMs: Long): Long =
    if (startTimeMs != ROLLING_START_TIME_UNSET) startTimeMs else nowMs.coerceAtLeast(timePositionMs)

  fun positionX(
    screenWidth: Int,
    itemWidth: Float,
    nowMs: Long,
    startTimeMs: Long,
    durationMs: Long
  ): Float {
    val deltaTime = (nowMs - startTimeMs).toFloat()
    return screenWidth - (deltaTime / durationMs) * (screenWidth + itemWidth)
  }

  fun isTimeout(nowMs: Long, startTimeMs: Long, durationMs: Long): Boolean =
    nowMs - startTimeMs > durationMs
}
