package com.btelly.feature.player.danmaku

/** Engine-neutral settings consumed by both danmaku implementations. */
data class DanmakuSettingsSnapshot(
    val enabled: Boolean,
    val alpha: Float,
    val textSize: Int,
    val speed: Int,
    val screenArea: Int,
    val allowTop: Boolean,
    val allowBottom: Boolean,
    val smartFilterLevel: Int,
    val mergeDuplicate: Boolean,
    val trackSpacing: String = "standard",
)
