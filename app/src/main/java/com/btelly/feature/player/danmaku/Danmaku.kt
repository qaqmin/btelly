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
)

/** 高赞弹幕标记（attr bit2）。当前 stub 版本不渲染高赞图标，仅保留判定。 */
val Danmaku.isHighLiked: Boolean
    get() = (attr and (1 shl 2)) != 0
