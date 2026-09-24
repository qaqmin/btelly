// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.tab

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import com.btelly.R
import com.google.android.material.tabs.TabLayout

/**
 * tab chip 化（UI 3.0 统一语言）：首页 / 分类 / 直播 / 搜索 / 我的 共用。
 *
 * 两个要点（真机踩过的坑，改任何一屏都别再绕回去）：
 * 1. 文字色必须代码 [TabLayout.setTabTextColors]：color selector 不支持 `?attr` 主题令牌；
 * 2. chip 背景必须逐个设在 tab view 自己身上：XML 的 `app:tabBackground` 会被 Material 收进
 *    `baseBackgroundDrawable` 由 `TabLayout.onDraw` 手绘，该管线在当贝 H5（Android 12）上
 *    失焦/切换选中后 chip 不刷新（高亮残留、选中不跟随，实锤）；普通 view 背景走平台标准
 *    state 管线，state_selected/state_focused/state_pressed 才可靠。
 *    TabLayout 没有 tabMargin 属性，逐个 tab view 补 marginStart（首项不带）。
 *
 * 调用时机：TabLayoutMediator.attach() 之后（tabCount 才就位）。
 */
fun TabLayout.applyChipTabStyle() {
    setTabTextColors(
        context.resolveThemeAttrColor(R.attr.tvTextSecondary),
        context.resolveThemeAttrColor(R.attr.tvAccent)
    )
    val tabMargin = resources.getDimensionPixelSize(R.dimen.home_tab_margin)
    for (i in 0 until tabCount) {
        val tabView = getTabAt(i)?.view ?: continue
        tabView.setBackgroundResource(R.drawable.bg_chip)
        (tabView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = if (i == 0) 0 else tabMargin
            tabView.layoutParams = lp
        }
    }
}

private fun Context.resolveThemeAttrColor(attr: Int): Int {
    val typed = TypedValue()
    return if (theme.resolveAttribute(attr, typed, true)) typed.data else 0
}
