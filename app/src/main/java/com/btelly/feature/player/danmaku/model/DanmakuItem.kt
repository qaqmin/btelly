package com.btelly.feature.player.danmaku.model

import com.btelly.feature.player.danmaku.Danmaku

internal enum class DanmakuKind {
    SCROLL,
    TOP,
    BOTTOM,
}

internal enum class DanmakuCacheState {
    Init,
    Rendering,
    Rendered,
}

internal class DanmakuItem(
    val data: Danmaku,
) {
    // ---- Measure/cache (updated by cache thread) ----
    @Volatile var measuredWidthPx: Float = Float.NaN
    @Volatile var measureGeneration: Int = -1

    @Volatile var cacheEntry: SharedCacheEntry? = null
    @Volatile var cacheGeneration: Int = -1
    @Volatile var pendingCacheGeneration: Int = -1
    @Volatile var cacheState: DanmakuCacheState = DanmakuCacheState.Init

    // ---- Active state (action thread only) ----
    var kind: DanmakuKind = DanmakuKind.SCROLL
    var lane: Int = 0
    /**
     * 是否当前在场（action 线程私有）。activate() 置 true，所有从 active 列表移除的路径置 false。
     * 替代 O(n) 的 `item in active` 线性扫描（applyCacheResult/rebuildScene 每帧/每条都会查）。
     */
    var inActive: Boolean = false
    @Volatile var startTimeMs: Int = 0
    @Volatile var motionStarted: Boolean = false
    var durationMs: Int = 0
    var pxPerMs: Float = 0f
    var textWidthPx: Float = 0f
    var layoutTopPx: Float = 0f
    /**
     * 时间线整体替换（setDanmakus）时，若新实例与"当前仍在场的条目"内容相同（同发送时间+同内容），
     * 标记 consumed 使 rebuildScene 不再把它重新入场——否则同一条弹幕会滚两遍。
     * 用户回看（位置倒退）时忽略该标记，允许重新入场。仅替换路径设置，普通入场不置位。
     */
    var consumed: Boolean = false

    fun timeMs(): Int = data.timeMs
}
