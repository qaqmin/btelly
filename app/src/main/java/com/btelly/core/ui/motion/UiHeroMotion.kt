package com.btelly.core.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import com.btelly.R
import com.btelly.core.common.log.AppLog
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Hero 转场（动效 Phase 3）：点卡片打开视频详情页时，封面从卡片原位置飞到
 * 详情页头图位置；按返回时反向飞回卡片。整条链路受 [UiMinStyle] 开关控制，
 * 关闭时所有入口直接返回，不碰任何 fragment 事务与视图层级。
 *
 * 为什么不用系统共享元素：详情页是 MainActivity 容器里的 fragment 事务（旧页
 * 随即 hide），共享元素要求退出页在动画期间保持可见，与现有事务模型冲突；
 * 且详情数据是网络异步的，头图出现时机不可控。自绘覆盖层的做法是把封面画下来
 * 沿起止矩形插值，等头图布局完成再起飞，目标迟迟不来就撤，天然容忍异步。
 *
 * 调用时序：
 * 1. 卡片点击 → [beginForward]（fragment 事务之前：事务会清掉可见页焦点）；
 * 2. 详情头图布局完成 → [notifyHeadCover] 给出落点，飞 [DURATION_MS]；
 * 3. 返回 → [beginReverse]（pop 之前把头图钉进覆盖层）+ pop，
 *    列表页回到前台后 [onReturnDestinationReady] 触发回程。
 */
object UiHeroMotion {

    private const val TAG = "UiHero"

    /** 去程/回程飞行时长，与 UiMotion 转场令牌对齐。 */
    const val DURATION_MS = UiMotion.TRANSITION_MS

    private const val TARGET_WAIT_TIMEOUT_MS = 2500L
    private const val RETURN_ARM_TIMEOUT_MS = 800L
    private const val FADE_IN_MS = 80L
    private const val FADE_OUT_MS = 120L
    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private val FLIGHT_INTERPOLATOR: Interpolator = DecelerateInterpolator(1.6f)

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class Stage {
        /** 已出发，在等详情头图给出落点 */
        FORWARD_WAIT,

        /** 去程飞行中 */
        FORWARD_FLY,

        /** 去程已落地（或超时撤下），记录在案，可随时回程 */
        SETTLED,

        /** 回程已挂起（pop 之前钉住头图），在等列表页回到前台 */
        RETURN_ARMED,

        /** 回程飞行中 */
        RETURN_FLY,
    }

    private class HeroRun(val host: WeakReference<Activity>) {
        /** 卡片封面 view（回程落点，弱引用，列表页 recycle 后自动失效） */
        var sourceView: WeakReference<View>? = null

        /** 捕获时的屏幕矩形，view 失效时的兜底落点 */
        val sourceRect = RectF()
        var targetView: WeakReference<View>? = null
        var overlay: HeroOverlay? = null
        var animator: ValueAnimator? = null
        var stage = Stage.FORWARD_WAIT
    }

    private var run: HeroRun? = null

    // ---------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------

    /**
     * 打开详情页之前调用（必须在 fragment 事务之前：事务会清掉可见页焦点）。
     * 把当前焦点卡片封面画进覆盖层并停在原位。没有可用封面时返回 false，
     * 事务与平时完全一样。
     */
    fun beginForward(activity: Activity, sourceCover: View?): Boolean {
        if (!UiMinStyle.isEnabled) return false
        val cover = sourceCover as? ImageView ?: return false
        val drawable = cover.drawable ?: return false
        if (cover.width <= 0 || cover.height <= 0) return false
        val content = activity.contentRoot() ?: return false
        val existing = run
        if (existing != null && existing.stage != Stage.SETTLED) return false
        existing?.let { cancelRun(it) }
        val sourceRect = cover.visualRectInScreen(RectF())
        val overlay = HeroOverlay(activity, FADE_OUT_MS).apply {
            bindCover(drawable)
            setFrameScreen(sourceRect)
            cornerRadius = activity.resources.getDimensionPixelSize(R.dimen.card_radius).toFloat()
        }
        content.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        val newRun = HeroRun(WeakReference(activity))
        newRun.sourceView = WeakReference(cover)
        newRun.sourceRect.set(sourceRect)
        newRun.overlay = overlay
        newRun.stage = Stage.FORWARD_WAIT
        run = newRun
        mainHandler.postDelayed(forwardWaitTimeout, TARGET_WAIT_TIMEOUT_MS)
        AppLog.d(TAG, "beginForward: rect=$sourceRect radius=${overlay.cornerRadius}")
        return true
    }

