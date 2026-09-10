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
 * 在此基础上叠加"视频卡片大小"档位偏移（[UiCardSize.offset]），
 * 各列表页（首页、搜索、历史、收藏、动态、直播等）一律走此入口，保证判定一致。
 */
fun Resources.adaptiveSpanCount(base: Int = 4, wide: Int = 8): Int {
    val span = if (isWideScreen()) wide else base
    // 叠加"视频卡片大小"偏移：紧凑 +1 列（更小卡片）、大 -1、特大 -2；任何页面最少保留 2 列
    return (span + UiCardSize.offset()).coerceAtLeast(2)
}
