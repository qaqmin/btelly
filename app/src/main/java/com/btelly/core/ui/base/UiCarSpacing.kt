// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.base

import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * 车机模式间距铺开（与 [ScaledTextView] 的文字 boost 同一机制、同一刷新时机）。
 *
 * 在 inflate 后对目标子树调用一次 [apply]：
 * - 所有视图：padding 四边与 margin 四边乘 [UiDisplayMode.boost]；
 * - 容器（ViewGroup）：固定 layoutParams.height 与 minHeight 同乘（触控目标增高，行车点按更稳）。
 *
 * 叶子图标/文本的固定宽高不动（文字放大由 ScaledTextView 负责，图标尺寸保持设计基准）。
 * 电视模式零扫描零改动；设置切换显示模式后 recreate 自然重新生效。
 */
object UiCarSpacing {

    fun apply(view: View) {
        if (!UiDisplayMode.isCarMode) return
        scaleSubtree(view, UiDisplayMode.boost)
    }

    private fun scaleSubtree(view: View, boost: Float) {
        if (boost == 1f) return
        scalePadding(view, boost)
        scaleMargin(view, boost)
        if (view is ViewGroup) {
            scaleFixedHeight(view, boost)
            for (i in 0 until view.childCount) {
                scaleSubtree(view.getChildAt(i), boost)
            }
        }
    }

    private fun scalePadding(view: View, boost: Float) {
        val start = (view.paddingStart * boost).roundToInt()
        val top = (view.paddingTop * boost).roundToInt()
        val end = (view.paddingEnd * boost).roundToInt()
        val bottom = (view.paddingBottom * boost).roundToInt()
        if (start != view.paddingStart || top != view.paddingTop ||
            end != view.paddingEnd || bottom != view.paddingBottom
        ) {
            view.setPadding(start, top, end, bottom)
        }
    }

    private fun scaleMargin(view: View, boost: Float) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val start = (lp.marginStart * boost).roundToInt()
        val top = (lp.topMargin * boost).roundToInt()
        val end = (lp.marginEnd * boost).roundToInt()
        val bottom = (lp.bottomMargin * boost).roundToInt()
        if (start != lp.marginStart || top != lp.topMargin ||
            end != lp.marginEnd || bottom != lp.bottomMargin
        ) {
            lp.setMargins(start, top, end, bottom)
            view.layoutParams = lp
        }
    }

    /** 只放大固定像素高度（>0）；wrap/match/0dp（约束）不动。 */
    private fun scaleFixedHeight(view: ViewGroup, boost: Float) {
        val lp = view.layoutParams ?: return
        if (lp.height > 0) {
            lp.height = (lp.height * boost).roundToInt()
            view.layoutParams = lp
        }
        val minH = view.minHeight
        if (minH > 0) {
            view.minimumHeight = (minH * boost).roundToInt()
        }
    }
}
