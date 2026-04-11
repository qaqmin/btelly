package com.btelly.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.btelly.databinding.FragmentLiveBaseListBinding
import com.btelly.model.live.LiveAreaCategory
import com.btelly.model.live.LiveAreaCategoryParent
import com.btelly.ui.base.BaseFragment
import com.btelly.ui.base.RecyclerViewFocusRestoreHelper
import com.btelly.ui.view.WrapContentGridLayoutManager
import com.btelly.utils.AppLog
import com.btelly.utils.SpatialFocusNavigator
import com.btelly.utils.serializableCompat
import androidx.recyclerview.widget.RecyclerView

class LiveAreaFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {

    companion object {
        private const val TAG = "MainEntryFocus"
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: LiveAreaCategoryParent): LiveAreaFragment {
            return LiveAreaFragment().apply {
                arguments = bundleOf(ARG_CATEGORY to category)
            }
        }
    }

    private lateinit var adapter: LiveAreaAdapter
    private var category: LiveAreaCategoryParent? = null

    override fun initArguments() {
        category = arguments?.serializableCompat(ARG_CATEGORY)
        AppLog.e("[DM_DIAG]", "AreaFrag.initArguments: category=${category?.name ?: "null"}, areaList=${category?.areaList?.size ?: "null"}")
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBaseListBinding {
        return FragmentLiveBaseListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        adapter = LiveAreaAdapter(
            onItemClick = ::openAreaDetail,
            onTopEdgeUp = ::focusTopTab
        )
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), 8)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        val areaList = category?.areaList.orEmpty()
        AppLog.e("[DM_DIAG]", "AreaFrag.initData: category=${category?.name ?: "null"}, areaList=${areaList.size}")
        if (areaList.isNotEmpty()) {
            AppLog.e("[DM_DIAG]", "AreaFrag: firstArea id=${areaList[0].id} name=${areaList[0].name} title=${areaList[0].title} pic=${areaList[0].pic.take(30)}")
        } else {
            AppLog.e("[DM_DIAG]", "AreaFrag: areaList is EMPTY! category=${category?.name}, category.id=${category?.id}, areaList field=${category?.areaList}")
        }
        adapter.setData(areaList)
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent failed: itemCount=${adapter.itemCount}")
            return false
        }
        val lm = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            binding.recyclerView.findViewHolderForAdapterPosition(firstVisible)?.itemView?.let {
                val handled = it.requestFocus()
                AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent firstVisible=$firstVisible handled=$handled")
                return handled
            }
        }
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerView,
            position = 0
        )
        AppLog.d(
            TAG,
            "LiveAreaFragment.focusPrimaryContent deferred: handled=${result.handled} deferred=${result.deferred}"
        )
        return true
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent spatialEntry: handled=$handled")
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    private fun openAreaDetail(area: LiveAreaCategory) {
        val areaId = area.areaV2Id.takeIf { it > 0 } ?: area.id
        val parentAreaId = area.areaV2ParentId.takeIf { it > 0 } ?: area.parentId
        val title = area.title.ifBlank { area.name }
        openInHostContainer(
            LiveListFragment.newAreaInstance(
                areaId = areaId,
                parentAreaId = parentAreaId,
                title = title
            )
        )
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }
}
