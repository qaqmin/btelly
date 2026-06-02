/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET
import com.kuaishou.akdanmaku.ext.isTimeout
import kotlin.math.abs

/**
 * 滚动弹幕轨道分配器。
 *
 * 运行时保证入场基本按时间顺序，因此每条轨道只检查尾部弹幕即可，避免旧 retainer 在高密度时
 * 对整条轨道做多次碰撞扫描。
 */
internal class RollingTrackAllocator {
  private val rows = ArrayList<Row>(32)
  private val itemToRow = HashMap<Long, Row>(256)
  private var maxBottom = 0

  fun updateExisting(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    config: DanmakuConfig
  ): Boolean {
    if (!refreshMaxBottom(height, config)) return false
    val row = itemToRow[item.data.danmakuId] ?: return false
    updatePosition(item, row, nowMs, width, config)
    return true
  }

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    refreshMaxBottom(height, config)
    val drawState = item.drawState
    val row = itemToRow[item.data.danmakuId] ?: run {
      val newRow = findOrCreateRow(item, nowMs, width, margin, config) ?: return false
      itemToRow[item.data.danmakuId] = newRow
      if (item.rollingStartTimeMs == ROLLING_START_TIME_UNSET) {
        item.rollingStartTimeMs = nowMs.coerceAtLeast(item.timePosition)
      }
      newRow.add(item)
      newRow
    }

    updatePosition(item, row, nowMs, width, config)
    return true
  }

  private fun refreshMaxBottom(height: Int, config: DanmakuConfig): Boolean {
    val nextMaxBottom = (height * config.screenPart).toInt()
    if (maxBottom == nextMaxBottom) return true
    maxBottom = nextMaxBottom
    if (rows.isEmpty() && itemToRow.isEmpty()) return true
    clear()
    return false
  }

  private fun updatePosition(
    item: DanmakuItem,
    row: Row,
    nowMs: Long,
    width: Int,
    config: DanmakuConfig
  ) {
    val drawState = item.drawState
    val startTime = RollingDanmakuTiming.resolvedStartTime(item.rollingStartTimeMs, item.timePosition)
    drawState.positionX = RollingDanmakuTiming.positionX(
      screenWidth = width,
      itemWidth = drawState.width,
      nowMs = nowMs,
      startTimeMs = startTime,
      durationMs = config.rollingDurationMs
    )
    drawState.positionY = row.top.toFloat()
    drawState.visibility = true
    drawState.layoutGeneration = config.layoutGeneration
  }

  fun remove(item: DanmakuItem) {
    val row = itemToRow.remove(item.data.danmakuId) ?: return
    row.remove(item)
  }

  fun clear() {
    rows.clear()
    itemToRow.clear()
  }

  private fun findOrCreateRow(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    margin: Int,
    config: DanmakuConfig
  ): Row? {
    val itemHeight = item.drawState.height.toInt().coerceAtLeast(1)
    for (row in rows) {
      row.dropExpired(nowMs, config.rollingDurationMs)
      if (itemHeight > row.height) continue
      val tail = row.tail
      if (config.allowOverlap || tail == null ||
        !willRollingCollision(tail, item, width, nowMs, config.rollingDurationMs, config.overlapFraction)) {
        return row
      }
    }

    val nextTop = if (rows.isEmpty()) 0 else rows.last().bottom + margin
    if (nextTop + itemHeight > maxBottom) return null
    return Row(nextTop, itemHeight).also { rows.add(it) }
  }

  private class Row(val top: Int, val height: Int) {
    private val items = ArrayDeque<DanmakuItem>()
    val bottom: Int
      get() = top + height
    val tail: DanmakuItem?
      get() = items.lastOrNull()

    fun add(item: DanmakuItem) {
      items.addLast(item)
    }

    fun remove(item: DanmakuItem) {
      items.remove(item)
    }

    fun dropExpired(nowMs: Long, durationMs: Long) {
      while (items.firstOrNull()?.isRollingTimeout(nowMs, durationMs) == true) {
        items.removeFirst()
      }
    }
  }

  private fun willRollingCollision(
    previous: DanmakuItem,
    next: DanmakuItem,
    screenWidth: Int,
    nowMs: Long,
    durationMs: Long,
    overlapFraction: Float
  ): Boolean {
    val previousStartTime = previous.rollingStartTimeForLayout()
    val nextStartTime = next.predictedRollingStartTime(nowMs)
    if (previous.isRollingTimeout(nowMs, durationMs, previousStartTime)) return false
    val dt = nextStartTime - previousStartTime
    if (dt <= 0) return true
    if (abs(dt) >= durationMs || next.isRollingTimeout(nowMs, durationMs, nextStartTime)) return false
    return checkCollisionAt(previous, next, screenWidth, nowMs, durationMs, overlapFraction, previousStartTime, nextStartTime) ||
      checkCollisionAt(previous, next, screenWidth, nowMs + durationMs, durationMs, overlapFraction, previousStartTime, nextStartTime)
  }

  private fun checkCollisionAt(
    previous: DanmakuItem,
    next: DanmakuItem,
    screenWidth: Int,
    atMs: Long,
    durationMs: Long,
    overlapFraction: Float,
    previousStartTime: Long,
    nextStartTime: Long
  ): Boolean {
    val previousWidth = previous.drawState.width
    val nextWidth = next.drawState.width
    val tolerance = minOf(previousWidth, nextWidth) * overlapFraction
    val previousDt = atMs - previousStartTime
    val nextDt = atMs - nextStartTime
    val previousRight = screenWidth - (screenWidth + previousWidth) * (previousDt.toFloat() / durationMs) + previousWidth
    val nextLeft = screenWidth - (screenWidth + nextWidth) * (nextDt.toFloat() / durationMs)
    return nextLeft < previousRight - tolerance
  }

}

