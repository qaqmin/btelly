package com.btelly.feature.player.danmaku

import android.graphics.Typeface

/** 轨道密度 prefValue 字面量（原 上游实现的 AppPrefs 常量，内联避免依赖 AppPrefs）。 */
private const val LANE_DENSITY_SPARSE = "sparse"
private const val LANE_DENSITY_DENSE = "dense"
private const val FONT_WEIGHT_NORMAL = "normal"
private const val FONT_WEIGHT_BOLD = "bold"

enum class DanmakuLaneDensity(
    val prefValue: String,
    val laneHeightFactor: Float,
) {
    Sparse(LANE_DENSITY_SPARSE, 1.25f),
    Standard("standard", 1.0f),
    Dense(LANE_DENSITY_DENSE, 0.85f),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuLaneDensity =
            when (value.trim()) {
                LANE_DENSITY_SPARSE -> Sparse
                LANE_DENSITY_DENSE -> Dense
                else -> Standard
            }
    }
}

enum class DanmakuFontWeight(
    val prefValue: String,
    val typeface: Typeface,
) {
    Normal(FONT_WEIGHT_NORMAL, Typeface.DEFAULT),
    Bold(FONT_WEIGHT_BOLD, Typeface.DEFAULT_BOLD),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuFontWeight =
            when (value.trim()) {
                FONT_WEIGHT_NORMAL -> Normal
                else -> Bold
            }
    }
}

data class DanmakuConfig(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val showHighLikeIcon: Boolean,
)

data class DanmakuSessionSettings(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val followBiliShield: Boolean = true,
    val showHighLikeIcon: Boolean = true,
    val aiShieldEnabled: Boolean = false,
    val aiShieldLevel: Int = 3,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColor: Boolean = true,
    val allowSpecial: Boolean = true,
) {
    fun toConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = enabled,
            opacity = opacity,
            textSizeSp = textSizeSp,
            fontWeight = fontWeight,
            strokeWidthPx = strokeWidthPx,
            speedLevel = speedLevel,
            area = area,
            laneDensity = laneDensity,
            showHighLikeIcon = showHighLikeIcon,
        )
}
