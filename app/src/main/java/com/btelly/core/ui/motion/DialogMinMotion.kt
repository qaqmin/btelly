// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import android.app.Dialog
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Min化：对话框呈现动效。
 *
 * ## 为什么不用窗口动画
 *
 * 旧实现走 `window.setWindowAnimations(R.style.WindowAnim_MinSpring)`，即**窗口级动画**：
 * SurfaceFlinger 对整个窗口图层（设置选项弹窗是 800x615，占 1920x1080 的一大块）做缩放。
 * 在 Allwinner 这类弱板电视上，叠加后台视频解码占用的硬件，窗口层缩放基本跑不满帧——
 * 表现就是弹窗弹出时明显卡顿/掉帧。两个因素还会加剧：
 *   1. `setOnShowListener` 里让 RecyclerView 立刻滚动到选中项，与窗口缩放同时进行；
 *   2. overshootInterpolator(tension=2.2) 中间帧更多，掉帧时格外明显。
 *
 * 现在改成**内容视图动画**（`ViewPropertyAnimator`）：硬件加速、跑在 RenderThread、
 * 完全不碰 SurfaceFlinger 的窗口层。这是 Android TV 上的常规做法。
 *
 * ## 用法
 * ```
 * dialog.applyMinWindowAnim()      // setContentView 之前：关掉窗口动画
 * dialog.setContentView(...)
 * dialog.show()
 * dialog.playMinContentEnter()     // show 之后：内容视图入场
 * dismissWithMinAnim()             // 替代 dismiss()，出场也顺滑
 * ```
 *
 * [applyMinWindowAnim] 单独调用也安全：它只负责关掉窗口动画，
 * 所有既有调用点都会立刻摆脱卡顿，只是退化为"直出"。
 */
private const val MIN_DIALOG_ENTER_MS = 180L
private const val MIN_DIALOG_EXIT_MS = 130L

/** 入场：从 94% 缩放 + 透明渐入，减速收尾（fast-out-slow-in 的观感）。 */
fun Dialog.playMinContentEnter() {
    val content = findViewById<View>(android.R.id.content) ?: return
    content.animate().cancel()
    if (!UiMinStyle.isEnabled) {
        content.alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
        return
    }
    content.alpha = 0f
    content.scaleX = 0.94f
    content.scaleY = 0.94f
    content.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(MIN_DIALOG_ENTER_MS)
        .setInterpolator(DecelerateInterpolator(1.4f))
        .start()
}

/**
 * 替代 [Dialog.dismiss]：先播出场动画再真正关闭。
 * 重复调用是安全的（第二次进来时已在关闭中，直接放行）。
 */
fun Dialog.dismissWithMinAnim() {
    if (!UiMinStyle.isEnabled) {
        dismiss()
        return
    }
    val content = findViewById<View>(android.R.id.content)
    if (content == null || !isShowing) {
        dismiss()
        return
    }
    content.animate().cancel()
    content.animate()
        .alpha(0f)
        .scaleX(0.96f)
        .scaleY(0.96f)
        .setDuration(MIN_DIALOG_EXIT_MS)
        .setInterpolator(AccelerateInterpolator(1.2f))
        .withEndAction {
            content.animate().cancel()
            // 防御：动画结束前窗口可能已被外部 dismiss
            if (isShowing) dismiss()
        }
        .start()
}

/**
 * 关闭窗口级动画。必须在 `setContentView` 之前调用。
 *
 * 保留这个函数名是为了不改动既有 20 个调用点；它们现在都会自动获得"无窗口动画"收益。
 * 想要完整顺滑的弹窗，再在 `show()` 后接 [playMinContentEnter]、关闭时用 [dismissWithMinAnim]。
 */
fun Dialog.applyMinWindowAnim() {
    window?.setWindowAnimations(0)
}
