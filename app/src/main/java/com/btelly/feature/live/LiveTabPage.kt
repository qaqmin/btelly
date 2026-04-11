package com.btelly.feature.live

import com.btelly.core.ui.focus.TabContentFocusTarget

interface LiveTabPage : TabContentFocusTarget {
    fun scrollToTop()

    fun onTabSelected() {}

    fun onExplicitRefresh() {
        onReselected()
    }

    fun onReselected() {}
}
