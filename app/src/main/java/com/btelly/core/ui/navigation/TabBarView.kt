// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.navigation

import com.btelly.core.ui.focus.attachFocusSpring
import android.content.Context
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.btelly.R
import com.btelly.core.ui.base.ScaledTextView
import com.btelly.core.ui.base.UiCarSpacing
import com.btelly.core.ui.base.UiDisplayMode
import com.btelly.core.ui.base.UiTextScale
import com.btelly.core.ui.image.AvatarBadgeView
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.ui.focus.SpatialFocusNavigator

/**
 * UI 2.0 导航轨（见 docs/ui-redesign-plan.md 第四节）。
 *
 * 结构：品牌头 + 图标标签横排导航项（激活项带左侧指示条）+ 底部头像/设置。
 * 公开 API 与按键行为与此前一致，MainActivity 无需改动；颜色走主题令牌
 * （tvAccent / tvTextTertiary），七套皮肤自动跟随。
 */
class TabBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 一个导航项：可聚焦容器 + 图标 + 标签 + 激活指示条。 */
    private class NavItem(
        val container: View,
        val icon: ImageView,
        val label: TextView,
        val indicator: View
    )

    private val navSearch: NavItem
    private val navHome: NavItem
    private val navCategory: NavItem
    private val navDynamic: NavItem
    private val navLive: NavItem
    private val navCctvLive: NavItem
    private val navMe: NavItem
    private val navSetting: NavItem

    private val imageAvatar: AvatarBadgeView
    private val navAvatar: View

    /** tab 顺序契约（MainActivity 的 tab 索引）：首页/分区/动态/直播/CCTV/我的/搜索。 */
    private val tabButtons: List<NavItem>

    private var currentSelectedIndex = -1
    private var onTabClickListener: OnTabClickListener? = null

    private val selectedColor: Int
    private val normalColor: Int

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_tab_bar, this, true)
        // 车机模式间距铺开：轨内边距、导航项触控高度与项间距乘 boost（电视模式零改动）
        UiCarSpacing.apply(view)
        // 焦点弹簧溢出需要父级不裁剪（导航项放大才可见）
        clipChildren = false
        clipToPadding = false
        navSearch = navItem(view, R.id.nav_search, R.id.button_search, R.id.label_search, R.id.indicator_search)
        navHome = navItem(view, R.id.nav_home, R.id.button_home, R.id.label_home, R.id.indicator_home)
        navCategory = navItem(view, R.id.nav_category, R.id.button_category, R.id.label_category, R.id.indicator_category)
        navDynamic = navItem(view, R.id.nav_dynamic, R.id.button_dynamic, R.id.label_dynamic, R.id.indicator_dynamic)
        navLive = navItem(view, R.id.nav_live, R.id.button_live, R.id.label_live, R.id.indicator_live)
        navCctvLive = navItem(view, R.id.nav_cctv_live, R.id.button_cctv_live, R.id.label_cctv_live, R.id.indicator_cctv_live)
        navMe = navItem(view, R.id.nav_me, R.id.button_me, R.id.label_me, R.id.indicator_me)
        navSetting = navItem(view, R.id.nav_setting, R.id.button_setting, R.id.label_setting, 0)
        navAvatar = view.findViewById(R.id.nav_avatar)
        imageAvatar = view.findViewById(R.id.image_avatar)

        tabButtons = listOf(navHome, navCategory, navDynamic, navLive, navCctvLive, navMe, navSearch)

        selectedColor = resolveThemeColor(R.attr.tvAccent, context.getColor(R.color.tv_accent))
        normalColor = resolveThemeColor(R.attr.tvTextTertiary, context.getColor(R.color.tv_text_tertiary))

        tabButtons.forEach { applyTabColor(it, false) }
        applyTabColor(navSetting, false)

        // 焦点弹簧（动效 Phase 1）：导航项聚焦放大回弹（轨无业务 focus 监听，attach 安全）
        tabButtons.forEach { it.container.attachFocusSpring() }
        navSetting.container.attachFocusSpring()

        setupClickListeners()
    }

    private fun navItem(
        root: View,
        containerId: Int,
        iconId: Int,
        labelId: Int,
        indicatorId: Int
    ): NavItem {
        val container = root.findViewById<View>(containerId)
        val icon = root.findViewById<ImageView>(iconId)
        val label = root.findViewById<TextView>(labelId)
        if (label !is ScaledTextView) {
            // 自定义 View 内部 inflate 可能不经过 UiTextScaleFactory（如 AsyncLayoutInflater 预 inflate 路径），
            // 标签此时不会跟随全局文字缩放，按轨基准字号手动补齐一次（含车机 boost，与 ScaledTextView 一致）
            label.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.rail_label_size) * UiTextScale.scale() * UiDisplayMode.boost
            )
        }
        val indicator = if (indicatorId != 0) root.findViewById<View>(indicatorId) else container
        return NavItem(container, icon, label, indicator)
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true) && typedValue.data != 0) {
            typedValue.data
        } else {
            fallback
        }
    }

    private fun applyTabColor(item: NavItem, isSelected: Boolean) {
        val color = if (isSelected) selectedColor else normalColor
        item.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        item.label.setTextColor(color)
        item.container.isSelected = isSelected
        if (item !== navSetting) {
            item.indicator.visibility = if (isSelected) VISIBLE else GONE
        }
    }

    private fun setupClickListeners() {
        tabButtons.forEachIndexed { index, item ->
            item.container.setOnClickListener {
                selectTab(index)
            }
            item.container.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onTabClickListener?.onTabNavigateRight(index) == true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        item === navSearch
                    }
                    else -> false
                }
            }
        }

        navSetting.container.setOnClickListener {
            onTabClickListener?.onSettingClick()
        }
        navSetting.container.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> onTabClickListener?.onSideButtonNavigateRight() ?: false
                else -> false
            }
        }

        navAvatar.setOnClickListener {
            onTabClickListener?.onAvatarClick()
        }
        navAvatar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> onTabClickListener?.onSideButtonNavigateRight() ?: false
                else -> false
            }
        }
    }

    fun selectTab(index: Int) {
        com.btelly.core.common.log.AppLog.d(
            "FocusTrace",
            "selectTab index=$index currentSelectedIndex=$currentSelectedIndex"
        )
        if (index < 0 || index >= tabButtons.size) return
        if (index == 1 && navCategory.container.visibility == GONE) return
        if (index == 3 && navLive.container.visibility == GONE) return
        if (index == 4 && navCctvLive.container.visibility == GONE) return
        if (currentSelectedIndex == index) {
            if (index == 6) {
                onTabClickListener?.onSearchClick()
            } else {
                onTabClickListener?.onTabReselected(index)
            }
            return
        }

        tabButtons.getOrNull(currentSelectedIndex)?.let { applyTabColor(it, false) }
        currentSelectedIndex = index
        applyTabColor(tabButtons[currentSelectedIndex], true)

        if (index == 6) {
            onTabClickListener?.onSearchClick()
        } else {
            onTabClickListener?.onTabSelected(index)
        }
    }

    fun setOnTabClickListener(listener: OnTabClickListener) {
        this.onTabClickListener = listener
    }

    fun setLiveButtonVisible(visible: Boolean) {
        navLive.container.visibility = if (visible) VISIBLE else GONE
    }

    fun isLiveButtonVisible(): Boolean {
        return navLive.container.visibility == VISIBLE
    }

    fun setCctvLiveButtonVisible(visible: Boolean) {
        navCctvLive.container.visibility = if (visible) VISIBLE else GONE
    }

    fun isCctvLiveButtonVisible(): Boolean {
        return navCctvLive.container.visibility == VISIBLE
    }

    fun setCategoryButtonVisible(visible: Boolean) {
        navCategory.container.visibility = if (visible) VISIBLE else GONE
    }

    fun isCategoryButtonVisible(): Boolean {
        return navCategory.container.visibility == VISIBLE
    }

    fun showAvatar(show: Boolean) {
        navAvatar.visibility = if (show) VISIBLE else GONE
    }

    fun setAvatarUrl(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            ImageLoader.loadDrawableRes(
                imageView = imageAvatar,
                resId = R.drawable.default_avatar,
                circleCrop = true
            )
            return
        }

        ImageLoader.loadCircle(
            imageView = imageAvatar,
            url = avatarUrl,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
    }

    fun setAvatarBadge(officialVerifyType: Int, vipStatus: Int = 0, vipType: Int = 0, vipAvatarSubscript: Int = 0) {
        imageAvatar.setBadge(officialVerifyType, vipStatus, vipType, vipAvatarSubscript)
    }

    fun getCurrentSelectedIndex(): Int = currentSelectedIndex

    fun restoreTabHighlight(index: Int) {
        if (index < 0 || index >= tabButtons.size) return
        tabButtons.getOrNull(currentSelectedIndex)?.let { applyTabColor(it, false) }
        currentSelectedIndex = index
        applyTabColor(tabButtons[currentSelectedIndex], true)
    }

    fun focusCurrentTab(): Boolean {
        return tabButtons.getOrNull(currentSelectedIndex)?.container?.requestFocus() == true
    }

    fun focusNearestButtonTo(anchorView: View?): Boolean {
        val candidates = buildList {
            addAll(tabButtons.map { it.container })
            add(navAvatar)
            add(navSetting.container)
        }.filter { it.visibility == VISIBLE && it.isFocusable }
        if (candidates.isEmpty()) {
            return false
        }
        return SpatialFocusNavigator.requestBestCandidate(
            anchorView = anchorView,
            candidates = candidates,
            direction = View.FOCUS_LEFT,
            fallback = {
                focusCurrentTab() || candidates.firstOrNull()?.requestFocus() == true
            }
        )
    }

    interface OnTabClickListener {
        fun onTabSelected(index: Int)
        fun onTabReselected(index: Int)
        fun onTabNavigateRight(index: Int): Boolean
        fun onSideButtonNavigateRight(): Boolean
        fun onSearchClick()
        fun onSettingClick()
        fun onAvatarClick()
    }
}
