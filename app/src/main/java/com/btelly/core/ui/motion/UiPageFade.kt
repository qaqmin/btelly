// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * Min化：ViewPager2 切页淡入（Apple 味 Phase 2a）。
 *
 * tab 切换/滑动时新页面从半透明渐显——比生硬跳切更顺。Min化 关闭时移除
 * transformer（恢复系统默认无渐变）。
 */
object UiPageFade {

    fun install(viewPager: ViewPager2) {
        viewPager.setPageTransformer { page, position ->
            if (!UiMinStyle.isEnabled) {
                page.alpha = 1f
                return@setPageTransformer
            }
            page.alpha = 1f - minOf(1f, kotlin.math.abs(position)) * 0.45f
        }
    }

    fun clear(viewPager: ViewPager2) {
        viewPager.setPageTransformer(null)
    }
}
