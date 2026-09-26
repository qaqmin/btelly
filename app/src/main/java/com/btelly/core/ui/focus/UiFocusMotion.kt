// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.focus

import com.btelly.core.ui.motion.UiMinStyle
import android.view.View
import com.btelly.R
import com.btelly.core.ui.motion.UiMotion

/**
 * 焦点弹簧缩放（「Apple 味」动效 Phase 1）：焦点获得时轻微放大回弹，
 * 失去时减速回落；同时抬起 translationZ，放大的卡片压在邻卡之上（不裁切）。
 *
 * 两种用法（按焦点监听归属选择，避免相互覆盖）：
 * 1. [animateFocusSpring]：纯动画函数。焦点监听归业务方时（设置行、业务
 *    Adapter 的 root 监听），在业务的 focus 回调里第一行调用；
 * 2. [attachFocusSpring]：自带监听。仅用于焦点监听无归属的视图
 *    （导航轨项、tab chip）。业务方后设监听会覆盖它，那些场景勿用。
 *
 * 电视端焦点响应优先于动画：动画异步、不阻塞按键；[com.btelly.core.ui.base.UiCarSpacing]
 * 的间距 boost 与本缩放互不干扰（先缩放后间距）。
 */
fun View.animateFocusSpring(
    hasFocus: Boolean,
    scale: Float = UiMotion.FOCUS_SCALE,
    raiseElevation: Boolean = true
) {
    if (!isFocusable) return
    if (!UiMinStyle.isEnabled) return // Min化 总开关：关闭时零动画直出
    if (hasFocus) {
        animate().cancel()
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(UiMotion.FOCUS_DURATION_MS)
            .setInterpolator(UiMotion.FOCUS_IN_INTERPOLATOR)
            .start()
        if (raiseElevation) {
            translationZ = resources.getDimension(R.dimen.focus_lift_z)
        }
    } else {
        animate().cancel()
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(UiMotion.FOCUS_OUT_DURATION_MS)
            .setInterpolator(UiMotion.FOCUS_OUT_INTERPOLATOR)
            .start()
        if (raiseElevation) {
            translationZ = 0f
        }
    }
}

/** 自带焦点监听的便捷版（仅用于无业务焦点监听的视图）。 */
fun View.attachFocusSpring(scale: Float = UiMotion.FOCUS_SCALE) {
    if (!isFocusable) return
    if (getTag(R.id.tag_focus_spring) != null) return
    setTag(R.id.tag_focus_spring, true)
    setOnFocusChangeListener { v, hasFocus -> v.animateFocusSpring(hasFocus, scale) }
}
