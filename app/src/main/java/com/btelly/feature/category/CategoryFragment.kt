package com.btelly.feature.category

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.btelly.R
import com.btelly.databinding.FragmentCategoryBinding
import com.btelly.model.CategoryModel
import com.btelly.core.ui.base.BaseFragment
import com.btelly.ui.fragment.main.MainNavigationViewModel
import com.btelly.ui.fragment.main.MainTabFocusTarget
import com.btelly.core.ui.tab.enableTouchNavigation
import com.btelly.core.ui.tab.focusNearestTabTo
import com.btelly.core.ui.tab.disableAdjacentPagePrefetch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoryFragment : BaseFragment<FragmentCategoryBinding>(), MainTabFocusTarget {

    companion object {
        fun newInstance(): CategoryFragment = CategoryFragment()
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: CategoryFragmentAdapter
    private val categories = mutableListOf<CategoryModel>()
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCategoryBinding {
        return FragmentCategoryBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager

        adapter = CategoryFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.disableAdjacentPagePrefetch()
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        applyChipTabs()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPagePrimaryContent,
            onTabReselected = {
                adapter.getCurrentFragment(viewPager.currentItem)?.refresh()
            }
        )
        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                notifyTabSelected(position)
            }
        }.also { viewPager.registerOnPageChangeCallback(it) }

        initCategories()
    }

    /**
     * tab chip 化（P1-5，与首页同语言）：
     * - 文字色走主题令牌（color selector 不支持 ?attr，需代码 setTabTextColors）；
     * - chip 背景必须设在 tab view 自己身上：app:tabBackground 会被 Material 收进
     *   baseBackgroundDrawable 由 TabLayout.onDraw 手绘，该管线在真机上失焦/切换选中后
     *   不刷新（当贝 H5/Android 12 实测 chip 残留高亮）；普通 view 背景走平台标准 state
     *   管线才可靠。TabLayout 无 tabMargin 属性，逐个 tab view 补 marginStart。
     */
    private fun applyChipTabs() {
        tabLayout.setTabTextColors(
            resolveThemeColor(R.attr.tvTextSecondary),
            resolveThemeColor(R.attr.tvAccent)
        )
        val tabMargin = resources.getDimensionPixelSize(R.dimen.home_tab_margin)
        for (i in 0 until tabLayout.tabCount) {
            val tabView = tabLayout.getTabAt(i)?.view ?: continue
            tabView.setBackgroundResource(R.drawable.bg_chip)
            (tabView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = if (i == 0) 0 else tabMargin
                tabView.layoutParams = lp
            }
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typed = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, typed, true)) typed.data else 0
    }

    private fun initCategories() {
        categories.clear()
        val resources = requireContext().resources

        categories.add(CategoryModel(0, resources.getString(R.string.allWeb)))
        categories.add(CategoryModel(1, resources.getString(R.string.cartoon)))
        categories.add(CategoryModel(3, resources.getString(R.string.music)))
        categories.add(CategoryModel(129, resources.getString(R.string.dance)))
        categories.add(CategoryModel(4, resources.getString(R.string.game)))
        categories.add(CategoryModel(36, resources.getString(R.string.knowledge)))
        categories.add(CategoryModel(188, resources.getString(R.string.science)))
        categories.add(CategoryModel(234, resources.getString(R.string.sport)))
        categories.add(CategoryModel(223, resources.getString(R.string.auto)))
        categories.add(CategoryModel(160, resources.getString(R.string.lifestyle)))
        categories.add(CategoryModel(211, resources.getString(R.string.food)))
        categories.add(CategoryModel(217, resources.getString(R.string.pet)))
        categories.add(CategoryModel(119, resources.getString(R.string.kichiku)))
        categories.add(CategoryModel(155, resources.getString(R.string.fashion)))
        categories.add(CategoryModel(5, resources.getString(R.string.entainment)))
        categories.add(CategoryModel(181, resources.getString(R.string.film_and_television)))
        categories.add(CategoryModel(177, resources.getString(R.string.documentary)))
        categories.add(CategoryModel(23, resources.getString(R.string.movie)))
        categories.add(CategoryModel(11, resources.getString(R.string.series)))

        adapter.setCategories(categories)
        
        viewPager.currentItem = 0
    }

    override fun initData() {
        binding.viewPager.post {
            adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 1) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 1) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.refresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            adapter.getCurrentFragment(viewPager.currentItem)?.refresh()

                        MainNavigationViewModel.Event.BackPressed -> Unit

                        else -> Unit
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
    }

    private fun notifyTabSelected(position: Int, retries: Int = 5) {
        val fragment = adapter.getCurrentFragment(position)
        if (fragment != null) {
            fragment.onTabSelected()
        } else if (retries > 0) {
            viewPager.post { notifyTabSelected(position, retries - 1) }
        }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { viewPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry && anchorView != null && isVerticallyAlignedWith(anchorView, tabLayout)) {
            if (focusCurrentTab(anchorView)) return true
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry) ||
            focusCurrentTab(anchorView)
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentListFragment()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun getCurrentListFragment(): CategoryListFragment? {
        val currentItem = viewPager.currentItem
        val itemId = adapter.getItemId(currentItem)
        val fragmentTag = "f$itemId"
        childFragmentManager.findFragmentByTag(fragmentTag)?.let { return it as? CategoryListFragment }
        for (fragment in childFragmentManager.fragments) {
            if (fragment is CategoryListFragment && fragment.isVisible) {
                return fragment
            }
        }
        return null
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }
}