    /**
     * 详情页头图布局完成时调用（每次头图 bind 都会走到，未挂起时是空操作）。
     * 头图还没上过屏则等下一次 pre-draw，直到 [TARGET_WAIT_TIMEOUT_MS] 兜底。
     */
    fun notifyHeadCover(headCover: View) {
        val active = run ?: return
        if (active.stage != Stage.FORWARD_WAIT) return
        if (!headCover.isAttachedToWindow || headCover.width <= 0 || headCover.height <= 0) {
            headCover.doOnPreDraw { notifyHeadCover(headCover) }
            return
        }
        val host = active.host.get() ?: return cancelForward()
        val overlay = active.overlay ?: return cancelForward()
        mainHandler.removeCallbacks(forwardWaitTimeout)
        active.targetView = WeakReference(headCover)
        val start = overlay.currentFrameScreen(RectF())
        val end = headCover.visualRectInScreen(RectF())
        val radiusStart = overlay.cornerRadius
        val radiusEnd = host.resources.getDimensionPixelSize(R.dimen.px15).toFloat()
        active.stage = Stage.FORWARD_FLY
        active.animator = flightAnimator(overlay, start, end, radiusStart, radiusEnd) {
            overlay.detachSelf(animated = false)
            active.stage = Stage.SETTLED
        }.also { it.start() }
        AppLog.d(TAG, "notifyHeadCover: start=$start end=$end")
    }

