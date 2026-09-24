// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.theme

import android.content.Context
import android.util.TypedValue

/**
 * 代码侧取主题令牌色（P2 旧色清理通用）。
 *
 * 背景：UI 3.0 前程序化视图直接用 `R.color.pink/textColor` 等固定色（B站粉/死白），
 * 不随七套皮肤与深浅色切换，是“别人家”的视觉残留；一律改走 `?attr/tv*` 令牌。
 * XML 里直接用 `?attr/xxx` 即可，本扩展供代码侧（setTextColor/ getColor 等处）使用。
 */
fun Context.resolveThemeColor(attr: Int): Int {
    val typed = TypedValue()
    return if (theme.resolveAttribute(attr, typed, true)) typed.data else 0
}
