package com.btelly.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * VIP 渐变弹幕渲染（移植自 akdanmaku SimpleRenderer，去纹理贴图分支）。
 *
 * 只支持 LinearGradient 渐变路径（4色：白→浅亮→主色→暗尾），
 * 配合双层描边光晕近似 setShadowLayer 的柔和外发光效果。
 *
 * 仅在 CacheManager.buildCache 时调用（烘焙进 bitmap），每帧只 drawBitmap，
 * 稳态零开销。shader/palette 缓存复用避免重复创建。
 */
internal object VipGradientRenderer {

    /** B站协议里 VIP 渐变的 colorful 字段值。 */
    const val COLORFUL_VIP_GRADIENT = 0xEA61

    // 默认 5 色彩虹色板（与 akdanmaku 对齐）
    private val DEFAULT_VIP_GRADIENT_COLORS = intArrayOf(
        Color.parseColor("#FF6AA8"), // 粉
        Color.parseColor("#FFD86E"), // 金
        Color.parseColor("#7EE1C7"), // 青
        Color.parseColor("#86B9FF"), // 蓝
        Color.parseColor("#C18EFF")  // 紫
    )
    private val VIP_TEXT_GRADIENT_POSITIONS = floatArrayOf(0f, 0.38f, 0.7f, 1f)

    // shader 复用缓存：key = "leadingColor_trailingColor_textWidth_textHeight"
    private val shaderCache = HashMap<String, LinearGradient>(32)
    // 调色板复用缓存：key = textColor
    private val paletteCache = HashMap<Int, IntArray>(32)

    /**
     * 绘制 VIP 文字：字芯保持白色，纹理由描边承载。
     *
     * 调用方需提供 [fillPaint]（FILL）和 [strokePaint]（STROKE），绘制完毕后调用方
     * 务必清 shader，避免污染下一条弹幕。
     */
    fun draw(
        canvas: Canvas,
        text: String,
        textColor: Int,
        startX: Float,
        baselineY: Float,
        textSizePx: Float,
        strokeWidthPx: Float,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        if (text.isBlank()) return
        val textWidth = fillPaint.measureText(text).coerceAtLeast(1f)
        val top = baselineY + fillPaint.ascent()
        val bottom = baselineY + fillPaint.descent()
        val textHeight = (bottom - top).coerceAtLeast(1f)

        val palette = resolveVipPalette(textColor)
        val leadingColor = lightenColor(palette.first(), 0.40f)
        val trailingColor = lightenColor(palette.last(), 0.08f)

        // 轻描边，避免把字芯压黑。
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.shader = null
        strokePaint.clearShadowLayer()

        val outerStrokeWidth = BiliDanmakuStyle.resolveVipStrokeWidth(textSizePx)
        val innerStrokeWidth = (outerStrokeWidth * 0.74f).coerceAtLeast(1.0f)

        // 描边 shader（浅粉→浅蓝的柔和过渡，字芯保持白）
        strokePaint.shader = resolveStrokeShader(startX, top, textWidth, textHeight, textColor)

        // 外层淡边
        strokePaint.color = BiliDanmakuStyle.resolveVipStrokeColor()
        strokePaint.strokeWidth = outerStrokeWidth
        canvas.drawText(text, startX, baselineY, strokePaint)
        // 内层提亮
        strokePaint.color = BiliDanmakuStyle.resolveVipInnerStrokeColor()
        strokePaint.strokeWidth = innerStrokeWidth
        canvas.drawText(text, startX, baselineY, strokePaint)

        // 字芯固定白色
        fillPaint.color = Color.WHITE
        canvas.drawText(text, startX, baselineY, fillPaint)

        // 收尾：清 shader 避免污染下一条弹幕
        strokePaint.shader = null
        strokePaint.clearShadowLayer()
    }

    /** 根据 textColor 生成 5 色调色板（默认彩虹色板 × textColor 26% 混合）。 */
    private fun resolveVipPalette(textColor: Int): IntArray {
        val resolved = textColor or Color.argb(255, 0, 0, 0)
        paletteCache[resolved]?.let { return it }
        val result = BiliDanmakuStyle.resolveVipGradientColors(resolved)
        paletteCache[resolved] = result
        return result
    }

    fun resolveStrokeShader(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        textColor: Int
    ): LinearGradient {
        val resolved = normalizeKeyColor(textColor)
        val key = "${resolved}_${left}_${top}_${width}_${height}"
        return shaderCache.getOrPut(key) {
            val palette = resolveVipPalette(resolved)
            val leadingColor = lightenColor(palette.first(), 0.40f)
            val trailingColor = lightenColor(palette.last(), 0.08f)
            LinearGradient(
                left, top,
                left + width, top + height,
                intArrayOf(
                    Color.argb(255, 255, 248, 253),
                    lightenColor(leadingColor, 0.34f),
                    leadingColor,
                    trailingColor
                ),
                VIP_TEXT_GRADIENT_POSITIONS,
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun normalizeKeyColor(color: Int): Int =
        if (color == 0) Color.WHITE else (color or Color.argb(255, 0, 0, 0))

    private fun lightenColor(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt()
        return Color.argb(a, r, g, b)
    }

}
