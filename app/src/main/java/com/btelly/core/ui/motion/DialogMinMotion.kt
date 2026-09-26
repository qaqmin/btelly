// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import android.app.Dialog
import com.btelly.R

/**
 * Min化：对话框弹簧呈现（Apple 味 Phase 2a）。
 *
 * 在 setContentView 之后、show 之前调用：给窗口挂 scale+alpha 弹簧入场/
 * 减速出场。Min化 关闭时清空窗口动画（零动画直出）。
 */
fun Dialog.applyMinWindowAnim() {
    window?.setWindowAnimations(
        if (UiMinStyle.isEnabled) R.style.WindowAnim_MinSpring else 0
    )
}