private fun DanmakuItem.predictedRollingStartTime(nowMs: Long): Long =
  RollingDanmakuTiming.predictedStartTime(rollingStartTimeMs, nowMs, timePosition)

private fun DanmakuItem.rollingStartTimeForLayout(): Long =
  RollingDanmakuTiming.resolvedStartTime(rollingStartTimeMs, timePosition)

private fun DanmakuItem.isRollingTimeout(nowMs: Long, durationMs: Long): Boolean =
  isRollingTimeout(nowMs, durationMs, rollingStartTimeForLayout())

private fun DanmakuItem.isRollingTimeout(nowMs: Long, durationMs: Long, startTimeMs: Long): Boolean =
  RollingDanmakuTiming.isTimeout(nowMs, startTimeMs, durationMs)

/**
 * 顶部/底部固定弹幕轨道。固定弹幕同轨只需等待上一条超时，不做滚动碰撞。
 */
internal class FixedTrackAllocator(private val fromBottom: Boolean) {
  private val rows = ArrayList<Row>(16)
  private val itemToRow = HashMap<Long, Row>(64)
  private var lastMaxBottom = 0

  fun updateExisting(
    item: DanmakuItem,
    width: Int,
    height: Int,
    config: DanmakuConfig
  ): Boolean {
    if (!refreshMaxBottom(height, config)) return false
    val row = itemToRow[item.data.danmakuId] ?: return false
    updatePosition(item, row, width, config)
    return true
  }

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    val maxBottom = (height * config.screenPart).toInt()
    refreshMaxBottom(height, config)
    val row = itemToRow[item.data.danmakuId] ?: run {
      val newRow = findOrCreateRow(item, nowMs, margin, config, maxBottom) ?: return false
      itemToRow[item.data.danmakuId] = newRow
      newRow.item = item
      newRow
    }
    updatePosition(item, row, width, config)
    return true
  }

  private fun refreshMaxBottom(height: Int, config: DanmakuConfig): Boolean {
    val maxBottom = (height * config.screenPart).toInt()
    if (lastMaxBottom == maxBottom) return true
    lastMaxBottom = maxBottom
    if (rows.isEmpty() && itemToRow.isEmpty()) return true
    clear()
    return false
  }

  private fun updatePosition(
    item: DanmakuItem,
    row: Row,
    width: Int,
    config: DanmakuConfig
  ) {
    val drawState = item.drawState
    drawState.positionX = ((width - drawState.width) * 0.5f).coerceAtLeast(0f)
    drawState.positionY = row.top.toFloat()
    drawState.visibility = true
    drawState.layoutGeneration = config.layoutGeneration
  }

  fun remove(item: DanmakuItem) {
    val row = itemToRow.remove(item.data.danmakuId) ?: return
    if (row.item == item) {
      row.item = null
    }
  }

  fun clear() {
    rows.clear()
    itemToRow.clear()
  }

  private fun findOrCreateRow(
    item: DanmakuItem,
    nowMs: Long,
    margin: Int,
    config: DanmakuConfig,
    maxBottom: Int
  ): Row? {
    val itemHeight = item.drawState.height.toInt().coerceAtLeast(1)
    for (row in rows) {
      val current = row.item
      if (current == null || current.isTimeout(nowMs)) {
        if (itemHeight <= row.height) return row
      }
    }
    val top = if (fromBottom) {
      val previousTop = rows.lastOrNull()?.top ?: maxBottom
      previousTop - itemHeight - margin
    } else {
      val previousBottom = rows.lastOrNull()?.bottom ?: 0
      previousBottom + margin
    }
    if (top < 0 || top + itemHeight > maxBottom) return null
    return Row(top, itemHeight).also { rows.add(it) }
  }

  private class Row(val top: Int, val height: Int) {
    var item: DanmakuItem? = null
    val bottom: Int
      get() = top + height
  }
}
