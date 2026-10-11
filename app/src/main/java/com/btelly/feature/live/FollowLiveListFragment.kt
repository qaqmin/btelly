package com.btelly.feature.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btelly.core.ui.base.BaseFragment
import com.btelly.core.ui.base.adaptiveSpanCount
import com.btelly.core.ui.layout.WrapContentGridLayoutManager
import com.btelly.core.common.ext.toast
import com.btelly.databinding.FragmentFollowLiveListBinding
import com.btelly.feature.detail.UserSpaceFragment
import com.btelly.model.live.LiveRoomItem
import com.btelly.ui.activity.LivePlayerActivity
import com.btelly.ui.activity.MainActivity
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 「我的关注」直播页：分组展示全部已关注主播。
 *
 * 正在直播（live_status != 0）→ 回车进直播间；未开播（live_status == 0）→ 回车进个人空间。
 * 通过 MainActivity.openInHostContainer 从直播页「我的关注」分区标题进入。
 */
class FollowLiveListFragment : BaseFragment<FragmentFollowLiveListBinding>() {

    companion object {
        fun newInstance(): FollowLiveListFragment = FollowLiveListFragment()
    }

    private val viewModel: FollowLiveViewModel by viewModel()

    private lateinit var liveHeaderAdapter: LiveFollowHeaderAdapter
    private lateinit var offlineHeaderAdapter: LiveFollowHeaderAdapter
    private lateinit var liveRoomAdapter: LiveRoomAdapter
    private lateinit var offlineRoomAdapter: LiveRoomAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var focusRequested = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentFollowLiveListBinding {
        return FragmentFollowLiveListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        liveHeaderAdapter = LiveFollowHeaderAdapter("正在直播 (0)")
        offlineHeaderAdapter = LiveFollowHeaderAdapter("未开播 (0)")
        liveRoomAdapter = LiveRoomAdapter(onItemClick = ::onRoomClick)
        offlineRoomAdapter = LiveRoomAdapter(onItemClick = ::onRoomClick)

        val spanCount = resources.adaptiveSpanCount()
        val layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // 0 = 直播分组标题；liveAdapter.itemCount + 1 = 未开播分组标题
                return if (position == 0 || position == liveRoomAdapter.itemCount + 1) spanCount else 1
            }
        }

        concatAdapter = ConcatAdapter(liveHeaderAdapter, liveRoomAdapter)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = concatAdapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val total = concatAdapter.itemCount
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= total - 4) viewModel.loadMore()
            }
        })

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    override fun initData() {
        viewModel.refresh()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }
    }

    private fun render(state: FollowLiveViewModel.State) {
        liveHeaderAdapter.update("正在直播 (${state.liveRooms.size})")
        offlineHeaderAdapter.update("未开播 (${state.offlineRooms.size})")
        liveRoomAdapter.setData(state.liveRooms)
        offlineRoomAdapter.setData(state.offlineRooms)

        // 未开播组懒挂载：一个未开播主播都没有时不显示该分组
        val hasOffline = state.offlineRooms.isNotEmpty()
        if (hasOffline && concatAdapter.adapters.size < 4) {
            concatAdapter.addAdapter(offlineHeaderAdapter)
            concatAdapter.addAdapter(offlineRoomAdapter)
        }

        val hasContent = state.liveRooms.isNotEmpty() || state.offlineRooms.isNotEmpty()
        binding.recyclerView.isVisible = !state.loading && state.error == null && hasContent
        binding.tvMessage.isVisible = state.loading || state.error != null || !hasContent
        binding.tvMessage.text = when {
            state.loading -> "加载中…"
            state.error != null -> "加载失败：${state.error}"
            else -> "还没有关注任何主播，去直播间关注一个吧"
        }
        binding.tvSummary.text =
            "正在直播 ${state.liveRooms.size} · 未开播 ${state.offlineRooms.size}"

        if (!state.loading && hasContent && !focusRequested) {
            focusRequested = true
            binding.recyclerView.post {
                binding.recyclerView.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus()
                    ?: binding.recyclerView.requestFocus()
            }
        }
    }

    private fun onRoomClick(room: LiveRoomItem) {
        if (room.liveStatus != 0) {
            LivePlayerActivity.start(requireContext(), room.roomId)
        } else if (room.uid > 0) {
            (activity as? MainActivity)?.openInHostContainer(UserSpaceFragment.newInstance(room.uid))
        } else {
            context.toast("该主播未开播")
        }
    }
}
