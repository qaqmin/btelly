/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
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

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    if (maxBottom != (height * config.screenPart).toInt()) {
      maxBottom = (height * config.screenPart).toInt()
      clear()
    }

    val drawState = item.drawState
    val row = itemToRow[item.data.danmakuId] ?: run {
      val newRow = findOrCreateRow(item, nowMs, width, margin, config) ?: return false
      itemToRow[item.data.danmakuId] = newRow
      newRow.add(item)
      newRow
    }

    val deltaTime = (nowMs - item.timePosition).toFloat()
    drawState.positionX = width - (deltaTime / config.rollingDurationMs) * (width + drawState.width)
    drawState.positionY = row.top.toFloat()
    drawState.visibility = true
    drawState.layoutGeneration = config.layoutGeneration
    return true
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
      row.dropExpired(nowMs)
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

    fun dropExpired(nowMs: Long) {
      while (items.firstOrNull()?.isTimeout(nowMs) == true) {
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
    if (previous.isTimeout(nowMs)) return false
    val dt = next.timePosition - previous.timePosition
    if (dt <= 0) return true
    if (abs(dt) >= durationMs || next.isTimeout(nowMs)) return false
    return checkCollisionAt(previous, next, screenWidth, nowMs, durationMs, overlapFraction) ||
      checkCollisionAt(previous, next, screenWidth, nowMs + durationMs, durationMs, overlapFraction)
  }

  private fun checkCollisionAt(
    previous: DanmakuItem,
    next: DanmakuItem,
    screenWidth: Int,
    atMs: Long,
    durationMs: Long,
    overlapFraction: Float
  ): Boolean {
    val previousWidth = previous.drawState.width
    val nextWidth = next.drawState.width
    val tolerance = minOf(previousWidth, nextWidth) * overlapFraction
    val previousDt = atMs - previous.timePosition
    val nextDt = atMs - next.timePosition
    val previousRight = screenWidth - (screenWidth + previousWidth) * (previousDt.toFloat() / durationMs) + previousWidth
    val nextLeft = screenWidth - (screenWidth + nextWidth) * (nextDt.toFloat() / durationMs)
    return nextLeft < previousRight - tolerance
  }
}

/**
 * 顶部/底部固定弹幕轨道。固定弹幕同轨只需等待上一条超时，不做滚动碰撞。
 */
internal class FixedTrackAllocator(private val fromBottom: Boolean) {
  private val rows = ArrayList<Row>(16)
  private val itemToRow = HashMap<Long, Row>(64)
  private var lastMaxBottom = 0

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    val maxBottom = (height * config.screenPart).toInt()
    if (lastMaxBottom != maxBottom) {
      lastMaxBottom = maxBottom
      clear()
    }
    val row = itemToRow[item.data.danmakuId] ?: run {
      val newRow = findOrCreateRow(item, nowMs, margin, config, maxBottom) ?: return false
      itemToRow[item.data.danmakuId] = newRow
      newRow.item = item
      newRow
    }
    val drawState = item.drawState
    drawState.positionX = ((width - drawState.width) * 0.5f).coerceAtLeast(0f)
    drawState.positionY = row.top.toFloat()
    drawState.visibility = true
    drawState.layoutGeneration = config.layoutGeneration
    return true
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
