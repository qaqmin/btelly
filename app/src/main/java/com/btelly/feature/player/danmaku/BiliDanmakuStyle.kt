package com.btelly.feature.player.danmaku

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * B 站风格弹幕文字规则：
 * - 协议色 0 归一成白字
 * - 描边按亮度反色
 * - 默认描边档位按 0.09×textSize
 */
internal object BiliDanmakuStyle {

    private val VIP_GRADIENT_BASE_COLORS = intArrayOf(
        Color.parseColor("#FFB7DD"),
        Color.parseColor("#FFD0EF"),
        Color.parseColor("#E8DEFF"),
        Color.parseColor("#C5DEFF"),
        Color.parseColor("#B5F0F2")
    )

    fun normalizeProtocolColor(color: Int): Int =
        if (color == 0) {
            Color.WHITE
        } else {
            color or 0xFF000000.toInt()
        }

    fun resolveStrokeColor(rgb: Int, opacityAlpha: Int): Int {
        val luminance = (0.2126f * Color.red(rgb) +
            0.7152f * Color.green(rgb) +
            0.0722f * Color.blue(rgb)) / 255f
        val baseAlpha = if (luminance > 0.5f) 230 else 210
        val strokeAlpha = ((opacityAlpha * baseAlpha) / 255).coerceIn(0, 255)
        val baseRgb = if (luminance > 0.5f) 0x000000 else 0xFFFFFF
        return (strokeAlpha shl 24) or baseRgb
    }

    fun resolveStrokeWidth(textSizePx: Float, fontBorder: Int): Float =
        when (fontBorder) {
            1 -> (textSizePx * 0.12f).coerceIn(2f, 4f)
            2 -> (textSizePx * 0.09f).coerceIn(1.5f, 3f)
            3 -> 0f
            else -> (textSizePx * 0.09f).coerceIn(1.5f, 3f)
        }

    fun resolveVipStrokeWidth(textSizePx: Float): Float =
        (textSizePx * 0.11f).coerceIn(2.2f, 4.6f)

    fun resolveVipStrokeColor(): Int = Color.argb(210, 255, 224, 244)

    fun resolveVipInnerStrokeColor(): Int = Color.argb(245, 255, 251, 255)

    fun resolveVipGradientColors(textColor: Int): IntArray {
        val resolved = normalizeProtocolColor(textColor)
        if ((resolved and 0x00FFFFFF) == 0x00FFFFFF) {
            return VIP_GRADIENT_BASE_COLORS.copyOf()
        }
        val blend = 0.18f
        return IntArray(VIP_GRADIENT_BASE_COLORS.size) { i ->
            blendColor(VIP_GRADIENT_BASE_COLORS[i], resolved, blend)
        }
    }

    fun useShadowLayer(fontBorder: Int): Boolean = fontBorder == 2

    fun strokeWidthForCache(textSizePx: Float, fontBorder: Int): Int =
        resolveStrokeWidth(textSizePx, fontBorder).roundToInt().coerceAtLeast(0)

    private fun blendColor(start: Int, end: Int, progress: Float): Int {
        val p = progress.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * p).roundToInt().coerceIn(0, 255),
            (Color.red(start) + (Color.red(end) - Color.red(start)) * p).roundToInt().coerceIn(0, 255),
            (Color.green(start) + (Color.green(end) - Color.green(start)) * p).roundToInt().coerceIn(0, 255),
            (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * p).roundToInt().coerceIn(0, 255)
        )
    }
}
