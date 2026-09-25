package com.btelly.feature.favorite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.databinding.FragmentFavoriteDetailBinding
import com.btelly.event.AppEventHub
import com.btelly.network.session.NetworkSessionGateway
import com.btelly.repository.FavoriteRepository
import com.btelly.ui.adapter.FavoriteHistoryAdapter
import com.btelly.core.ui.base.BaseFragment
import com.btelly.core.ui.base.VideoRecyclerViewTuning
import com.btelly.core.ui.base.adaptiveSpanCount
import com.btelly.core.ui.layout.WrapContentGridLayoutManager
import com.btelly.core.ui.decoration.GridSpacingItemDecoration
import com.btelly.core.common.content.ContentFilter
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.log.PagePerfLogger
import com.btelly.core.ui.focus.tv.GridTvFocusStrategy
import com.btelly.core.ui.focus.tv.TvDataChangeReason
import com.btelly.core.ui.focus.tv.TvListFocusController
import com.btelly.core.navigation.VideoRouteNavigator
import com.btelly.core.ui.refresh.SwipeRefreshHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class FavoriteDetailFragment : BaseFragment<FragmentFavoriteDetailBinding>() {

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        private const val ARG_TITLE = "title"

        fun newInstance(folderId: Long, title: String): FavoriteDetailFragment {
            return FavoriteDetailFragment().apply {
                arguments = bundleOf(
                    ARG_FOLDER_ID to folderId,
                    ARG_TITLE to title
                )
            }
        }
    }

    private var folderId: Long = 0
    private var title: String = ""

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private lateinit var favoriteAdapter: FavoriteHistoryAdapter

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var pendingRestoreFocus = false
    private var hasRequestedInitialFocus = false
    private var tvFocusController: TvListFocusController? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var videosLoadJob: Job? = null
    private var videosRequestSerial = 0
    private var activeVideosRequestId = 0

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFavoriteDetailBinding {
        return FragmentFavoriteDetailBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        folderId = arguments?.getLong(ARG_FOLDER_ID) ?: 0
        title = arguments?.getString(ARG_TITLE) ?: ""
    }

    override fun initView() {
        binding.tvTitle.text = title

        favoriteAdapter = FavoriteHistoryAdapter(
            onItemClick = { item ->
                val video = item.toVideoModel()
                if (video.aid != 0L || video.bvid.isNotEmpty()) {
                    lastFocusedPosition = favoriteAdapter.getFocusedPosition()
                    pendingRestoreFocus = true
                    tvFocusController?.captureCurrentAnchor()
                    VideoRouteNavigator.openHistory(
                        context = requireContext(),
                        historyVideo = item,
                        playQueue = com.btelly.ui.activity.PlayerActivity.buildPlayQueue(
                            favoriteAdapter.getItemsSnapshot().map { it.toVideoModel() },
                            video
                        )
                    )
                }
            },
            onItemFocused = { position ->
                lastFocusedPosition = position
            },
            onItemFavoriteRemoved = { _ ->
                if (!isAdded) return@FavoriteHistoryAdapter
                if (favoriteAdapter.itemCount <= 1) {
                    parentFragmentManager.popBackStack()
                }
            },
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
        val spanCount = resources.adaptiveSpanCount()
        binding.recyclerViewVideos.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerViewVideos.adapter = favoriteAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerViewVideos, favoriteAdapter)
        if (binding.recyclerViewVideos.itemDecorationCount == 0) {
            binding.recyclerViewVideos.addItemDecoration(
                GridSpacingItemDecoration(spanCount, resources.getDimensionPixelSize(com.btelly.R.dimen.px20), true)
            )
        }
        binding.recyclerViewVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (!isLoading && hasMore && lastVisibleItem >= layoutManager.itemCount - 5) {
                    currentPage++
                    loadFavoriteVideos()
                }
            }
        })
        installTvFocusController()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerViewVideos,
            onRefresh = ::refreshFavoriteVideos
        )

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedPosition = RecyclerView.NO_POSITION
            }
        }

        // 统一空态组件（P2-2）：标题补焦点能力，操作按钮（错误态出现）挂重试
        binding.favoriteEmptyState.emptyStateTitle.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        binding.favoriteEmptyState.emptyStateAction.setOnClickListener { refreshFavoriteVideos() }
    }

    override fun initData() {
        loadFavoriteInfo()
        loadFavoriteVideos()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreFocus()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden && isVisible) {
                        currentPage = 1
                        hasMore = true
                        loadFavoriteInfo()
                        loadFavoriteVideos()
                    }
                }
            }
        }
    }

    private fun loadFavoriteInfo() {
        if (folderId == 0L) return

        lifecycleScope.launch {
            favoriteRepository.getFavoriteFolderInfo(folderId)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        binding.tvTitle.text = response.data.title.ifEmpty { title }
                    }
                }
        }
    }

    private fun loadFavoriteVideos() {
        if (folderId == 0L || !hasMore) return
        val restartFirstPage = currentPage <= 1
        if (isLoading && !restartFirstPage) {
            AppLog.d("FavoriteDetail", "skip load-more while loading, page=$currentPage")
            return
        }
        val requestId = ++videosRequestSerial
        activeVideosRequestId = requestId
        val requestStartMs = PagePerfLogger.now()
        if (restartFirstPage) {
            videosLoadJob?.cancel()
        }
        PagePerfLogger.markNow(
            "Favorite/detail",
            "request_start",
            "request=$requestId page=$currentPage hasContent=${favoriteAdapter.itemCount > 0}"
        )

        if (!sessionGateway.isLoggedIn()) {
            hasMore = false
            swipeRefreshLayout?.isRefreshing = false
            favoriteAdapter.setData(emptyList())
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            showEmpty(getString(R.string.need_sign_in))
            requestBackFocus()
            return
        }

        isLoading = true
        val hasExistingItems = favoriteAdapter.itemCount > 0
        if (currentPage == 1 && !hasExistingItems) {
            binding.progressBar.visibility = View.VISIBLE
        }

        val requestPage = currentPage
        videosLoadJob = lifecycleScope.launch {
            val result = try {
                favoriteRepository.getFavoriteFolderDetail(folderId, requestPage, 20)
            } catch (e: CancellationException) {
                throw e
            }

            if (!isActiveVideosRequest(requestId)) {
                AppLog.d("FavoriteDetail", "drop stale videos result request=$requestId page=$requestPage")
                return@launch
            }

            binding.progressBar.visibility = android.view.View.GONE
            swipeRefreshLayout?.isRefreshing = false
            finishVideosRequest(requestId)

            result.onSuccess { response ->
                PagePerfLogger.mark(
                    "Favorite/detail",
                    "data_collected",
                    requestStartMs,
                    "request=$requestId page=$requestPage success=${response.isSuccess}"
                )
                if (response.isSuccess) {
                    val detail = response.data
                    val medias = detail?.medias.orEmpty()
                    hasMore = detail?.hasMore == true
                    detail?.info?.title
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { binding.tvTitle.text = it }

                    if (requestPage == 1 && medias.isEmpty()) {
                        favoriteAdapter.setData(emptyList())
                        showEmpty(getString(R.string.favorite_folder_content_empty))
                    } else {
                        showListContent()
                        val filtered = medias.filter { !ContentFilter.isVideoBlocked(requireContext(), it.tagName, it.title, authorName = it.displayAuthorName) }
                        if (requestPage == 1) {
                            favoriteAdapter.setData(filtered)
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        } else if (filtered.isNotEmpty()) {
                            favoriteAdapter.addData(filtered)
                            tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        }
                        PagePerfLogger.mark(
                            "Favorite/detail",
                            "adapter_commit",
                            requestStartMs,
                            "request=$requestId page=$requestPage items=${favoriteAdapter.itemCount}"
                        )
                        if (!hasRequestedInitialFocus && requestPage == 1) {
                            hasRequestedInitialFocus = true
                            requestBackFocus()
                        } else if (lastFocusedPosition != RecyclerView.NO_POSITION) {
                            restoreFocus()
                        }
                    }
                } else {
                    rollbackPage()
                    handleLoadError(response.errorMessage)
                }
            }.onFailure { e ->
                rollbackPage()
                handleLoadError("加载失败: ${e.message}")
            }
        }
    }

    private fun isActiveVideosRequest(requestId: Int): Boolean {
        return requestId == activeVideosRequestId
    }

    private fun finishVideosRequest(requestId: Int) {
        if (isActiveVideosRequest(requestId)) {
            isLoading = false
        }
    }

    private fun showListContent() {
        binding.favoriteEmptyState.root.visibility = View.GONE
        binding.recyclerViewVideos.visibility = View.VISIBLE
    }

    /** 统一空态（P2-2 组件）：显示文案；加载类错误附重试按钮。 */
    private fun showEmpty(message: String, withRetry: Boolean = false) {
        binding.favoriteEmptyState.root.visibility = View.VISIBLE
        binding.favoriteEmptyState.emptyStateTitle.text = message
        binding.favoriteEmptyState.emptyStateAction.visibility =
            if (withRetry) View.VISIBLE else View.GONE
        binding.recyclerViewVideos.visibility = View.GONE
    }

    private fun handleLoadError(message: String) {
        if (currentPage == 1 && favoriteAdapter.itemCount == 0) {
            showEmpty(message, withRetry = true)
            return
        }
        showListContent()
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun rollbackPage() {
        if (currentPage > 1) {
            currentPage--
        }
    }

    private fun refreshFavoriteVideos() {
        currentPage = 1
        hasMore = true
        lastFocusedPosition = RecyclerView.NO_POSITION
        tvFocusController?.clearAnchorForUserRefresh()
        loadFavoriteVideos()
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.recyclerViewVideos.post {
            if (!isAdded) return@post
            if (binding.recyclerViewVideos.isVisible && favoriteAdapter.itemCount > 0) {
                val controller = tvFocusController
                // 恢复优先级：
                // ① 点击进入播放时捕获的锚点（capturedAnchor）——独立于 lastFocusedPosition，
                //    不会被"返回按钮获得焦点"的监听器清空，是从播放器返回后恢复原视频最可靠的依据；
                // ② 退而求其次用 lastFocusedPosition。
                // 注意：返回时 buttonBack 的 setOnFocusChangeListener 会把 lastFocusedPosition
                // 重置为 NO_POSITION，故不能单独依赖 lastFocusedPosition。
                if (controller != null &&
                    (controller.hasCapturedAnchor() || lastFocusedPosition != RecyclerView.NO_POSITION)
                ) {
                    // 统一走带仲裁的恢复入口（强制拉回 + 真实落点轮询 + 数据落地等待，
                    // 且恢复窗口内 onDataChanged 不会与其互派重试链），手写 retry 轮询已删除。
                    controller.restoreFocusAfterReturn(onFailed = { restoreFallbackFocus() })
                } else {
                    requestBackFocus()
                }
            } else {
                requestBackFocus()
            }
        }
    }

    /**
     * 锚点恢复失败后的兜底：lastFocusedPosition 仍有效则聚焦该位置，
     * 仍失败（以 [TvListFocusController.hasFocusInList] 真实落点为准，
     * `requestFocusPosition` 返回值不可靠——内部失败也返回 true）再退返回键。
     */
    private fun restoreFallbackFocus() {
        if (!isAdded) return
        val target = lastFocusedPosition
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceIn(0, favoriteAdapter.itemCount - 1)
        if (target == null ||
            tvFocusController?.requestFocusPosition(target) != true ||
            tvFocusController?.hasFocusInList() != true
        ) {
            requestBackFocus()
        }
    }

    private fun requestBackFocus() {
        if (!isAdded) return
        binding.buttonBack.post {
            if (isAdded && !binding.buttonBack.hasFocus()) {
                binding.buttonBack.requestFocus()
            }
        }
    }

    private fun installTvFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewVideos,
            adapter = favoriteAdapter,
            strategy = GridTvFocusStrategy { 4 },
            canLoadMore = { hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadFavoriteVideos()
                }
            }
        )
    }

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        swipeRefreshLayout = null
        super.onDestroyView()
    }
}
