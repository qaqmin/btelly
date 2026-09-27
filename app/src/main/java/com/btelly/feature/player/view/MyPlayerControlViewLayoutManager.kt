package com.btelly.feature.player.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.core.ui.motion.UiMinStyle

class MyPlayerControlViewLayoutManager(
    private val playerControlView: MyPlayerControlView,
    private val uiCoordinator: com.btelly.feature.player.PlaybackUiCoordinator? = null
) {

    companion object {
        private const val ANIMATION_DURATION_MS = 250L
        private const val OVERFLOW_ANIMATION_DURATION_MS = 220L
        private const val PROGRESS_ONLY_DURATION_MS = 1000L

        /** Min化（S-3）：控制栏进出场编排。入场 overshoot 回弹、退场减速—— */
        /** 「来有回弹、去要稳」的非对称节奏，张力与焦点弹簧同源（0.7f）。 */
        private const val MIN_SHOW_MS = 300L
        private const val MIN_HIDE_MS = 220L
        private val MIN_SHOW_INTERPOLATOR = OvershootInterpolator(0.7f)
        private val MIN_HIDE_INTERPOLATOR = DecelerateInterpolator(1.2f)
        private const val UX_STATE_ALL_VISIBLE = 0
        private const val UX_STATE_ONLY_PROGRESS_VISIBLE = 1
        private const val UX_STATE_NONE_VISIBLE = 2
        private const val UX_STATE_ANIMATING_HIDE = 3
        private const val UX_STATE_ANIMATING_SHOW = 4

        /** 调试日志用的状态名——uxState 是整数，日志里必须能直接读。 */
        private fun stateName(state: Int): String = when (state) {
            UX_STATE_ALL_VISIBLE -> "ALL_VISIBLE"
            UX_STATE_ONLY_PROGRESS_VISIBLE -> "ONLY_PROGRESS"
            UX_STATE_NONE_VISIBLE -> "NONE_VISIBLE"
            UX_STATE_ANIMATING_HIDE -> "ANIMATING_HIDE"
            UX_STATE_ANIMATING_SHOW -> "ANIMATING_SHOW"
            else -> "UNKNOWN($state)"
        }
    }

    private val controlsBackground: View = playerControlView.findViewById(R.id.exo_controls_background)
    private val centerControls: ViewGroup = playerControlView.findViewById(R.id.exo_center_controls)
    private val bottomBar: ViewGroup = playerControlView.findViewById(R.id.exo_bottom_bar)
    private val timeView: ViewGroup = playerControlView.findViewById(R.id.exo_time)
    private val titleView: ViewGroup = playerControlView.findViewById(R.id.view_title)
    private val subtitleView: View = playerControlView.findViewById(R.id.text_sub_title)
    private val bottomBarController: ViewGroup = playerControlView.findViewById(R.id.view_bottom_controller)
    private val timeBar: DefaultTimeBar = playerControlView.findViewById(R.id.exo_progress)
    private val basicControls: ViewGroup = playerControlView.findViewById(R.id.exo_basic_controls)
    private val extraControls: ViewGroup = playerControlView.findViewById(R.id.exo_extra_controls)
    private val extraControlsScrollView: ViewGroup = playerControlView.findViewById(R.id.exo_extra_controls_scroll_view)
    private val overflowShowButton: View = playerControlView.findViewById(R.id.exo_overflow_show)
    private val overflowHideButton: View = playerControlView.findViewById(R.id.exo_overflow_hide)
    private val orderedButtons: List<View> = listOf(
        playerControlView.findViewById(R.id.button_play),
        playerControlView.findViewById(R.id.button_refresh),
        playerControlView.findViewById(R.id.button_previous),
        playerControlView.findViewById(R.id.button_next),
        playerControlView.findViewById(R.id.button_rewind),
        playerControlView.findViewById(R.id.button_fast_forward),
        playerControlView.findViewById(R.id.button_dm_switch),
        playerControlView.findViewById(R.id.button_mirror),
        playerControlView.findViewById(R.id.exo_settings),
        playerControlView.findViewById(R.id.button_choose_episode),
        playerControlView.findViewById(R.id.button_more),
        playerControlView.findViewById(R.id.button_up_info),
        playerControlView.findViewById(R.id.button_subtitle),
        playerControlView.findViewById(R.id.button_related),
        playerControlView.findViewById(R.id.button_repeat),
        playerControlView.findViewById(R.id.button_live_settings),
        playerControlView.findViewById(R.id.button_close)
    )
    private val overflowPriority = listOf(
        R.id.button_live_settings,
        R.id.button_repeat,
        R.id.button_related,
        R.id.button_subtitle,
        R.id.button_up_info,
        R.id.button_more,
        R.id.button_choose_episode,
        R.id.button_refresh,
        R.id.button_dm_switch,
        R.id.button_mirror,
        R.id.button_next,
        R.id.button_previous,
        R.id.button_fast_forward,
        R.id.button_rewind,
        R.id.exo_settings
    )
    private val hiddenTranslationY by lazy {
        playerControlView.resources.getDimension(R.dimen.px147)
    }
    private val originalTimeBarTopMargin by lazy {
        (timeBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    }
    private val originalBottomBarPaddingBottom = bottomBar.paddingBottom
    private val compactBottomBarPaddingBottom = originalBottomBarPaddingBottom / 2
    private val hideMainBarRunnable = Runnable {
        AppLog.dLogcat("CtrlBar", "hideMainBarRunnable fired (dwell=${dwellLabel()})")
        hideMainBar()
    }
    private val hideProgressBarRunnable = Runnable {
        AppLog.dLogcat("CtrlBar", "hideProgressBarRunnable fired (dwell=${dwellLabel()})")
        hideProgressBar()
    }
    private val hideControllerRunnable = Runnable {
        AppLog.dLogcat("CtrlBar", "hideControllerRunnable fired (dwell=${dwellLabel()})")
        hideController()
    }
    private val onLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            scheduleAdaptiveLayout()
        }
    }

    private val shownButtons = LinkedHashSet<View>()
    private var uxState: Int = UX_STATE_ALL_VISIBLE
    private var isAnimationEnabled: Boolean = true
    private var needToShowBars: Boolean = false
    private var isOverflowVisible: Boolean = false
    private var isCompactMode: Boolean = false
    private var progressOnlyUiEnabled: Boolean = true
    private var timeViewVisible: Boolean = true
    private var adaptiveLayoutScheduled: Boolean = false

    /** 最近一次 show() 的发起时刻，用于日志里算「控制栏实际停留了多久」。 */
    private var showIssuedAtMs: Long = 0L

    private fun dwellLabel(): String {
        if (showIssuedAtMs <= 0L) return "n/a"
        return "${SystemClock.elapsedRealtime() - showIssuedAtMs}ms"
    }

    init {
        overflowShowButton.setOnClickListener { showOverflowControls() }
        overflowHideButton.setOnClickListener { hideOverflowControls() }
        extraControlsScrollView.visibility = View.INVISIBLE
        overflowShowButton.visibility = View.GONE
    }

    fun show(focusPlayPause: Boolean = false) {
        val fromSeekProgress = uxState == UX_STATE_ONLY_PROGRESS_VISIBLE
        showIssuedAtMs = SystemClock.elapsedRealtime()
        AppLog.dLogcat(
            "CtrlBar",
            "show() focusPlayPause=$focusPlayPause uxState=${stateName(uxState)} " +
                "fromSeekProgress=$fromSeekProgress timeoutMs=${playerControlView.getShowTimeoutMs()} " +
                "anim=$isAnimationEnabled visible=${playerControlView.isVisible()}"
        )
        if (!playerControlView.isVisible()) {
            playerControlView.visibility = View.VISIBLE
            playerControlView.updateAll()
            playerControlView.startProgressUpdates()
        }
        updateAdaptiveLayout()
        showAllBars()
        playerControlView.post {
            if (fromSeekProgress && !focusPlayPause) {
                playerControlView.requestTimeBarFocus()
            } else {
                playerControlView.requestPlayPauseFocus()
            }
        }
    }

    fun hide() {
        AppLog.dLogcat("CtrlBar", "hide() req uxState=${stateName(uxState)} dwell=${dwellLabel()}")
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        removeHideCallbacks()
        if (!isAnimationEnabled) {
            hideController()
        } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
            hideProgressBar()
        } else {
            hideMainBar()
        }
    }

    fun hideImmediately() {
        AppLog.dLogcat("CtrlBar", "hideImmediately() req uxState=${stateName(uxState)} dwell=${dwellLabel()}")
        if (uxState == UX_STATE_ANIMATING_HIDE || (uxState == UX_STATE_NONE_VISIBLE && !playerControlView.isVisible())) {
            return
        }
        removeHideCallbacks()
        cancelAllAnimations()
        hideController()
    }

    fun isFullyVisible(): Boolean = uxState == UX_STATE_ALL_VISIBLE && playerControlView.isVisible()

    fun setAnimationEnabled(enabled: Boolean) {
        isAnimationEnabled = enabled
    }

    fun setProgressOnlyUiEnabled(enabled: Boolean) {
        progressOnlyUiEnabled = enabled
    }

    fun setTimeViewVisible(visible: Boolean) {
        timeViewVisible = visible
        timeView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun getShowButton(button: View?): Boolean = button != null && shownButtons.contains(button)

    fun setShowButton(button: View?, show: Boolean) {
        if (button == null) {
            return
        }
        if (!show) {
            shownButtons.remove(button)
            removeViewFromParent(button)
            button.visibility = View.GONE
        } else {
            shownButtons.add(button)
            button.visibility = View.VISIBLE
        }
        scheduleAdaptiveLayout()
    }

    fun removeHideCallbacks() {
        playerControlView.removeCallbacks(hideControllerRunnable)
        playerControlView.removeCallbacks(hideMainBarRunnable)
        playerControlView.removeCallbacks(hideProgressBarRunnable)
    }

    fun resetHideCallbacks() {
        val timeoutMs = playerControlView.getShowTimeoutMs()
        if (uxState == UX_STATE_ANIMATING_HIDE) {
            AppLog.dLogcat("CtrlBar", "resetHideCallbacks -> skip (ANIMATING_HIDE) uxState=${stateName(uxState)}")
            return
        }
        removeHideCallbacks()
        if (timeoutMs <= 0 || !playerControlView.isVisible()) {
            AppLog.dLogcat(
            "CtrlBar",
                "resetHideCallbacks -> no schedule (timeoutMs=$timeoutMs visible=${playerControlView.isVisible()}) " +
                    "uxState=${stateName(uxState)}"
            )
            return
        }
        when {
            !isAnimationEnabled -> {
                AppLog.dLogcat("CtrlBar", "resetHideCallbacks -> hideControllerRunnable in ${timeoutMs}ms (anim off)")
                playerControlView.postDelayed(hideControllerRunnable, timeoutMs.toLong())
            }
            uxState == UX_STATE_ONLY_PROGRESS_VISIBLE && progressOnlyUiEnabled -> {
                AppLog.dLogcat(
            "CtrlBar",
                    "resetHideCallbacks -> hideProgressBarRunnable in ${PROGRESS_ONLY_DURATION_MS}ms " +
                        "(ONLY_PROGRESS branch, timer=${PROGRESS_ONLY_DURATION_MS}ms)"
                )
                playerControlView.postDelayed(hideProgressBarRunnable, PROGRESS_ONLY_DURATION_MS)
            }
            else -> {
                AppLog.dLogcat("CtrlBar", "resetHideCallbacks -> hideMainBarRunnable in ${timeoutMs}ms uxState=${stateName(uxState)}")
                playerControlView.postDelayed(hideMainBarRunnable, timeoutMs.toLong())
            }
        }
    }

    fun hideInfoOnlyLeftTimeBar() {
        if (titleView.visibility != View.VISIBLE || bottomBarController.visibility != View.VISIBLE) {
            return
        }
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        titleView.animate()
            .alpha(0f)
            .setDuration(300L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    titleView.visibility = View.INVISIBLE
                }
            })
            .start()
        bottomBarController.animate()
            .alpha(0f)
            .setDuration(300L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomBarController.visibility = View.INVISIBLE
                }
            })
            .start()
    }

    fun showInfoAfterEndFF() {
        if (titleView.visibility == View.VISIBLE && bottomBarController.visibility == View.VISIBLE) {
            return
        }
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        titleView.visibility = View.VISIBLE
        bottomBarController.visibility = View.VISIBLE
        titleView.animate().alpha(1f).setDuration(300L).setListener(null).start()
        bottomBarController.animate().alpha(1f).setDuration(300L).setListener(null).start()
    }

    fun onAttachedToWindow() {
        playerControlView.addOnLayoutChangeListener(onLayoutChangeListener)
        playerControlView.post { updateAdaptiveLayout() }
    }

    fun onDetachedFromWindow() {
        playerControlView.removeOnLayoutChangeListener(onLayoutChangeListener)
        removeHideCallbacks()
        cancelAllAnimations()
    }

    /**
     * 进入 seek 模式：只显示进度条+时间，隐藏其他 UI。
     * 无论当前处于什么状态都可以调用。
     */
    fun enterSeekProgressOnly() {
        AppLog.dLogcat("CtrlBar", "enterSeekProgressOnly uxState=${stateName(uxState)} dwell=${dwellLabel()}")
        cancelAllAnimations()
        removeHideCallbacks()
        playerControlView.visibility = View.VISIBLE
        controlsBackground.visibility = View.INVISIBLE
        centerControls.visibility = View.INVISIBLE
        titleView.visibility = View.INVISIBLE
        bottomBarController.visibility = View.INVISIBLE
        bottomBar.visibility = View.VISIBLE
        bottomBar.translationY = 0f
        bottomBar.alpha = 1f
        timeBar.showScrubber()
        // 不启动 progressRunnable，避免与 beginSeekPreview() 冲突导致进度条头乱跳
        // 位置由 beginSeekPreview() 通过 seek tick 驱动
        setUxState(UX_STATE_ONLY_PROGRESS_VISIBLE)
    }

    /**
     * 退出 seek 模式：立刻隐藏进度条和整个控制器，不闪现其他 UI。
     *
     * 契约：只在 uxState == ONLY_PROGRESS_VISIBLE 时真正收尾；其它状态下静默返回。
     * 6 个调用点（MyPlayerView 的 seekSession/BACK/commit/滑动手势等路径）都不保证
     * 那一刻状态还在 ONLY_PROGRESS——例如控制栏刚 show 又立刻双击横滑，enterSeekProgressOnly
     * 把状态推到 ONLY_PROGRESS 后，入场动画的迟到回调曾把它抢回 ALL_VISIBLE，本函数就空转了。
     * 该抢状态问题已由 animateMainInfo 的 startedAtState 守卫修掉；这里保留日志，
     * 一旦再出现"守卫未命中"就是新的抢状态案例，按 CtrlBar 日志定位。
     */
    fun exitSeekProgressOnly() {
        AppLog.dLogcat("CtrlBar", "exitSeekProgressOnly uxState=${stateName(uxState)} dwell=${dwellLabel()}")
        if (uxState != UX_STATE_ONLY_PROGRESS_VISIBLE) {
            AppLog.dLogcat(
                "CtrlBar",
                "exitSeekProgressOnly guard miss: expected ONLY_PROGRESS, got ${stateName(uxState)} — no cleanup"
            )
            return
        }
        cancelAllAnimations()
        playerControlView.stopProgressUpdates()
        playerControlView.visibility = View.GONE
        setUxState(UX_STATE_NONE_VISIBLE)
    }

    fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) {
            controlsBackground.layout(0, 0, right - left, bottom - top)
            updateAdaptiveLayout()
        }
    }

    /** Min化（S-3）：控制栏滑动时长。入场 300ms（回弹要时间展开）、退场 220ms；关闭时维持旧值。 */
    private val controlShowMs: Long
        get() = if (UiMinStyle.isEnabled) MIN_SHOW_MS else ANIMATION_DURATION_MS

    private val controlHideMs: Long
        get() = if (UiMinStyle.isEnabled) MIN_HIDE_MS else ANIMATION_DURATION_MS

    /**
     * Min化（S-3）：给控制栏滑动套编排。入场 overshoot 回弹、退场减速；
     * Min化 关闭时保持调用处原样（默认插值 + 原时长），零差异。
     */
    private fun ViewPropertyAnimator.minControlSlide(show: Boolean): ViewPropertyAnimator {
        if (!UiMinStyle.isEnabled) return this
        setDuration(if (show) controlShowMs else controlHideMs)
        setInterpolator(if (show) MIN_SHOW_INTERPOLATOR else MIN_HIDE_INTERPOLATOR)
        return this
    }

    private fun hideMainBar() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_ONLY_PROGRESS_VISIBLE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        cancelAllAnimations()
        needToShowBars = false
        setUxState(UX_STATE_ANIMATING_HIDE)
        timeBar.hideScrubber(controlHideMs)
        if (bottomBarController.hasFocus() || titleView.hasFocus() || playerControlView.isAnyPrimaryControlFocused()) {
            timeBar.requestFocus()
        }
        controlsBackground.visibility = View.INVISIBLE
        animateMainInfo(visible = false) {}
        var completed = false
        val completeHide = {
            if (!completed) {
                completed = true
                hideController()
                restoreMainBarImmediately()
                if (needToShowBars) {
                    needToShowBars = false
                    playerControlView.post { showAllBars() }
                }
            }
        }
        bottomBar.animate()
            .translationY(hiddenTranslationY)
            .minControlSlide(show = false)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeHide()
                }
            })
            .start()
        // 兜底：bottomBar 动画的监听器同样可能被复用同一 ViewPropertyAnimator 的
        // 路径静默摘掉（setListener(null)），丢失时 uxState 残留 ANIMATING_HIDE，
        // needToShowBars 永真、之后每次 show 都被吞，控制栏同样永久唤不出。
        // 退场视觉上 controlHideMs 必然完成，到时强制收口一次。
        playerControlView.postDelayed({ completeHide() }, controlHideMs + 120L)
    }

    private fun hideProgressBar() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        cancelAllAnimations()
        needToShowBars = false
        setUxState(UX_STATE_ANIMATING_HIDE)
        controlsBackground.visibility = View.INVISIBLE
        var completed = false
        val completeHide = {
            if (!completed) {
                completed = true
                hideController()
                restoreMainBarImmediately()
                if (needToShowBars) {
                    needToShowBars = false
                    playerControlView.post { showAllBars() }
                }
            }
        }
        bottomBar.animate()
            .translationY(hiddenTranslationY)
            .minControlSlide(show = false)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeHide()
                }
            })
            .start()
        playerControlView.postDelayed({ completeHide() }, controlHideMs + 120L)
    }

    private fun hideController() {
        playerControlView.stopProgressUpdates()
        setUxState(UX_STATE_NONE_VISIBLE)
    }

    private fun showAllBars() {
        AppLog.dLogcat(
            "CtrlBar",
            "showAllBars uxState=${stateName(uxState)} anim=$isAnimationEnabled " +
                "visibility=${playerControlView.visibility} dwell=${dwellLabel()}"
        )
        if (!isAnimationEnabled) {
            playerControlView.visibility = View.VISIBLE
            restoreMainBarImmediately()
            timeBar.showScrubber()
            setUxState(UX_STATE_ALL_VISIBLE)
            playerControlView.startProgressUpdates()
            resetHideCallbacks()
            return
        }
        when (uxState) {
            UX_STATE_ONLY_PROGRESS_VISIBLE -> animateShowMainBar()
            UX_STATE_NONE_VISIBLE -> animateShowAllBars()
            UX_STATE_ANIMATING_HIDE -> needToShowBars = true
            UX_STATE_ANIMATING_SHOW -> {
                // 自愈：入场收口回调丢失时（见 animateMainInfo 兜底注释）uxState 会
                // 残留 ANIMATING_SHOW，此后每次 show 都直接 return，控制栏永久唤不出。
                // 视觉已稳定（滑到位、info 全显）时直接推进到 ALL_VISIBLE。
                if (bottomBar.translationY == 0f && controlsBackground.alpha >= 1f) {
                    setUxState(UX_STATE_ALL_VISIBLE)
                } else {
                    return
                }
            }
        }
        resetHideCallbacks()
    }

    private fun animateShowMainBar() {
        cancelAllAnimations()
        setUxState(UX_STATE_ANIMATING_SHOW)
        playerControlView.visibility = View.VISIBLE
        controlsBackground.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        if (timeViewVisible) timeView.visibility = View.VISIBLE
        bottomBar.animate()
            .translationY(0f)
            .minControlSlide(show = true)
            .setListener(null)
            .start()
        timeBar.showScrubber(controlShowMs)
        playerControlView.startProgressUpdates()
        animateMainInfo(visible = true) {
            setUxState(UX_STATE_ALL_VISIBLE)
            resetHideCallbacks()
        }
    }

    private fun animateShowAllBars() {
        cancelAllAnimations()
        playerControlView.visibility = View.VISIBLE
        restoreMainBarImmediately(initialAlpha = 0f)
        bottomBar.translationY = hiddenTranslationY
        setUxState(UX_STATE_ANIMATING_SHOW)
        timeBar.showScrubber(controlShowMs)
        playerControlView.startProgressUpdates()
        bottomBar.animate()
            .translationY(0f)
            .minControlSlide(show = true)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animateMainInfo(visible = true) {
                        setUxState(UX_STATE_ALL_VISIBLE)
                        resetHideCallbacks()
                    }
                }
            })
            .start()
    }

    private fun animateMainInfo(visible: Boolean, endAction: () -> Unit) {
        val targetAlpha = if (visible) 1f else 0f
        // Min化（S-3）：info 渐显/渐隐比滑动收得快——滑动是主角，渐隐是陪衬
        val infoMs = when {
            !UiMinStyle.isEnabled -> ANIMATION_DURATION_MS
            visible -> 200L
            else -> 180L
        }
        // 启动时刻的状态。下面的兜底 finish() 可能迟到（延迟 320ms），动画进行中若有
        // seek/隐藏路径改写了 uxState，迟到的那次就不能再推状态——实测「刚 show 就立刻
        // 双击横滑」时 enterSeekProgressOnly 把状态推到 ONLY_PROGRESS，随后迟到的 finish
        // 又把它推回 ALL_VISIBLE，导致 exitSeekProgressOnly 的守卫失配、seek 收尾空转。
        val startedAtState = uxState
        if (visible) {
            controlsBackground.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            titleView.visibility = View.VISIBLE
            bottomBarController.visibility = View.VISIBLE
            applyCompactMode(force = true)
            applyOverflowState()
        }
        var completedCount = 0
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                if (!visible) {
                    controlsBackground.visibility = View.INVISIBLE
                    centerControls.visibility = View.INVISIBLE
                    titleView.visibility = View.INVISIBLE
                    bottomBarController.visibility = View.INVISIBLE
                }
                if (uxState == startedAtState) {
                    endAction()
                } else {
                    AppLog.dLogcat(
                        "CtrlBar",
                        "animateMainInfo finish suppressed: state moved " +
                            "${stateName(startedAtState)} -> ${stateName(uxState)}"
                    )
                }
            }
        }
        val onPartEnd = {
            completedCount += 1
            if (completedCount >= 4) {
                finish()
            }
        }
        // 兜底收口：同一 view 的 ViewPropertyAnimator 是复用的，FF 状态切换
        // （hideInfoOnlyLeftTimeBar/showInfoAfterEndFF）cancel+setListener(null)
        // 会静默摘掉这里刚挂的监听器，计数永远凑不满——uxState 卡在 ANIMATING_SHOW，
        // 之后 showAllBars() 直接 return、resetHideCallbacks() 也不再注册，
        // 控制栏永久唤不出。渐显视觉上 infoMs 必然完成，到时强制收口一次。
        playerControlView.postDelayed({ finish() }, infoMs + 120L)
        controlsBackground.animate()
            .alpha(targetAlpha)
            .setDuration(infoMs)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        centerControls.animate()
            .alpha(targetAlpha)
            .setDuration(infoMs)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        titleView.animate()
            .alpha(targetAlpha)
            .setDuration(infoMs)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        bottomBarController.animate()
            .alpha(targetAlpha)
            .setDuration(infoMs)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
    }

    private fun updateAdaptiveLayout() {
        if (playerControlView.width <= 0) {
            return
        }
        syncOverflowButtons()
        applyCompactMode(force = false)
        applyOverflowState()
    }

    private fun scheduleAdaptiveLayout() {
        if (adaptiveLayoutScheduled) {
            return
        }
        adaptiveLayoutScheduled = true
        playerControlView.post {
            adaptiveLayoutScheduled = false
            updateAdaptiveLayout()
        }
    }

    private fun syncOverflowButtons() {
        val visibleButtons = orderedButtons.filter { shownButtons.contains(it) }
        val movedIds = LinkedHashSet<Int>()
        while (requiredWidth(visibleButtons, movedIds) > availableWidth()) {
            val nextId = overflowPriority.firstOrNull { candidateId ->
                candidateId !in movedIds && visibleButtons.any { it.id == candidateId }
            } ?: break
            movedIds.add(nextId)
        }

        val basicButtons = visibleButtons.filterNot { it.id in movedIds }
        val extraButtons = visibleButtons.filter { it.id in movedIds }
        val focusedExtraHidden = !isOverflowVisible && extraButtons.any { it.isFocused }
        val previouslyFocusedButton = orderedButtons.firstOrNull { it.isFocused }

        basicControls.removeAllViews()
        extraControls.removeAllViews()

        basicButtons.forEach { button ->
            removeViewFromParent(button)
            basicControls.addView(button)
            button.visibility = View.VISIBLE
        }

        val hasOverflow = extraButtons.isNotEmpty()
        if (hasOverflow) {
            removeViewFromParent(overflowShowButton)
            basicControls.addView(overflowShowButton)
            overflowShowButton.visibility = if (isOverflowVisible) View.INVISIBLE else View.VISIBLE

            removeViewFromParent(overflowHideButton)
            extraControls.addView(overflowHideButton)
            extraButtons.forEach { button ->
                removeViewFromParent(button)
                extraControls.addView(button)
                button.visibility = View.VISIBLE
            }
        } else {
            overflowShowButton.visibility = View.GONE
            extraControlsScrollView.visibility = View.INVISIBLE
            isOverflowVisible = false
        }

        if (focusedExtraHidden) {
            playerControlView.post {
                if (overflowShowButton.visibility == View.VISIBLE) {
                    overflowShowButton.requestFocus()
                } else {
                    playerControlView.requestPlayPauseFocus()
                }
            }
        } else if (previouslyFocusedButton != null && previouslyFocusedButton.visibility == View.VISIBLE) {
            previouslyFocusedButton.requestFocus()
        }
    }

    private fun applyCompactMode(force: Boolean) {
        val compactMode = useCompactMode()
        if (!force && compactMode == isCompactMode) {
            return
        }
        isCompactMode = compactMode

        subtitleView.visibility = if (compactMode) View.GONE else View.VISIBLE

        val timeBarLayoutParams = timeBar.layoutParams as ViewGroup.MarginLayoutParams
        timeBarLayoutParams.topMargin = if (compactMode) 0 else originalTimeBarTopMargin
        timeBar.layoutParams = timeBarLayoutParams

        bottomBar.setPadding(
            bottomBar.paddingLeft,
            bottomBar.paddingTop,
            bottomBar.paddingRight,
            if (compactMode) compactBottomBarPaddingBottom else originalBottomBarPaddingBottom
        )
    }

    private fun useCompactMode(): Boolean {
        val availableHeight = playerControlView.height - playerControlView.paddingTop - playerControlView.paddingBottom
        if (availableHeight <= 0) {
            return false
        }
        return availableHeight <= titleView.measuredHeight + bottomBar.measuredHeight + timeBar.measuredHeight
    }

    private fun availableWidth(): Int {
        return playerControlView.width - playerControlView.paddingLeft - playerControlView.paddingRight
    }

    private fun requiredWidth(visibleButtons: List<View>, movedIds: Set<Int>): Int {
        val basicWidth = visibleButtons
            .filterNot { it.id in movedIds }
            .sumOf { getWidthWithMargins(it) }
        val overflowWidth = if (movedIds.isNotEmpty()) getWidthWithMargins(overflowShowButton) else 0
        return basicWidth + overflowWidth + getWidthWithMargins(timeView)
    }

    private fun applyOverflowState() {
        val hasOverflow = extraControls.childCount > 1
        if (!hasOverflow) {
            isOverflowVisible = false
            cancelOverflowAnimations()
            setOverflowProgress(0f)
            extraControlsScrollView.visibility = View.GONE
            overflowShowButton.visibility = View.GONE
            return
        }
        if (isOverflowVisible) {
            overflowShowButton.visibility = View.INVISIBLE
            cancelOverflowAnimations()
            extraControlsScrollView.visibility = View.VISIBLE
            setOverflowProgress(1f)
        } else {
            overflowShowButton.visibility = View.VISIBLE
            cancelOverflowAnimations()
            setOverflowProgress(0f)
            extraControlsScrollView.visibility = View.GONE
        }
    }

    private fun showOverflowControls() {
        if (extraControls.childCount <= 1 || isOverflowVisible) {
            return
        }
        resetHideCallbacks()
        isOverflowVisible = true
        animateOverflow(show = true)
    }

    private fun hideOverflowControls() {
        if (!isOverflowVisible) {
            return
        }
        resetHideCallbacks()
        isOverflowVisible = false
        val restoreFocus = extraControls.anyDescendantFocused()
        animateOverflow(show = false, restoreOverflowButtonFocus = restoreFocus)
    }

    private fun animateOverflow(show: Boolean, restoreOverflowButtonFocus: Boolean = false) {
        cancelOverflowAnimations()
        if (show) {
            extraControlsScrollView.visibility = View.VISIBLE
            overflowShowButton.visibility = View.INVISIBLE
            setOverflowProgress(0f)
        } else {
            setOverflowProgress(1f)
        }
        extraControlsScrollView.animate()
            .translationX(if (show) 0f else extraControlsWidth().toFloat())
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!show) {
                        extraControlsScrollView.visibility = View.GONE
                        overflowShowButton.visibility = View.VISIBLE
                        if (restoreOverflowButtonFocus) {
                            overflowShowButton.requestFocus()
                        }
                    } else if (overflowHideButton.visibility == View.VISIBLE) {
                        overflowHideButton.requestFocus()
                    }
                }
            })
            .start()
        basicControls.animate()
            .alpha(if (show) 0f else 1f)
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
        timeView.animate()
            .alpha(if (show) 0f else 1f)
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
    }

    private fun setOverflowProgress(progress: Float) {
        basicControls.alpha = 1f - progress
        timeView.alpha = 1f - progress
        extraControlsScrollView.translationX = (1f - progress) * extraControlsWidth()
    }

    private fun extraControlsWidth(): Int {
        val width = extraControlsScrollView.width.takeIf { it > 0 }
            ?: extraControls.width.takeIf { it > 0 }
        return width ?: extraControls.childrenWidth()
    }

    private fun restoreMainBarImmediately(initialAlpha: Float = 1f) {
        controlsBackground.visibility = View.VISIBLE
        controlsBackground.alpha = initialAlpha
        centerControls.visibility = View.VISIBLE
        centerControls.alpha = initialAlpha
        bottomBar.visibility = View.VISIBLE
        bottomBar.alpha = 1f
        bottomBar.translationY = 0f
        titleView.visibility = View.VISIBLE
        bottomBarController.visibility = View.VISIBLE
        if (timeViewVisible) timeView.visibility = View.VISIBLE
        titleView.alpha = initialAlpha
        bottomBarController.alpha = initialAlpha
        applyCompactMode(force = true)
        applyOverflowState()
    }

    private fun cancelAllAnimations() {
        cancelViewAnimation(centerControls)
        cancelViewAnimation(bottomBar)
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        cancelViewAnimation(controlsBackground)
        cancelOverflowAnimations()
    }

    private fun cancelOverflowAnimations() {
        cancelViewAnimation(extraControlsScrollView)
        cancelViewAnimation(basicControls)
        cancelViewAnimation(timeView)
    }

    private fun cancelViewAnimation(view: View) {
        view.animate().setListener(null)
        view.animate().cancel()
    }

    private fun removeViewFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun getWidthWithMargins(view: View): Int {
        val layoutParams = view.layoutParams
        val baseWidth = when {
            view.width > 0 -> view.width
            view.measuredWidth > 0 -> view.measuredWidth
            layoutParams != null && layoutParams.width > 0 -> layoutParams.width
            else -> 0
        }
        val marginLayoutParams = layoutParams as? ViewGroup.MarginLayoutParams ?: return baseWidth
        return baseWidth + marginLayoutParams.leftMargin + marginLayoutParams.rightMargin
    }

    private fun ViewGroup.childrenWidth(): Int {
        var total = 0
        for (index in 0 until childCount) {
            total += getWidthWithMargins(getChildAt(index))
        }
        return total
    }

    private fun ViewGroup.anyDescendantFocused(): Boolean {
        if (hasFocus()) {
            return true
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isFocused) {
                return true
            }
            if (child is ViewGroup && child.anyDescendantFocused()) {
                return true
            }
        }
        return false
    }

    private fun setUxState(newState: Int) {
        val oldState = uxState
        uxState = newState
        if (oldState != newState) {
            AppLog.dLogcat(
            "CtrlBar",
                "uxState ${stateName(oldState)} -> ${stateName(newState)} dwell=${dwellLabel()}"
            )
        }
        if (newState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.GONE
            (playerControlView.parent as? View)?.requestFocus()
        } else if (oldState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.VISIBLE
        }
        if (oldState != newState) {
            val effective = when (newState) {
                UX_STATE_ANIMATING_SHOW, UX_STATE_ALL_VISIBLE -> View.VISIBLE
                UX_STATE_ANIMATING_HIDE, UX_STATE_ONLY_PROGRESS_VISIBLE -> View.INVISIBLE
                UX_STATE_NONE_VISIBLE -> View.GONE
                else -> playerControlView.visibility
            }
            playerControlView.notifyChromeState(effective)
        }
        uiCoordinator?.let { coordinator ->
            when (newState) {
                UX_STATE_ALL_VISIBLE -> {
                    coordinator.transition(com.btelly.feature.player.UiEvent.ChromeShowAll)
                }
                UX_STATE_ONLY_PROGRESS_VISIBLE -> {
                    if (coordinator.chromeState != com.btelly.feature.player.PlaybackUiCoordinator.ChromeState.ProgressOnly) {
                        coordinator.withState { state ->
                            state.chromeState = com.btelly.feature.player.PlaybackUiCoordinator.ChromeState.ProgressOnly
                            state.bottomOccupant = com.btelly.feature.player.PlaybackUiCoordinator.BottomOccupant.FullChrome
                            state.hudState = com.btelly.feature.player.PlaybackUiCoordinator.HudState.Chrome
                        }
                    }
                }
                UX_STATE_NONE_VISIBLE -> {
                    coordinator.withState { state ->
                        state.chromeState = com.btelly.feature.player.PlaybackUiCoordinator.ChromeState.Hidden
                        state.bottomOccupant = com.btelly.feature.player.PlaybackUiCoordinator.BottomOccupant.SlimTimeline
                        state.hudState = com.btelly.feature.player.PlaybackUiCoordinator.HudState.Ambient
                    }
                }
            }
        }
    }
}
