// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R

/**
 * Min化：列表 stagger 进场（Apple 味 Phase 2b）。
 *
 * 在设置 adapter 之前调用：列表首次布局时子项按 12% 递延迟错峰浮入
 * （上移 14% + 渐显）。Min化 关闭时不挂 layoutAnimation（零动画直出）。
 *
 * 注意：layoutAnimation 只在 setAdapter/首次布局时播放；增量 submitList
 * 不重放（避免滚动时每批都闪）。
 */
fun RecyclerView.installMinStagger() {
    layoutAnimation = if (UiMinStyle.isEnabled) {
        AnimationUtils.loadLayoutAnimation(context, R.anim.min_stagger)
    } else {
        null
    }
}
