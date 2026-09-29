package com.btelly.feature.home

import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 全站排行榜 tab（首页第 3 个 tab，位于 推荐/热门 之后）。
 *
 * 与 [HotListFragment] 的差别只在数据源：热门是翻页流，这里一次拿全榜单。
 * 列表渲染、焦点控制、缓存都走 [VideoFeedFragment] 基类，不重复实现。
 */
class RankListFragment : VideoFeedFragment() {

    companion object {
        fun newInstance(): RankListFragment {
            return RankListFragment()
        }
    }

    private val viewModel: RankListViewModel by viewModel()

    override val feedViewModel: VideoFeedViewModel
        get() = viewModel
    override val secondaryTabPosition: Int = 2
    override val toastNonEmptyError: Boolean = true
    override val enableTvListFocusController: Boolean = true
}
