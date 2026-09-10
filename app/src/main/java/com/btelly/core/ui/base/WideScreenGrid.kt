package com.btelly.core.ui.base

import android.content.res.Resources

/** 超宽屏判定：宽高比 ≥ 2.8（如 5120×1600）。 */
fun Resources.isWideScreen(): Boolean {
    val metrics = displayMetrics
    return metrics.widthPixels >= metrics.heightPixels * 2.8f
}

/**
 * 超宽屏网格列数适配。
 *
 * 超宽屏沿用为常规比例设计的列数会让封面过大，统一放宽到 [wide] 列；
 * 常规比例屏幕保持 [base] 列。
 * 各列表页（首页、搜索、历史、收藏、动态、直播等）一律走此入口，保证判定一致。
 */
fun Resources.adaptiveSpanCount(base: Int = 4, wide: Int = 8): Int =
    if (isWideScreen()) wide else base
