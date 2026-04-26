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
import com.btelly.ui.fragment.main.MainNavigationViewModel
import com.btelly.core.common.content.ContentFilter
import com.btelly.core.ui.focus.SpatialFocusNavigator
import com.btelly.core.ui.focus.TabContentFocusHelper
import com.btelly.core.ui.refresh.SwipeRefreshHelper
import com.btelly.core.common.ext.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveRecommendFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {
    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        fun newInstance(): LiveRecommendFragment = LiveRecommendFragment()
    }

    private val viewModel: LiveRecommendViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: LiveRecommendAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

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
        viewModel.loadData()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendData.collectLatest { data ->
                    swipeRefreshLayout?.isRefreshing = false
                    val sections = buildSections(data)
                    adapter.setData(sections)
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
