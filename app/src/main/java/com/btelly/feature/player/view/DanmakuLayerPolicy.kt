package com.btelly.feature.player.view

internal data class DanmakuLayerVisibility(
    val maskHostVisible: Boolean,
    val functionalVisible: Boolean,
    val performanceVisible: Boolean,
)

internal fun danmakuLayerVisibility(performanceMode: Boolean): DanmakuLayerVisibility =
    DanmakuLayerVisibility(
        maskHostVisible = true,
        functionalVisible = !performanceMode,
        performanceVisible = performanceMode,
    )
