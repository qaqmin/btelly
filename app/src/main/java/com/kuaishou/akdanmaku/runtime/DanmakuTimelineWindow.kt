/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import kotlin.math.max

private const val TRIM_SAFETY_MARGIN_MS = 2_000L

internal class DanmakuTimelineWindow<T>(
  private val items: MutableList<T>,
  private val comparator: Comparator<T>,
  private val liveHistoryMax: Int,
  private val timeOf: (T) -> Long
) {
  var scanIndex: Int = 0
    private set

  fun clear() {
    items.clear()
    scanIndex = 0
  }

  fun syncPending(
    pending: MutableList<T>,
    liveMode: Boolean,
    nowMs: Long,
    durationMs: Long,
    rollingDurationMs: Long
  ): Int {
    if (pending.isEmpty()) return 0
    val added = pending.size
    val batch = ArrayList(pending)
    pending.clear()
    val canAppendInOrder = canAppendWithoutSort(batch)
    items.addAll(batch)
    if (!canAppendInOrder) {
      items.sortWith(comparator)
    }
    if (liveMode) {
      trimLiveHistory()
    } else {
      // VOD: slide the window so items already flown past won't accumulate.
      trimExpiredByTime(nowMs, durationMs, rollingDurationMs)
    }
    return added
  }

  // Drop items that have permanently flown past the playback window.
  // cutoff = now - max(duration, rolling) - safetyMargin. Only items before
  // scanIndex (already scanned) are eligible, so un-enqueued items are kept.
  private fun trimExpiredByTime(nowMs: Long, durationMs: Long, rollingDurationMs: Long) {
    val span = max(durationMs, rollingDurationMs)
    val cutoff = nowMs - span - TRIM_SAFETY_MARGIN_MS
    var removeCount = lowerBound(cutoff)
    if (removeCount <= 0) return
    // Never trim past scanIndex: those items haven't been scanned yet.
    removeCount = removeCount.coerceAtMost(scanIndex)
    if (removeCount <= 0) return
    items.subList(0, removeCount).clear()
    scanIndex -= removeCount
  }

  fun reset(positionMs: Long, durationMs: Long, rollingDurationMs: Long) {
    scanIndex = lowerBound(positionMs - max(durationMs, rollingDurationMs))
  }

  fun enqueueDue(
    nowMs: Long,
    durationMs: Long,
    rollingDurationMs: Long,
    entryAheadMs: Long,
    enqueueBudget: Int,
    shouldSkipItem: () -> Boolean,
    shouldStopAfterAdded: (Int) -> Boolean,
    onItem: (T) -> Boolean
  ): Int {
    if (items.isEmpty()) return 0
    val windowStart = nowMs - max(durationMs, rollingDurationMs)
    if (scanIndex >= items.size || (items.getOrNull(scanIndex)?.let(timeOf) ?: Long.MAX_VALUE) < windowStart) {
      scanIndex = lowerBound(windowStart)
    }

    val entryEnd = nowMs + entryAheadMs
    var added = 0
    while (scanIndex < items.size && added < enqueueBudget) {
      val item = items[scanIndex]
      val itemTime = timeOf(item)
      if (itemTime > entryEnd) break
      scanIndex++
      if (itemTime < windowStart) continue
      if (shouldSkipItem()) continue
      if (!onItem(item)) continue
      added++
      if (shouldStopAfterAdded(added)) break
    }
    return added
  }

  private fun canAppendWithoutSort(pending: List<T>): Boolean {
    if (pending.isEmpty()) return true
    var previousTime = items.lastOrNull()?.let(timeOf) ?: Long.MIN_VALUE
    for (item in pending) {
      val itemTime = timeOf(item)
      if (itemTime < previousTime) {
        return false
      }
      previousTime = itemTime
    }
    return true
  }

  private fun lowerBound(timeMs: Long): Int {
    var low = 0
    var high = items.size
    while (low < high) {
      val mid = (low + high).ushr(1)
      if (timeOf(items[mid]) < timeMs) {
        low = mid + 1
      } else {
        high = mid
      }
    }
    return low
  }

  private fun trimLiveHistory() {
    if (items.size <= liveHistoryMax) return
    val removeCount = items.size - liveHistoryMax
    items.subList(0, removeCount).clear()
    scanIndex = (scanIndex - removeCount).coerceAtLeast(0)
  }
}
