// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 动效令牌（UI 3.0 动效层单一来源）。
 *
 * 约定来自「Apple 味」方向：入场用轻微回弹（overshoot，弹簧感），
 * 退场用减速（decelerate，稳）。倍率与时长集中于此，便于整站调速。
 * 电视端焦点响应优先于动画：所有动画异步、不阻塞按键。
 */
object UiMotion {

    /** 焦点放大倍率（卡片/行/chip 统一）。 */
    const val FOCUS_SCALE = 1.04f

    /** 焦点获得时的轻微回弹（与 MyPlayerView 既有 0.7f 先例一致）。 */
    val FOCUS_IN_INTERPOLATOR = OvershootInterpolator(0.7f)

    /** 焦点失去时的减速回落。 */
    val FOCUS_OUT_INTERPOLATOR = DecelerateInterpolator(1.4f)

    const val FOCUS_DURATION_MS = 220L
    const val FOCUS_OUT_DURATION_MS = 180L

    /** 过场/页面转场时长（后续页面 choreography 用）。 */
    const val TRANSITION_MS = 300L
}
