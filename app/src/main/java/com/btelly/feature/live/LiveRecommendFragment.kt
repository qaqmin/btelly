package com.btelly.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.databinding.FragmentLiveBaseListBinding
import com.btelly.model.live.LiveListWrapper
import com.btelly.model.live.LiveRecommendSection
import com.btelly.ui.activity.LivePlayerActivity
import com.btelly.core.ui.base.BaseFragment
import com.btelly.core.ui.image.ImageLoader
import com.btelly.ui.fragment.main.MainNavigationViewModel
import com.btelly.core.common.content.ContentFilter
import com.btelly.core.ui.focus.SpatialFocusNavigator
import com.btelly.core.ui.focus.TabContentFocusHelper
import com.btelly.core.ui.refresh.SwipeRefreshHelper
import com.btelly.core.common.ext.toast
import com.btelly.core.common.log.AppLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveRecommendFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {
    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val PREFETCH_SECTION_COUNT = 2
        private const val PREFETCH_PER_SECTION = 8
        private const val FIRST_RENDER_SECTIONS = 3

        fun newInstance(): LiveRecommendFragment = LiveRecommendFragment()
    }

    private val viewModel: LiveRecommendViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: LiveRecommendAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var capturedRoomId: Long? = null
    private var hasLoadedData = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBaseListBinding {
        return FragmentLiveBaseListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        adapter = LiveRecommendAdapter(
            onRoomClick = ::onRoomClick,
            onTopEdgeUp = ::focusTopTab,
            onLeftEdge = ::focusLeftNav
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            onExplicitRefresh()
        }
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        AppLog.d("LivePerf", "LiveRecommendFragment.initData: 触发加载推荐")
        viewModel.loadData()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendData.collectLatest { data ->
                    swipeRefreshLayout?.isRefreshing = false
                    if (data == null && hasLoadedData) return@collectLatest
                    val t0 = System.currentTimeMillis()
                    AppLog.d("LivePerf", "LiveRecommendFragment: 推荐数据到达UI, data=${data != null}")
                    val sections = buildSections(data)
                    if (sections.isEmpty()) return@collectLatest
                    hasLoadedData = true
                    AppLog.d("LivePerf", "LiveRecommendFragment: buildSections完成, section数=${sections.size}, 耗时=${System.currentTimeMillis() - t0}ms")

                    if (sections.size <= FIRST_RENDER_SECTIONS) {
                        prefetchFirstScreenCovers(sections)
                        adapter.setData(sections)
                    } else {
                        // 先渲染前几个可见 section，让首屏尽快显示
                        val firstBatch = sections.take(FIRST_RENDER_SECTIONS)
                        prefetchFirstScreenCovers(firstBatch)
                        adapter.setData(firstBatch)
                        // 首帧渲染后，追加剩余 section
                        binding.recyclerView.post {
                            if (!isAdded || view == null) return@post
                            adapter.setData(sections)
                            AppLog.d("LivePerf", "LiveRecommendFragment: 全部section追加完成, 总耗时=${System.currentTimeMillis() - t0}ms")
                        }
                    }
                    AppLog.d("LivePerf", "LiveRecommendFragment: 首批setData完成, 耗时=${System.currentTimeMillis() - t0}ms")
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.SecondaryTabReselected &&
                        event.host == MainNavigationViewModel.SecondaryTabHost.LIVE &&
                        event.position == 0 &&
                        !viewModel.loading.value
                    ) {
                        onExplicitRefresh()
                    }
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = binding.recyclerView,
            itemCount = adapter.itemCount,
            focusRequester = { holder ->
                (holder as? LiveRecommendAdapter.ViewHolder)?.requestPrimaryFocus() == true
            }
        )
        return result.resolved
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    override fun onReselected() {
        scrollToTop()
    }

    override fun onExplicitRefresh() {
        capturedRoomId = null
        viewModel.loadData(forceRefresh = true)
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        if (adapter.itemCount == 0 || viewModel.shouldRefresh(CACHE_TTL_MS)) {
            viewModel.loadData()
        }
    }

    private fun onRoomClick(room: com.btelly.model.live.LiveRoomItem) {
        LivePlayerActivity.start(requireContext(), room.roomId)
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }

    private fun focusLeftNav(): Boolean {
        return (activity as? com.btelly.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
    }

    override fun onPause() {
        captureFocus()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        restoreFocus()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            restoreFocus()
        }
    }

    override fun onDestroyView() {
        capturedRoomId = null
        super.onDestroyView()
    }

    private fun captureFocus() {
        val focused = activity?.currentFocus ?: return
        capturedRoomId = findFocusedRoomId(focused)
    }

    private fun findFocusedRoomId(focused: View): Long? {
        var view: View? = focused
        while (view != null) {
            val parent = view.parent
            if (parent is RecyclerView) {
                val holder = parent.findContainingViewHolder(view)
                if (holder != null && parent.adapter is LiveRoomAdapter) {
                    val position = holder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        return (parent.adapter as LiveRoomAdapter)
                            .currentList.getOrNull(position)?.roomId
                    }
                }
            }
            view = parent as? View
        }
        return null
    }

    private fun restoreFocus() {
        val roomId = capturedRoomId ?: return
        capturedRoomId = null
        if (!isAdded || view == null) return

        val sections = adapter.currentList
        for ((sectionIndex, section) in sections.withIndex()) {
            val roomIndex = section.rooms.indexOfFirst { it.roomId == roomId }
            if (roomIndex >= 0) {
                binding.recyclerView.post {
                    scrollToSectionAndFocusRoom(sectionIndex, roomIndex)
                }
                return
            }
        }
    }

    private fun scrollToSectionAndFocusRoom(sectionIndex: Int, roomIndex: Int) {
        if (!isAdded || view == null) return
        val sectionHolder = binding.recyclerView.findViewHolderForAdapterPosition(sectionIndex)
        if (sectionHolder is LiveRecommendAdapter.ViewHolder) {
            sectionHolder.focusRoomAt(roomIndex)
            return
        }
        binding.recyclerView.scrollToPosition(sectionIndex)
        binding.recyclerView.post {
            if (!isAdded || view == null) return@post
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(sectionIndex)
            if (holder is LiveRecommendAdapter.ViewHolder) {
                holder.focusRoomAt(roomIndex)
            }
        }
    }

    /**
     * 列表是「纵向多个 section，每个 section 内部 4 列网格」的结构，第一屏通常只能完整显示
     * 第一个 section（4 列），其余 section 上半部分露出 1~2 行。提前把前 [PREFETCH_SECTION_COUNT]
     * 个 section 的前 [PREFETCH_PER_SECTION] 张房间封面塞进 Coil 缓存，
     * 避免列表 onBind 才发起网络请求导致首屏图片延迟。
     */
    private fun prefetchFirstScreenCovers(sections: List<LiveRecommendSection>) {
        if (sections.isEmpty() || !isAdded) return
        val urls = sections.asSequence()
            .take(PREFETCH_SECTION_COUNT)
            .flatMap { it.rooms.asSequence().take(PREFETCH_PER_SECTION) }
            .map { it.cover }
            .toList()
        if (urls.isEmpty()) return
        ImageLoader.prefetchVideoCovers(requireContext(), urls)
    }

    private fun buildSections(data: LiveListWrapper?): List<LiveRecommendSection> {
        if (data == null) {
            return emptyList()
        }

        val sections = mutableListOf<LiveRecommendSection>()
        val hotRooms = ContentFilter.filterLiveRooms(requireContext(), data.recommendRoomList.orEmpty())
        if (hotRooms.isNotEmpty()) {
            sections += LiveRecommendSection(
                title = getString(R.string.hot_live),
                rooms = hotRooms
            )
        }
        sections += data.roomList.orEmpty()
            .mapNotNull { wrapper ->
                val rooms = ContentFilter.filterLiveRooms(requireContext(), wrapper.list.orEmpty())
                if (rooms.isEmpty()) {
                    null
                } else {
                    LiveRecommendSection(
                        title = wrapper.moduleInfo?.title.orEmpty(),
                        rooms = rooms
                    )
                }
            }
        return sections
    }

}