    /**
     * 返回键 pop 之前调用：把头图（或飞行中的覆盖层当前帧）钉在覆盖层里。
     * 返回 false 表示没有可回程的 hero（或弹出页不是持有头图的那个 fragment、
     * 或飞行已结束、头图已销毁），调用方按原逻辑 pop。
     */
    fun beginReverse(activity: Activity): Boolean {
        if (!UiMinStyle.isEnabled) return false
        val active = run ?: return false
        if (activity !== active.host.get()) return false
        if (active.stage != Stage.FORWARD_WAIT &&
            active.stage != Stage.FORWARD_FLY &&
            active.stage != Stage.SETTLED
        ) {
            return false
        }
        // 即将弹出的必须是持有 hero 目标（详情头图）的那个 fragment：
        // 从详情页又进了用户空间等子页时按返回，pop 的是子页，不能把详情页
        // 的封面偷走。头图还没 bind（去程等待期）时无从校验，交给极短的时序窗口。
        val target = active.targetView?.get()
        if (target != null && !target.isDescendantOf(activity.poppedFragmentView())) {
            AppLog.d(TAG, "beginReverse: target not in popped fragment, skip")
            return false
        }
        val content = activity.contentRoot() ?: return false
        val live = active.overlay
        val start = RectF()
        var drawable: Drawable? = null
        var radius = 0f
        if (live != null) {
            start.set(live.currentFrameScreen(RectF()))
            drawable = live.coverDrawable
            radius = live.cornerRadius
        } else {
            if (target == null || !target.isAttachedToWindow || target.width <= 0) {
                AppLog.d(TAG, "beginReverse: target gone, skip")
                cancelRun(active)
                return false
            }
            start.set(target.visualRectInScreen(RectF()))
            drawable = (target as? ImageView)?.drawable
            radius = activity.resources.getDimensionPixelSize(R.dimen.px15).toFloat()
        }
        if (drawable == null) {
            AppLog.d(TAG, "beginReverse: drawable gone, skip")
            cancelRun(active)
            return false
        }
        active.animator?.cancel()
        val overlay = live ?: HeroOverlay(activity, FADE_OUT_MS).also { fresh ->
            fresh.alpha = 0f
            content.addView(fresh, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            fresh.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
        overlay.bindCover(drawable)
        overlay.setFrameScreen(start)
        overlay.cornerRadius = radius
        active.overlay = overlay
        active.stage = Stage.RETURN_ARMED
        mainHandler.postDelayed(returnArmTimeout, RETURN_ARM_TIMEOUT_MS)
        AppLog.d(TAG, "beginReverse: armed at $start")
        return true
    }

    /**
     * pop 之后（返回栈监听里）调用：等列表页完成一次布局，再把封面飞回卡片原位。
     * 卡片 view 已被 recycle 时退回捕获时的坐标；连坐标都不可用就淡出。
     */
    fun onReturnDestinationReady() {
        val active = run ?: return
        if (active.stage != Stage.RETURN_ARMED) return
        val overlay = active.overlay ?: return cancelRun(active)
        if (!overlay.isAttachedToWindow) return cancelRun(active)
        val host = active.host.get() ?: return cancelRun(active)
        mainHandler.removeCallbacks(returnArmTimeout)
        overlay.doOnPreDraw {
            flyBack(active, host, overlay)
        }
    }

    /** 被全屏页面（播放器等）盖住：撤掉覆盖层，保留回程记录。 */
    fun onActivityPaused(activity: Activity) {
        val active = run ?: return
        if (activity !== active.host.get()) return
        if (active.stage == Stage.SETTLED) return
        mainHandler.removeCallbacks(forwardWaitTimeout)
        mainHandler.removeCallbacks(returnArmTimeout)
        active.animator?.cancel()
        active.overlay?.detachSelf(animated = true)
        active.overlay = null
        active.stage = if (active.targetView?.get()?.isAttachedToWindow == true) {
            Stage.SETTLED
        } else {
            cancelRun(active)
            return
        }
    }

    // ---------------------------------------------------------------
    // 源定位
    // ---------------------------------------------------------------

    /**
     * 从当前焦点出发找卡片封面：卡片根（click_view）下的 imageView。
     * 焦点落在对话框/侧栏等没有卡片封面的地方时返回 null，调用方自动跳过 hero。
     */
    fun resolveCoverFromFocus(activity: Activity): View? {
        val focus = activity.currentFocus ?: return null
        var node: View? = focus
        var depth = 0
        while (node != null && depth < 4) {
            if (node is ViewGroup) {
                findCoverIn(node, 0)?.let { return it }
            }
            node = node.parent as? View
            depth++
        }
        return null
    }

    private fun findCoverIn(group: ViewGroup, depth: Int): View? {
        if (depth > 6) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.id == R.id.imageView) return child
            if (child is ViewGroup) {
                findCoverIn(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    // ---------------------------------------------------------------
    // 飞行
    // ---------------------------------------------------------------

    private fun flightAnimator(
        overlay: HeroOverlay,
        start: RectF,
        end: RectF,
        radiusStart: Float,
        radiusEnd: Float,
        onEnd: () -> Unit
    ): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = FLIGHT_INTERPOLATOR
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                overlay.frame.set(
                    lerp(start.left, end.left, t),
                    lerp(start.top, end.top, t),
                    lerp(start.right, end.right, t),
                    lerp(start.bottom, end.bottom, t)
                )
                overlay.cornerRadius = radiusStart + (radiusEnd - radiusStart) * t
                overlay.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
        }
    }

    private fun flyBack(active: HeroRun, host: Activity, overlay: HeroOverlay) {
        val start = overlay.currentFrameScreen(RectF())
        val source = active.sourceView?.get()
        val end = if (source != null && source.isAttachedToWindow && source.width > 0) {
            source.visualRectInScreen(RectF())
        } else {
            RectF(active.sourceRect)
        }
        val radiusStart = overlay.cornerRadius
        val radiusEnd = host.resources.getDimensionPixelSize(R.dimen.card_radius).toFloat()
        active.stage = Stage.RETURN_FLY
        active.animator = flightAnimator(overlay, start, end, radiusStart, radiusEnd) {
            overlay.detachSelf(animated = true)
            cancelRun(active)
        }.also { it.start() }
        AppLog.d(TAG, "flyBack: start=$start end=$end")
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    // ---------------------------------------------------------------
    // 兜底与清理
    // ---------------------------------------------------------------

    /** 去程等待超时（详情数据没回来/接口失败）：撤覆盖层，记录仍可回程。 */
    private val forwardWaitTimeout = Runnable {
        val active = run ?: return@Runnable
        if (active.stage != Stage.FORWARD_WAIT) return@Runnable
        AppLog.d(TAG, "forwardWaitTimeout: target never came")
        active.overlay?.detachSelf(animated = true)
        active.overlay = null
        active.stage = Stage.SETTLED
    }

    /** 回程挂起后 pop 没来（理论上不会）：强行撤下，避免盖住屏幕。 */
    private val returnArmTimeout = Runnable {
        val active = run ?: return@Runnable
        if (active.stage != Stage.RETURN_ARMED) return@Runnable
        AppLog.d(TAG, "returnArmTimeout: pop never came")
        cancelRun(active)
    }

    private fun cancelForward() {
        run?.let { cancelRun(it) }
    }

    private fun cancelRun(active: HeroRun) {
        mainHandler.removeCallbacks(forwardWaitTimeout)
        mainHandler.removeCallbacks(returnArmTimeout)
        active.animator?.cancel()
        active.animator = null
        active.overlay?.detachSelf(animated = true)
        active.overlay = null
        if (run === active) run = null
    }

    // ---------------------------------------------------------------
    // 几何
    // ---------------------------------------------------------------

    private fun Activity.contentRoot(): ViewGroup? =
        findViewById<ViewGroup>(android.R.id.content)

    /** 即将被 pop 的 fragment 根 view：返回栈顶 entry 按 tag 反查（容器里 add 时用的 tag）。 */
    private fun Activity.poppedFragmentView(): View? {
        val fm = (this as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
            ?: return null
        if (fm.backStackEntryCount <= 0) return null
        val name = fm.getBackStackEntryAt(fm.backStackEntryCount - 1).name
        return fm.findFragmentByTag(name)?.view
    }

    private fun View.isDescendantOf(ancestor: View?): Boolean {
        if (ancestor == null) return false
        var node: View? = this
        while (node != null) {
            if (node === ancestor) return true
            node = node.parent as? View
        }
        return false
    }

    /**
     * 视图当前视觉范围（含自身与祖先的缩放）在屏幕上的矩形。
     * 不用 getLocationOnScreen：各版本对缩放是否计入口径不一，这里按
     * layout 坐标显式累加，焦点弹簧放大后的卡片也能严丝合缝对位。
     */
    private fun View.visualRectInScreen(out: RectF): RectF {
        out.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        applyOwnScale(this, out)
        var node = parent as? View
        while (node != null) {
            out.offset((node.left - node.scrollX).toFloat(), (node.top - node.scrollY).toFloat())
            applyOwnScale(node, out)
            node = node.parent as? View
        }
        return out
    }

    private fun applyOwnScale(view: View, out: RectF) {
        val sx = view.scaleX
        val sy = view.scaleY
        if (sx == 1f && sy == 1f) return
        val cx: Float
        val cy: Float
        val layout = RectF()
        layout.set(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
        var node = view.parent as? View
        while (node != null) {
            layout.offset((node.left - node.scrollX).toFloat(), (node.top - node.scrollY).toFloat())
            node = node.parent as? View
        }
        cx = layout.centerX()
        cy = layout.centerY()
        out.set(
            cx - (cx - out.left) * sx,
            cy - (cy - out.top) * sy,
            cx + (out.right - cx) * sx,
            cy + (out.bottom - cy) * sy
        )
    }

    // ---------------------------------------------------------------
    // 覆盖层
    // ---------------------------------------------------------------

    /**
     * 全屏覆盖层：把一张封面按任意矩形（屏幕坐标）画出来，centerCrop 语义与
     * 卡片/详情头图一致，圆角随飞行在起止半径间插值。可点击（吃掉飞行期间
     * 的误触）但不可聚焦，按键照旧透传给下面的页面。
     */
    private class HeroOverlay(context: Context, private val fadeOutMs: Long) : View(context) {

        var coverDrawable: Drawable? = null
        var cornerRadius = 0f

        /** 本地坐标（相对覆盖层左上角）。 */
        val frame = RectF()

        /** 未挂载时来不及算窗口偏移，先存屏幕坐标，挂载后重算。 */
        private val pendingFrameScreen = RectF()

        private val matrix = Matrix()
        private val clipPath = Path()

        init {
            isClickable = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            setFrameScreen(pendingFrameScreen)
        }

        fun bindCover(drawable: Drawable) {
            coverDrawable = drawable
            invalidate()
        }

        fun setFrameScreen(rect: RectF) {
            pendingFrameScreen.set(rect)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            frame.set(
                rect.left - loc[0],
                rect.top - loc[1],
                rect.right - loc[0],
                rect.bottom - loc[1]
            )
            invalidate()
        }

        fun currentFrameScreen(out: RectF): RectF {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            out.set(
                frame.left + loc[0],
                frame.top + loc[1],
                frame.right + loc[0],
                frame.bottom + loc[1]
            )
            return out
        }

        /** 从父容器摘掉自己；带淡出时等动画结束再摘。 */
        fun detachSelf(animated: Boolean) {
            val parentView = parent as? ViewGroup
            animate().cancel()
            if (animated && parentView != null) {
                animate()
                    .alpha(0f)
                    .setDuration(fadeOutMs)
                    .withEndAction { parentView.removeView(this) }
                    .start()
            } else {
                parentView?.removeView(this)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val drawable = coverDrawable ?: return
            val w = frame.width()
            val h = frame.height()
            if (w <= 0f || h <= 0f) return
            val dw = drawable.intrinsicWidth.toFloat()
            val dh = drawable.intrinsicHeight.toFloat()
            if (dw <= 0f || dh <= 0f) return
            val scale = max(w / dw, h / dh)
            val dx = frame.left + (w - dw * scale) / 2f
            val dy = frame.top + (h - dh * scale) / 2f
            clipPath.reset()
            clipPath.addRoundRect(frame, cornerRadius, cornerRadius, Path.Direction.CW)
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            canvas.concat(matrix)
            drawable.setBounds(0, 0, dw.toInt(), dh.toInt())
            drawable.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }
}
