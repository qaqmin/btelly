package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET

internal object RollingDanmakuTiming {

  fun resolvedStartTime(startTimeMs: Long, timePositionMs: Long): Long =
    startTimeMs.takeIf { it != ROLLING_START_TIME_UNSET } ?: timePositionMs

  fun predictedStartTime(startTimeMs: Long, nowMs: Long, timePositionMs: Long): Long =
    startTimeMs.takeIf { it != ROLLING_START_TIME_UNSET } ?: nowMs.coerceAtLeast(timePositionMs)

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
