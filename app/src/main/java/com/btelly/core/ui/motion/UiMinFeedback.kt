package com.btelly.core.ui.motion

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Min化：图标操作弹簧（Apple 味 S-2）。
 *
 * 点赞/收藏/投币被点亮时来一下「快速放大 + 回弹收场」——iOS 点赞的
 * 招牌手感。两段式：80ms 加速放大 1.22 倍，260ms overshoot 回弹落位
 * （压过 1 再回稳，橡皮筋感）。
 *
 * 以当前 scale 为基准放大而非硬设 1f：详情页图标被聚焦时焦点弹簧正
 * 把持着 1.04 倍，硬归一会打断焦点放大；取当前值为基准，弹完正好
 * 落回焦点态。Min化 关闭时直接返回，零动画。
 */
object UiMinFeedback {

    private const val GROW_MS = 80L
    private const val POP_MS = 260L
    private const val POP_SCALE = 1.22f
    private val GROW_INTERPOLATOR = AccelerateInterpolator(1.2f)
    private val POP_INTERPOLATOR = OvershootInterpolator(0.55f)

    /** 给图标来一下弹簧；[delayMs] 用于三连等多图标错峰。 */
    fun pop(view: View, delayMs: Long = 0L) {
        if (!UiMinStyle.isEnabled) return
        val runnable = Runnable {
            val baseX = if (view.scaleX > 0.1f) view.scaleX else 1f
            val baseY = if (view.scaleY > 0.1f) view.scaleY else 1f
            view.animate().cancel()
            view.animate()
                .scaleX(baseX * POP_SCALE)
                .scaleY(baseY * POP_SCALE)
                .setDuration(GROW_MS)
                .setInterpolator(GROW_INTERPOLATOR)
                .withEndAction {
                    view.animate()
                        .scaleX(baseX)
                        .scaleY(baseY)
                        .setDuration(POP_MS)
                        .setInterpolator(POP_INTERPOLATOR)
                        .start()
                }
                .start()
        }
        if (delayMs > 0L) view.postDelayed(runnable, delayMs) else runnable.run()
    }
}
