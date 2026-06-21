package com.btelly.feature.player.danmaku

import android.graphics.Color

/**
 * 弹幕引擎内部数据模型（迁移自 上游弹幕库的 Danmaku 数据模型）。
 *
 * 与 [com.btelly.model.dm.DmModel] 的桥接见 [toDanmaku] 扩展函数。
 */
data class Danmaku(
    val timeMs: Int,
    val mode: Int,
    val text: String,
    val color: Int,
    val fontSize: Int,
    val weight: Int,
    val midHash: String? = null,
    val dmid: Long? = null,
    val attr: Int = 0,
)

/** 高赞弹幕标记（attr bit2）。当前 stub 版本不渲染高赞图标，仅保留判定。 */
val Danmaku.isHighLiked: Boolean
    get() = (attr and (1 shl 2)) != 0

/**
 * 计算弹幕描边颜色（对齐 AkDanmaku SimpleRenderer.resolveStandardStrokeColor）。
 *
 * 亮字(luminance>0.5，如白色/黄色)→黑色描边(看清边缘)；
 * 暗字(红/蓝/绿等)→白色描边(在深色画面上才看得清)。
 *
 * 弹幕引擎原版固定用黑描边，深色弹幕在深色视频上看不清，这里改回老版本逻辑。
 *
 * @param rgb 文字颜色（仅 RGB，不含 alpha）
 * @param opacityAlpha 整体不透明度 0-255（来自 DanmakuConfig.opacity）
 */
fun resolveStandardStrokeColor(rgb: Int, opacityAlpha: Int): Int {
    val luminance = (0.2126f * Color.red(rgb) +
        0.7152f * Color.green(rgb) +
        0.0722f * Color.blue(rgb)) / 255f
    val baseAlpha = if (luminance > 0.5f) 230 else 210
    val strokeAlpha = ((opacityAlpha * baseAlpha) / 255).coerceIn(0, 255)
    val baseRgb = if (luminance > 0.5f) 0x000000 else 0xFFFFFF
    return (strokeAlpha shl 24) or baseRgb
}
