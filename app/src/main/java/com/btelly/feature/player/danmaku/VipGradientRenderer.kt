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
     * 绘制 VIP 渐变文字。填充用 LinearGradient，描边用双层光晕近似。
     *
     * 调用方需提供 [fillPaint]（FILL）和 [strokePaint]（STROKE），绘制完毕后调用方
     * 务必清 shader（fillPaint.shader = null），避免污染下一条弹幕。
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
        val leadingColor = lightenColor(palette.first(), 0.35f)
        val trailingColor = darkenColor(palette.last(), 0.05f)

        // 描边光晕（双层近似 setShadowLayer）
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.shader = null
        strokePaint.clearShadowLayer()

        val outerStrokeWidth = (textSizePx * 0.22f).coerceAtLeast(3f)
        val innerStrokeWidth = (textSizePx * 0.14f).coerceAtLeast(2f)

        // 填充 shader（4色对角线渐变，左上白色高光→右下暗尾）
        val shaderKey = "${leadingColor}_${trailingColor}_${textWidth}_${textHeight}"
        fillPaint.shader = shaderCache.getOrPut(shaderKey) {
            LinearGradient(
                startX, top,
                startX + textWidth, bottom,
                intArrayOf(
                    Color.WHITE,
                    lightenColor(leadingColor, 0.55f),
                    leadingColor,
                    trailingColor
                ),
                VIP_TEXT_GRADIENT_POSITIONS,
                Shader.TileMode.CLAMP
            )
        }

        // 外发光层：宽且半透明
        strokePaint.color = withAlpha(darkenColor(trailingColor, 0.45f), 96)
        strokePaint.strokeWidth = outerStrokeWidth
        canvas.drawText(text, startX, baselineY, strokePaint)
        // 主描边层：实色
        strokePaint.color = withAlpha(darkenColor(leadingColor, 0.55f), 220)
        strokePaint.strokeWidth = innerStrokeWidth
        canvas.drawText(text, startX, baselineY, strokePaint)

        // 填充
        canvas.drawText(text, startX, baselineY, fillPaint)

        // 收尾：清 shader 避免污染下一条弹幕
        fillPaint.shader = null
        strokePaint.clearShadowLayer()
    }

    /** 根据 textColor 生成 5 色调色板（默认彩虹色板 × textColor 26% 混合）。 */
    private fun resolveVipPalette(textColor: Int): IntArray {
        val resolved = textColor or Color.argb(255, 0, 0, 0)
        if ((resolved and 0x00FFFFFF) == 0x00FFFFFF) {
            return DEFAULT_VIP_GRADIENT_COLORS // 白色直接用默认色板
        }
        paletteCache[resolved]?.let { return it }
        val result = IntArray(DEFAULT_VIP_GRADIENT_COLORS.size) { i ->
            blendColor(DEFAULT_VIP_GRADIENT_COLORS[i], resolved, 0.26f)
        }
        paletteCache[resolved] = result
        return result
    }

    private fun lightenColor(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun darkenColor(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val factor = (1f - amount).coerceAtLeast(0f)
        return Color.argb(
            a,
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    private fun blendColor(start: Int, end: Int, progress: Float): Int {
        val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * progress).toInt()
        val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * progress).toInt()
        val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * progress).toInt()
        val a = (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * progress).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}
