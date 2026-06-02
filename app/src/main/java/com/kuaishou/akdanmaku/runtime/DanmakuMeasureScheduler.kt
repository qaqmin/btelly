package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.ItemState

internal object DanmakuMeasureScheduler {

  fun <T : MeasureCandidate> collectCandidates(
    items: List<T>,
    nowMs: Long,
    measureGeneration: Int,
    maxCount: Int,
    scheduleAheadMs: Long
  ): List<T> {
    if (items.isEmpty() || maxCount <= 0) return emptyList()
    val deadlineMs = nowMs + scheduleAheadMs.coerceAtLeast(0L)
    val candidates = ArrayList<T>(minOf(items.size, maxCount * 2))
    for (item in items) {
      if (item.measureState == ItemState.Measuring) continue
      if (item.isMeasured(measureGeneration) && item.measureState >= ItemState.Measured) continue
      if (item.timePositionMs > deadlineMs) continue
      candidates.add(item)
    }
    if (candidates.size <= maxCount) return candidates
    candidates.sortWith { left, right ->
      compareMeasurePriority(left, right, nowMs)
    }
    return candidates.subList(0, maxCount)
  }

  private fun compareMeasurePriority(left: MeasureCandidate, right: MeasureCandidate, nowMs: Long): Int {
    val leftDistance = (left.timePositionMs - nowMs).coerceAtLeast(0L)
    val rightDistance = (right.timePositionMs - nowMs).coerceAtLeast(0L)
    if (leftDistance != rightDistance) {
      return leftDistance.compareTo(rightDistance)
    }
    val leftLength = left.contentLength
    val rightLength = right.contentLength
    if (leftLength != rightLength) {
      return rightLength.compareTo(leftLength)
    }
    return left.timePositionMs.compareTo(right.timePositionMs)
  }

  interface MeasureCandidate {
    val timePositionMs: Long
    val contentLength: Int
    val measureState: ItemState
    fun isMeasured(measureGeneration: Int): Boolean
  }
}
