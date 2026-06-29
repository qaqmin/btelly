/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("SpellCheckingInspection")

package com.kuaishou.akdanmaku.data

import android.graphics.RectF
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.data.DanmakuItemData.Companion.DANMAKU_ITEM_DATA_EMPTY
import com.kuaishou.akdanmaku.data.state.DrawState
import com.kuaishou.akdanmaku.engine.DanmakuContext.Companion.NONE_CONTEXT
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ui.DanmakuPlayer

/**
 * 弹幕 Item，包括弹幕数据，以及其他绘制、排版等所需要的状态集合
 *
 * @author Xana
 * @since 2021-06-29
 */
open class DanmakuItem(var data: DanmakuItemData, player: DanmakuPlayer? = null) : Comparable<DanmakuItem> {

  var state = ItemState.Uninitialized
  var duration: Long = 0

  internal var timer = player?.engine?.timer ?: NONE_CONTEXT.timer

  internal val drawState = DrawState()
  internal var shownGeneration = -1
  internal var rollingStartTimeMs = ROLLING_START_TIME_UNSET
  internal var rollingMotionWidth = 0f
  internal var filterGeneration = -1
  internal var filteredInGeneration = false
  internal var pendingMeasureGeneration = -1
  internal var pendingCacheGeneration = -1
  internal var drawingIntoCache = false


  val rect: RectF
    get() = drawState.rect

  val timePosition: Long
    get() = data.position

  val isLate: Boolean
    get() = timePosition > timer.currentTimeMs
  val isTimeout: Boolean
    get() = timePosition < timer.currentTimeMs + duration
  @Suppress("unused")
  val isOutside: Boolean
    get() = isLate || isTimeout

  fun reset() {
    Log.d(DanmakuEngine.TAG, "[Item] Reset $this")
    state = ItemState.Uninitialized
    rect.setEmpty()
    drawState.reset()
    rollingStartTimeMs = ROLLING_START_TIME_UNSET
    rollingMotionWidth = 0f
    filterGeneration = -1
    filteredInGeneration = false
    pendingMeasureGeneration = -1
    pendingCacheGeneration = -1
    drawingIntoCache = false
  }

  override fun compareTo(other: DanmakuItem): Int {
    return data.compareTo(other.data)
  }

  fun recycle() {
    data = DANMAKU_ITEM_DATA_EMPTY
    timer = NONE_CONTEXT.timer
    reset()
  }

  fun cacheRecycle() {
    drawState.recycle()
    pendingCacheGeneration = -1
    if (state > ItemState.Measured) {
      state = ItemState.Measured
    }
  }

  companion object {
    internal const val ROLLING_START_TIME_UNSET = Long.MIN_VALUE
    @Suppress("unused")
    val DANMAKU_ITEM_EMPTY = DanmakuItem(DANMAKU_ITEM_DATA_EMPTY)
  }
}
