package com.btelly.feature.home

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.btelly.R
import com.btelly.core.common.ext.toast
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.log.PagePerfLogger
import com.btelly.core.navigation.VideoRouteNavigator
import com.btelly.core.ui.base.BaseListFragment
import com.btelly.core.ui.base.RecyclerViewPoolPrewarmer
import com.btelly.core.ui.focus.tv.TvDataChangeReason
import com.btelly.core.ui.render.FirstScreenRenderer
import com.btelly.model.video.VideoModel
import com.btelly.ui.activity.MainActivity
import com.btelly.ui.adapter.VideoAdapter
import com.btelly.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class VideoFeedFragment : BaseListFragment<VideoModel>(), HomeTabPage, MainActivity.OnVideoBlockedListener {

    protected abstract val feedViewModel: VideoFeedViewModel
    protected abstract val secondaryTabPosition: Int
    protected open val dispatchHomeContentReady: Boolean = false
    protected open val toastNonEmptyError: Boolean = false
    protected open val deferInitialLoadUntilFirstDraw: Boolean = false
    protected open val showInitialLoadingIndicator: Boolean = true

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private var pendingScrollToTopAfterRefresh = false
    private var initialLoadStarted = false
    private var initialLoadAfterFirstDrawArmed = false
    private var contentReadyDispatchScheduled = false
    private var contentReadyDispatched = false
    private var latestOpenStartMs = 0L

    override val autoLoad: Boolean = false
    override val initialViewHolderPrewarmPlan: RecyclerViewPoolPrewarmer.Plan = RecyclerViewPoolPrewarmer.Plan.VideoFeed
    // TV 项目无触摸下拉刷新，下拉刷新由 MainTabReselected/MenuPressed/BackPressed 等键盘事件触发。
    // 关掉可省 setupSwipeRefresh 里的 view tree 重排（removeView + addView 两次）+ 多一层 measure。
    override val enableSwipeRefresh: Boolean = false
    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                notifyTvListDataChanged(TvDataChangeReason.REMOVE_ITEM)
            },
            detectPortraitFromCover = false,
            fastFirstScreenCovers = true,
            fastFirstScreenCoverCount = 8
        )
    }

    override fun getSpanCount(): Int = 4

    override fun loadData(page: Int) {
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(this::class.java.simpleName, "request_start", "page=$page source=loadMore")
        isLoading = true
        feedViewModel.loadMore()
    }

    override fun refresh() {
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(this::class.java.simpleName, "request_start", "page=1 source=refresh hasContent=${adapter?.contentCount() ?: 0}")
        currentPage = 1
        hasMore = true
        pendingScrollToTopAfterRefresh = true
        clearTvFocusAnchorForUserRefresh()
        isLoading = true
        feedViewModel.refresh()
    }

    override fun initView() {
        super.initView()
        adapter?.setShowLoadMore(false)
    }

    override fun initData() {
        val t0 = SystemClock.elapsedRealtime()
        if (!isCurrentHomePage()) {
            showContent()
            showLoading(false)
            AppLog.i(
                "STARTUP",
                "${this::class.java.simpleName}.initialLoad deferred reason=not_current current=${currentHomePageIndex()}"
            )
        } else if (deferInitialLoadUntilFirstDraw && (adapter?.contentCount() ?: 0) == 0) {
            showContent()
            showLoading(false)
            scheduleInitialLoadAfterFirstDraw()
        } else {
            startInitialLoad(showLoading = showInitialLoadingIndicator, reason = "immediate")
        }
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initData elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun scheduleInitialLoadAfterFirstDraw() {
        if (initialLoadStarted || initialLoadAfterFirstDrawArmed) return
        val root = view ?: rootView ?: return startInitialLoad(showLoading = true, reason = "no_root")
        val className = this::class.java.simpleName
        val scheduledAtMs = SystemClock.elapsedRealtime()
        initialLoadAfterFirstDrawArmed = true
        AppLog.i("STARTUP", "$className.initialLoad deferUntilFirstDraw armed")

        var fired = false
        lateinit var listener: ViewTreeObserver.OnPreDrawListener

        fun fire(reason: String) {
            if (fired) return
            fired = true
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            root.post {
                if (!isAdded || view == null) return@post
                AppLog.i(
                    "STARTUP",
                    "$className.initialLoad afterFirstDraw reason=$reason wait=${SystemClock.elapsedRealtime() - scheduledAtMs}ms"
                )
                startInitialLoad(showLoading = true, reason = reason)
            }
        }

        listener = ViewTreeObserver.OnPreDrawListener {
            fire("first_pre_draw")
            true
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
        root.postDelayed({ fire("fallback") }, 700L)
    }

    private fun startInitialLoad(showLoading: Boolean, reason: String) {
        if (initialLoadStarted) return
        initialLoadStarted = true
        initialLoadAfterFirstDrawArmed = false
        val t0 = SystemClock.elapsedRealtime()
        if (showLoading) {
            showLoading(true)
        }
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(this::class.java.simpleName, "request_start", "page=1 source=$reason")
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initialLoad start reason=$reason")
        feedViewModel.loadInitial()
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initialLoad invoked elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || isLoading || initialLoadStarted || initialLoadAfterFirstDrawArmed) {
            return
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            startInitialLoad(showLoading = true, reason = "tabSelected")
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collectLatest { state ->
                    renderState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (!isResumed || view == null) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected -> {
                            if (event.index == 0 && !isLoading) {
                                refresh()
                            }
                        }

                        is MainNavigationViewModel.Event.SecondaryTabReselected -> {
                            if (event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                event.position == secondaryTabPosition &&
                                !isLoading
                            ) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            if (!isLoading) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onRetryClick() {
        refresh()
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            return buttonRetry?.requestFocus() == true
        }
        return super<BaseListFragment>.focusPrimaryContent()
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return super<BaseListFragment>.focusPrimaryContent(anchorView, preferSpatialEntry)
    }

    private fun renderState(state: FeedUiState<VideoModel>) {
        isLoading = state.loadingInitial || state.refreshing || state.appending
        hasMore = state.hasMore
        setRefreshing(state.refreshing)
        adapter?.setShowLoadMore(false)

        if (state.loadingInitial && state.items.isEmpty()) {
            showLoading(showInitialLoadingIndicator)
            return
        }

        state.errorMessage?.let { message ->
            isLoading = false
            setRefreshing(false)
            if ((adapter?.contentCount() ?: 0) == 0) {
                showLoading(false)
                showError(message.ifBlank { getString(R.string.net_error) })
            } else {
                if (toastNonEmptyError && message.isNotBlank()) {
                    requireContext().toast(message)
                }
            }
            return
        }

        val listChange = if (
            state.listChange == FeedListChange.NONE &&
            state.items.isNotEmpty() &&
            (adapter?.contentCount() ?: 0) == 0
        ) {
            FeedListChange.REPLACE
        } else {
            state.listChange
        }

        when (listChange) {
            FeedListChange.NONE -> Unit
            FeedListChange.REPLACE -> applyReplacedVideos(state.items)
            FeedListChange.APPEND -> applyAppendedVideos(state.items)
        }
    }

    private fun applyReplacedVideos(videos: List<VideoModel>) {
        val shouldDeferApply = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh &&
            !isRecyclerIdle()
        if (shouldDeferApply) {
            runWhenRecyclerIdle {
                if (!isAdded || view == null) {
                    return@runWhenRecyclerIdle
                }
                applyReplacedVideosNow(videos)
            }
            return
        }
        applyReplacedVideosNow(videos)
    }

    private fun applyReplacedVideosNow(videos: List<VideoModel>) {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("STARTUP", "T8 applyReplacedVideosNow count=${videos.size}")
        isLoading = false
        setRefreshing(false)
        val wasPendingScrollToTop = pendingScrollToTopAfterRefresh
        val shouldPreserveScroll = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh &&
            !isTvListFocusEnabled()
        val canBatchInitialRender = !pendingScrollToTopAfterRefresh &&
            !shouldPreserveScroll &&
            (adapter?.contentCount() ?: 0) == 0 &&
            videos.size > getSpanCount()
        if (canBatchInitialRender) {
            applyInitialVideoBatch(videos)
            pendingScrollToTopAfterRefresh = false
            feedViewModel.consumeListChange()
            AppLog.i("STARTUP", "T8 applyReplacedVideosNow first_screen count=${videos.size} elapsed=${SystemClock.elapsedRealtime() - t0}ms")
            return
        }
        setAdapterData(
            videos,
            preserveScrollOffset = shouldPreserveScroll,
            onComplete = {
                val reason = if (wasPendingScrollToTop) {
                    TvDataChangeReason.USER_REFRESH
                } else {
                    TvDataChangeReason.REPLACE_PRESERVE_ANCHOR
                }
                notifyTvListDataChanged(reason)
                logFirstCardsDraw(videos.size, source = if (wasPendingScrollToTop) "refresh" else "replace")
                if (wasPendingScrollToTop && !isPendingReturnRestore()) {
                    scrollToTop()
                    tvFocusController?.requestFocusPosition(0)
                }
            }
        )
        if (videos.isNotEmpty()) {
            showContent()
            showLoading(false)
            dispatchContentReadyAfterContentShownIfNeeded("replace")
        } else {
            showEmpty()
        }
        pendingScrollToTopAfterRefresh = false
        feedViewModel.consumeListChange()
        AppLog.i("STARTUP", "T8 applyReplacedVideosNow done count=${videos.size} elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun applyInitialVideoBatch(videos: List<VideoModel>) {
        val rv = recyclerView ?: return
        FirstScreenRenderer.render(
            recyclerView = rv,
            page = this::class.java.simpleName,
            items = videos,
            startMs = latestOpenStartMs,
            source = "first_screen",
            spanCount = getSpanCount(),
            setItems = { firstBatch, onCommitted ->
                setAdapterData(firstBatch, preserveScrollOffset = false, onComplete = onCommitted)
            },
            appendItems = { remaining ->
                (adapter as? VideoAdapter)?.addData(remaining)
            },
            onFirstBatchCommitted = {
                latestOpenStartMs = 0L
                notifyTvListDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            },
            onAppendRest = {
                notifyTvListDataChanged(TvDataChangeReason.APPEND)
            }
        )
        showContent()
        showLoading(false)
        dispatchContentReadyAfterContentShownIfNeeded("first_screen")
    }

    private fun applyAppendedVideos(items: List<VideoModel>) {
        isLoading = false
        setRefreshing(false)
        val currentCount = adapter?.contentCount() ?: 0
        val newItems = items.drop(currentCount)
        if (newItems.isNotEmpty()) {
            (adapter as? VideoAdapter)?.addData(newItems)
            showContent()
            showLoading(false)
            dispatchContentReadyAfterContentShownIfNeeded("append")
            logFirstCardsDraw(adapter?.contentCount() ?: 0, source = "append")
        }
        if (isTvListFocusEnabled()) {
            notifyTvListDataChanged(TvDataChangeReason.APPEND)
        }
        feedViewModel.consumeListChange()
    }

    private fun dispatchContentReadyIfNeeded() {
        if (dispatchHomeContentReady && !contentReadyDispatched) {
            contentReadyDispatched = true
            mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.HomeContentReady)
        }
    }

    private fun dispatchContentReadyAfterContentShownIfNeeded(reason: String) {
        if (!dispatchHomeContentReady || contentReadyDispatched || contentReadyDispatchScheduled) return
        val className = this::class.java.simpleName
        val scheduledAtMs = SystemClock.elapsedRealtime()
        contentReadyDispatchScheduled = true
        contentReadyDispatchScheduled = false
        AppLog.i(
            "STARTUP",
            "$className.contentReady contentShown reason=$reason wait=${SystemClock.elapsedRealtime() - scheduledAtMs}ms"
        )
        dispatchContentReadyIfNeeded()
    }

    private fun logFirstCardsDraw(itemCount: Int, source: String, onLogged: (() -> Unit)? = null) {
        val startMs = latestOpenStartMs
        val rv = recyclerView
        if (startMs <= 0L || itemCount <= 0 || rv == null) return
        FirstScreenRenderer.logFirstFrame(
            recyclerView = rv,
            page = this::class.java.simpleName,
            startMs = startMs,
            itemCount = itemCount,
            source = source,
            onLogged = onLogged
        )
        latestOpenStartMs = 0L
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
    }

    private fun isCurrentHomePage(): Boolean {
        return (parentFragment as? HomeFragment)?.isCurrentPage(secondaryTabPosition) != false
    }

    private fun currentHomePageIndex(): Int {
        val parent = parentFragment as? HomeFragment ?: return -1
        return (0..3).firstOrNull { parent.isCurrentPage(it) } ?: -1
    }

    private fun onVideoClick(video: VideoModel) {
        val ctx = context
        if (ctx == null) {
            AppLog.w("VideoFeed", "onVideoClick dropped: fragment context is null, bvid=${video.bvid}")
            return
        }
        VideoRouteNavigator.openVideo(
            context = ctx,
            video = video,
            playQueue = com.btelly.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        (adapter as? VideoAdapter)?.removeByVideoId(aid, bvid)
    }

    override fun onDestroyView() {
        initialLoadAfterFirstDrawArmed = false
        contentReadyDispatchScheduled = false
        super.onDestroyView()
    }
}
