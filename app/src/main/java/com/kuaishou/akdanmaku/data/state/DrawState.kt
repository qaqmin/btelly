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

package com.kuaishou.akdanmaku.data.state

import android.graphics.Matrix
import android.graphics.RectF
import com.kuaishou.akdanmaku.cache.DrawingCache

/**
 * 绘图状态
 *
 * Maintained by project contributors.
 * @since 2021-07-16
 */
internal class DrawState : State() {
  private var rectDirty = false
  internal val rect: RectF = RectF()
    get() {
      if (rectDirty) updateRect(field)
      return field
    }

  private var transformDirty = false
  internal val transform: Matrix = Matrix()
    get() {
      if (transformDirty) updateMatrix(field)
      return field
    }

  var layoutGeneration: Int = -1
  var measureGeneration: Int = -1
  var cacheGeneration: Int = -1
  override var generation: Int = -1

  var drawingCache: DrawingCache = DrawingCache.EMPTY_DRAWING_CACHE
  var visibility: Boolean = false
  var alpha: Float = 1f
  private var positionXValue: Float = 0f
  private var positionYValue: Float = 0f

  var positionX: Float
    get() = positionXValue
    set(value) {
      if (positionXValue != value) {
        positionXValue = value
        markDirty()
      }
    }
  var positionY: Float
    get() = positionYValue
    set(value) {
      if (positionYValue != value) {
        positionYValue = value
        markDirty()
      }
    }
  var width: Float = 0f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }
  var height: Float = 0f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }

  var translateX: Float = 0f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }
  var translateY: Float = 0f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }
  var scaleX: Float = 1f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }
  var scaleY: Float = 1f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }
  var rotation: Float = 0f
    set(value) {
      if (field != value) {
        field = value
        markDirty()
      }
    }

  fun isMeasured(measureGeneration: Int): Boolean = width > 0f && height > 0f &&
    this.measureGeneration == measureGeneration

  fun setRuntimePosition(x: Float, y: Float) {
    val changed = positionXValue != x || positionYValue != y
    positionXValue = x
    positionYValue = y
    if (changed) markDirty()
  }

  fun setRuntimePositionX(x: Float) {
    positionXValue = x
  }

  @Suppress("unused")
  fun resetActionProperty() {
    translateX = 0f
    translateY = 0f
    scaleX = 1f
    scaleY = 1f
    rotation = 0f
    alpha = 1f
  }

  override fun reset() {
    super.reset()
    visibility = false
    alpha = 1f
    positionX = 0f
    positionY = 0f
    width = 0f
    height = 0f
    translateX = 0f
    translateY = 0f
    scaleX = 1f
    scaleY = 1f
    rotation = 0f
    rectDirty = false
    transformDirty = false
    rect.setEmpty()
    transform.reset()
    recycle()
  }

  fun recycle() {
    if (drawingCache != DrawingCache.EMPTY_DRAWING_CACHE) {
      drawingCache.decreaseReference()
    }
    drawingCache = DrawingCache.EMPTY_DRAWING_CACHE
    layoutGeneration = -1
    cacheGeneration = -1
    visibility = false
  }

  private fun updateRect(rect: RectF) {
    rectDirty = false
    rect.set(positionXValue, positionYValue, positionXValue + width, positionYValue + height)
  }

  private fun updateMatrix(matrix: Matrix) {
    transformDirty = false
    matrix.reset()
    matrix.setScale(scaleX, scaleY)
    matrix.postRotate(rotation)
    matrix.postTranslate(positionXValue + translateX, positionYValue + translateY)
  }

  private fun markDirty() {
    transformDirty = true
    rectDirty = true
  }

  override fun toString(): String = "DrawState[measure: $measureGeneration, layout: $layoutGeneration]"
}
