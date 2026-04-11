package com.btelly.feature.home

import com.btelly.core.ui.focus.TabContentFocusTarget

interface HomeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
}
