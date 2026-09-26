package com.btelly.core.ui.motion

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.btelly.core.common.log.AppLog
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** 浮层视觉尺寸（[MinToastView] 与 [UiToastMin] 共用，放文件级）。 */
private const val CORNER_DP = 22f
private const val STROKE_DP = 1f
private const val ELEVATION_DP = 6f
private const val PAD_H_DP = 14f
private const val PAD_V_DP = 10f

/**
 * Min化：Toast 毛玻璃浮层（Apple 味 S-1）。
 *
 * 全站 toast 原来走系统 Toast（黑底小黑条），与 Min 化质感脱节。这里换成
 * 挂在当前页面内容根上的深色半透明 pill：圆角 + 细描边 + 柔影（伪毛玻璃，
 * 不做真背景模糊——应用内视图够不到背后内容，硬做要离屏渲染，不值），
 * 入场 overshoot 轻弹、退场减速下沉。
 *
 * 约束与取舍：
 * - 可点击（吃掉浮层期间的误触，点击即关闭）但**绝不可聚焦**：电视端焦点
 *   要留在网格/菜单上，浮层抢焦点会偷走用户的遥控器方向键；
 * - 连续触发原地更新文案 + 轻弹，不堆叠（与系统 Toast 替换语义一致）；
 * - Activity 来源：调用方 Context 若是 Activity 直接用；否则用
 *   ActivityLifecycleCallbacks 跟踪的当前 resumed Activity；都拿不到
 *   （后台线程/无界面）退回系统 Toast，行为与升级前一致；
 * - Min化 关闭时整体退回系统 Toast，零差异直出。
 */
object UiToastMin {

    private const val TAG = "UiToast"

    private const val HOLD_SHORT_MS = 2200L
    private const val HOLD_LONG_MS = 3500L

    private const val ENTER_MS = 280L
    private const val EXIT_MS = 200L
    private val ENTER_INTERPOLATOR = OvershootInterpolator(0.9f)
    private val EXIT_INTERPOLATOR = DecelerateInterpolator(1.2f)

    private const val ENTER_OFFSET_DP = 28f
    private const val REPOP_MS = 140L
    private val REPOP_INTERPOLATOR = OvershootInterpolator(0.8f)
    private const val REPOP_SCALE = 0.97f

    private const val BOTTOM_MARGIN_DP = 56f
    private const val MAX_WIDTH_RATIO = 0.64f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var topRef: WeakReference<Activity>? = null
    private var registered = false
    private var current: WeakReference<MinToastView>? = null

    private val hideRunnable = Runnable {
        val view = current?.get() ?: return@Runnable
        hide(view)
    }

    /** toast 统一入口：能拦截就拦截，拦截不了退回系统 Toast。 */
    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        if (message.isBlank()) return
        ensureRegistered(context)
        if (!UiMinStyle.isEnabled) {
            systemToast(context, message, duration)
            return
        }
        val host = (context as? Activity) ?: topRef?.get()
        if (host == null || host.isDestroyed || host.isFinishing) {
            systemToast(context, message, duration)
            return
        }
        val holdMs = if (duration == Toast.LENGTH_LONG) HOLD_LONG_MS else HOLD_SHORT_MS
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(host, message, holdMs, duration)
        } else {
            mainHandler.post { showInternal(host, message, holdMs, duration) }
        }
    }

    private fun showInternal(activity: Activity, message: CharSequence, holdMs: Long, duration: Int) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            systemToast(activity, message, duration)
            return
        }
        val existing = current?.get()
        if (existing != null && existing.isAttachedToWindow && existing.context === activity) {
            // 上一条还没走：原地换文案 + 轻弹，不叠第二条
            mainHandler.removeCallbacks(hideRunnable)
            existing.text = message
            existing.animate().cancel()
            existing.scaleX = REPOP_SCALE
            existing.scaleY = REPOP_SCALE
            existing.alpha = 1f
            existing.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(REPOP_MS)
                .setInterpolator(REPOP_INTERPOLATOR)
                .start()
            mainHandler.postDelayed(hideRunnable, holdMs)
            return
        }
        val view = MinToastView(activity).apply {
            text = message
            maxWidth = (activity.resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).roundToInt()
        }
        view.setOnClickListener { hide(view) }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = view.dp(BOTTOM_MARGIN_DP).roundToInt()
        }
        content.addView(view, lp)
        current = WeakReference(view)
        view.alpha = 0f
        view.translationY = view.dp(ENTER_OFFSET_DP)
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_MS)
            .setInterpolator(ENTER_INTERPOLATOR)
            .start()
        mainHandler.postDelayed(hideRunnable, holdMs)
        AppLog.d(TAG, "show: $message")
    }

    private fun hide(view: MinToastView) {
        mainHandler.removeCallbacks(hideRunnable)
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(view.dp(12f))
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(EXIT_MS)
            .setInterpolator(EXIT_INTERPOLATOR)
            .withEndAction {
                (view.parent as? ViewGroup)?.removeView(view)
                if (current?.get() === view) current = null
            }
            .start()
    }

    private fun systemToast(context: Context, message: CharSequence, duration: Int) {
        Toast.makeText(context.applicationContext, message, duration).show()
    }

    private fun ensureRegistered(context: Context) {
        if (registered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        registered = true
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            topRef = WeakReference(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            val view = current?.get()
            if (view != null && view.context === activity) {
                mainHandler.removeCallbacks(hideRunnable)
                (view.parent as? ViewGroup)?.removeView(view)
                current = null
            }
            if (topRef?.get() === activity) topRef = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/**
 * 单条 toast：深色半透明 pill。不可聚焦（电视端不抢方向键），可点击（误触即关）。
 */
private class MinToastView(context: Context) : TextView(context) {

    init {
        isClickable = true
        isFocusable = false
        setTextColor(Color.argb(242, 242, 242, 242))
        textSize = 15f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(
            dp(PAD_H_DP).roundToInt(),
            dp(PAD_V_DP).roundToInt(),
            dp(PAD_H_DP).roundToInt(),
            dp(PAD_V_DP).roundToInt()
        )
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = this@MinToastView.dp(CORNER_DP)
            setColor(Color.argb(235, 28, 28, 30))
            setStroke(dp(STROKE_DP).roundToInt(), Color.argb(38, 255, 255, 255))
        }
        elevation = dp(ELEVATION_DP)
    }

    fun dp(value: Float): Float = value * resources.displayMetrics.density
}
