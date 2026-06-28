package com.btelly.feature.player.danmaku

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
    /** VIP 渐变弹幕标记（colorful == 0xEA61 且 allowVipColorful 开启时为 true）。 */
    val vipGradient: Boolean = false,
)
