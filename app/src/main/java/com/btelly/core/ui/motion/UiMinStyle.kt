// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import com.btelly.core.common.settings.AppSettingsDataStore

/**
 * 「Min化」动画总开关（设置 → 界面 → Min化，默认开）。
 *
 * 关掉后全站动效（焦点弹簧、过场编排、Hero 转场等）退化为零动画直出，
 * 等价于动效系统的"减少动态效果"无障碍选项。刷新时机与
 * [com.btelly.core.ui.base.UiDisplayMode] 一致：BaseActivity.onCreate 读设置缓存，
 * 设置内切换后 recreate 生效。
 */
object UiMinStyle {

    const val KEY_MIN_STYLE = "min_style"
    const val VALUE_ON = "开"
    const val VALUE_OFF = "关"

    @Volatile
    private var enabled = true

    fun refresh(settings: AppSettingsDataStore) {
        enabled = settings.getCachedString(KEY_MIN_STYLE, VALUE_ON) != VALUE_OFF
    }

    /** 动效是否启用（电视模式/车机模式通用）。 */
    val isEnabled: Boolean
        get() = enabled
}
