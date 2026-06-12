package com.btelly.feature.cctv

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.btelly.R
import com.btelly.core.ui.base.BaseFragment
import com.btelly.core.ui.decoration.GridSpacingItemDecoration
import com.btelly.core.ui.layout.WrapContentGridLayoutManager
import com.btelly.databinding.FragmentCctvLiveBinding
import com.btelly.ui.activity.CctvPlayerActivity
import com.btelly.ui.fragment.main.MainTabFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject

class CctvLiveFragment : BaseFragment<FragmentCctvLiveBinding>(), MainTabFocusTarget {

    private val okHttpClient: OkHttpClient by inject()
    private lateinit var adapter: CctvChannelAdapter
    private val programRepository by lazy { CctvProgramRepository(okHttpClient) }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCctvLiveBinding {
        return FragmentCctvLiveBinding.inflate(inflater, container, false)
    }

    override fun useLightBaseContainer(): Boolean = true

    override fun initView() {
        adapter = CctvChannelAdapter(
            onItemClick = ::openChannel,
            onTopEdgeUp = { false }
        )
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), SPAN_COUNT)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                SPAN_COUNT,
                resources.getDimensionPixelSize(R.dimen.px20),
                true
            )
        )
    }

    override fun initData() {
        CctvWebViewPrewarmer.prewarm(requireContext())
        loadNowPrograms()
        binding.recyclerView.post {
            focusEntryFromMainTab()
        }
    }

    override fun onStop() {
        CctvWebViewPrewarmer.cancelIfPending()
        super.onStop()
    }

    private fun loadNowPrograms() {
        viewLifecycleOwner.lifecycleScope.launch {
            val programs = withContext(Dispatchers.IO) {
                programRepository.fetchNowPrograms(CctvChannels.list())
            }
            if (programs.isNotEmpty()) {
                adapter.submitNowPrograms(programs)
            }
        }
    }

    override fun focusEntryFromMainTab(): Boolean {
        val layoutManager = binding.recyclerView.layoutManager ?: return false
        val view = layoutManager.findViewByPosition(0)
        if (view != null) {
            return view.requestFocus()
        }
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.post {
            binding.recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
        return true
    }

    private fun openChannel(position: Int) {
        val intent = Intent(requireContext(), CctvPlayerActivity::class.java)
            .putExtra(CctvPlayerActivity.EXTRA_CHANNEL_INDEX, position)
        startActivity(intent)
    }

    companion object {
        private const val SPAN_COUNT = 4

        fun newInstance(): CctvLiveFragment = CctvLiveFragment()
    }
}
