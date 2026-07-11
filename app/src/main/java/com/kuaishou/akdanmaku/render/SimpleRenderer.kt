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

package com.kuaishou.akdanmaku.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.math.MathUtils.clamp
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import com.btelly.feature.player.danmaku.common.BiliDanmakuStyle
import com.btelly.feature.player.danmaku.common.VipDanmakuTextureCache
import java.util.HashMap
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/**
 * 一个默认的，实现了简单只绘制文字和描边的弹幕渲染器
 *
 * @author Xana
 */
open class SimpleRenderer : DanmakuRenderer {

  private val textPaint = TextPaint().apply {
    color = Color.WHITE
    style = Paint.Style.FILL
    isAntiAlias = true
  }
  private val strokePaint = TextPaint().apply {
    textSize = textPaint.textSize
    color = Color.BLACK
    strokeWidth = 2f
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
    isAntiAlias = true
  }
  private val debugPaint by lazy {
    Paint().apply {
      color = Color.RED
      style = Paint.Style.STROKE
      isAntiAlias = true
      strokeWidth = 6f
    }
  }
  private val borderPaint = Paint().apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    isAntiAlias = true
    strokeWidth = 6f
  }
  private val shaderMatrix = Matrix()

  override fun updatePaint(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    val danmakuItemData = item.data
    // update textPaint
    val textSize = clamp(danmakuItemData.textSize.toFloat(), 12f, 25f) * (displayer.density - 0.6f)
    textPaint.shader = null
    textPaint.clearShadowLayer()
    textPaint.color = danmakuItemData.textColor or Color.argb(255, 0, 0, 0)
    textPaint.textSize = textSize * config.textSizeScale
    textPaint.typeface = if (config.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    textPaint.style = Paint.Style.FILL
    // update strokePaint
    strokePaint.shader = null
    strokePaint.clearShadowLayer()
    strokePaint.textSize = textPaint.textSize
    strokePaint.typeface = textPaint.typeface
    strokePaint.color = BiliDanmakuStyle.resolveStrokeColor(textPaint.color, 255)
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeWidth = BiliDanmakuStyle.resolveStrokeWidth(
      textSizePx = textPaint.textSize,
      fontBorder = config.fontBorder
    )
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
  }

  override fun measure(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Size {
    updatePaint(item, displayer, config)
    val danmakuItemData = item.data
    val key = measureCacheKey(
      content = danmakuItemData.content,
      textSize = textPaint.textSize,
      bold = config.bold,
      fontBorder = config.fontBorder
    )
    synchronized(measureCache) {
      measureCache[key]?.let { return it }
    }
    val textWidth = textPaint.measureText(danmakuItemData.content)
    val textHeight = getCacheHeight(textPaint)
    val size = Size(textWidth.roundToInt() + CANVAS_PADDING, textHeight.roundToInt() + CANVAS_PADDING)
    synchronized(measureCache) {
      measureCache[key] = size
    }
    return size
  }

  override fun draw(
    item: DanmakuItem,
    canvas: Canvas,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    updatePaint(item, displayer, config)
    val alphaScale = if (item.drawingIntoCache) {
      255
    } else {
      (config.alpha * 255).roundToInt().coerceIn(0, 255)
    }
    textPaint.alpha = alphaScale
    strokePaint.alpha = alphaScale
    val danmakuItemData = item.data
    val x = CANVAS_PADDING * 0.5f
    val y = CANVAS_PADDING * 0.5f - textPaint.ascent()
    if ((danmakuItemData.renderFlags and DanmakuItemData.RENDER_FLAG_VIP_GRADIENT) != 0) {
      drawVipGradientText(
        canvas = canvas,
        danmakuItemData = danmakuItemData,
        startX = x,
        baselineY = y,
        borderMode = config.fontBorder,
        opacityAlpha = alphaScale
      )
    } else {
      drawTextWithBorder(canvas, danmakuItemData.content, x, y, config.fontBorder)
    }
    textPaint.shader = null
    textPaint.clearShadowLayer()
    strokePaint.clearShadowLayer()
    if (danmakuItemData.danmakuStyle == DanmakuItemData.DANMAKU_STYLE_SELF_SEND) {
      canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), borderPaint)
    }
  }

  private fun drawVipGradientText(
    canvas: Canvas,
    danmakuItemData: DanmakuItemData,
    startX: Float,
    baselineY: Float,
    borderMode: Int,
    opacityAlpha: Int
  ) {
    val text = danmakuItemData.content
    if (text.isBlank()) {
      return
    }
    val textWidth = textPaint.measureText(text).coerceAtLeast(1f)
    val top = baselineY + textPaint.ascent()
    val bottom = baselineY + textPaint.descent()
    val textHeight = (bottom - top).coerceAtLeast(1f)
    val strokeBitmap = VipDanmakuTextureCache.getBitmap(danmakuItemData.vipGradientStyle.strokeTextureUrl)
    val fillBitmap = VipDanmakuTextureCache.getBitmap(danmakuItemData.vipGradientStyle.fillTextureUrl)
    // 严格复刻 B 站：贴图都没有时不做渐变/纯色兜底，按普通白字描边渲染。
    if (strokeBitmap == null && fillBitmap == null) {
      textPaint.color = (opacityAlpha shl 24) or 0x00FFFFFF
      strokePaint.color = BiliDanmakuStyle.resolveStrokeColor(Color.WHITE, opacityAlpha)
      drawTextWithBorder(canvas, text, startX, baselineY, borderMode)
      return
    }
    drawVipTextureText(
      canvas = canvas,
      text = text,
      startX = startX,
      baselineY = baselineY,
      top = top,
      textWidth = textWidth,
      textHeight = textHeight,
      strokeBitmap = strokeBitmap,
      fillBitmap = fillBitmap,
      opacityAlpha = opacityAlpha,
    )
  }

  private fun drawVipTextureText(
    canvas: Canvas,
    text: String,
    startX: Float,
    baselineY: Float,
    top: Float,
    textWidth: Float,
    textHeight: Float,
    strokeBitmap: Bitmap?,
    fillBitmap: Bitmap?,
    opacityAlpha: Int,
  ) {
    // 描边色与填充色完全由 colorful_src 贴图决定（BitmapShader 铺到文字形状上）。
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
    if (strokeBitmap != null) {
      strokePaint.strokeWidth = BiliDanmakuStyle.resolveVipStrokeWidth(textSizePx = textPaint.textSize)
      strokePaint.shader = strokeBitmap.createTextShader(startX, top, textWidth, textHeight)
      strokePaint.color = (opacityAlpha shl 24) or 0x00FFFFFF
      strokePaint.alpha = opacityAlpha
    }

    textPaint.style = Paint.Style.FILL
    textPaint.color = (opacityAlpha shl 24) or 0x00FFFFFF
    textPaint.shader = fillBitmap?.createTextShader(startX, top, textWidth, textHeight)

    if (strokeBitmap != null && strokePaint.strokeWidth > 0.01f) {
      canvas.drawText(text, startX, baselineY, strokePaint)
    }
    canvas.drawText(text, startX, baselineY, textPaint)

    strokePaint.shader = null
    textPaint.shader = null
  }

  private fun Bitmap.createTextShader(
    left: Float,
    top: Float,
    width: Float,
    height: Float
  ): BitmapShader {
    return BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shader ->
      shaderMatrix.reset()
      shaderMatrix.setScale(
        width / this.width.coerceAtLeast(1).toFloat(),
        height / this.height.coerceAtLeast(1).toFloat()
      )
      shaderMatrix.postTranslate(left, top)
      shader.setLocalMatrix(shaderMatrix)
    }
  }

  companion object {
    private const val CANVAS_PADDING: Int = 6

    private val sTextHeightCache: MutableMap<Float, Float> = HashMap()
    private val measureCache = object : LinkedHashMap<Long, Size>(512, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Size>?): Boolean {
        return size > MEASURE_CACHE_MAX
      }
    }

    private fun getCacheHeight(paint: Paint): Float {
      val textSize = paint.textSize
      return sTextHeightCache[textSize] ?: let {
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading
        sTextHeightCache[textSize] = textHeight
        textHeight
      }
    }

    private fun measureCacheKey(
      content: String,
      textSize: Float,
      bold: Boolean,
      fontBorder: Int
    ): Long {
      var acc = 1469598103934665603L
      acc = (acc xor content.hashCode().toLong()) * 1099511628211L
      acc = (acc xor textSize.toBits().toLong()) * 1099511628211L
      acc = (acc xor if (bold) 1L else 0L) * 1099511628211L
      acc = (acc xor fontBorder.toLong()) * 1099511628211L
      return acc
    }

    private const val MEASURE_CACHE_MAX = 2048
    private const val SHADOW_RADIUS = 2.5f
    private const val SHADOW_DX = 1f
    private const val SHADOW_DY = 1f
  }

  private fun drawTextWithBorder(
    canvas: Canvas,
    text: String,
    startX: Float,
    baselineY: Float,
    borderMode: Int
  ) {
    when (borderMode) {
      DanmakuConfig.FONT_BORDER_NONE -> canvas.drawText(text, startX, baselineY, textPaint)
      DanmakuConfig.FONT_BORDER_SHADOW -> {
        textPaint.setShadowLayer(SHADOW_RADIUS, SHADOW_DX, SHADOW_DY, Color.BLACK)
        canvas.drawText(text, startX, baselineY, textPaint)
        textPaint.clearShadowLayer()
      }
      else -> {
        strokePaint.strokeWidth = BiliDanmakuStyle.resolveStrokeWidth(
          textSizePx = textPaint.textSize,
          fontBorder = borderMode
        )
        canvas.drawText(text, startX, baselineY, strokePaint)
        canvas.drawText(text, startX, baselineY, textPaint)
      }
    }
  }
}
