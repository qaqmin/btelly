// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.ui.motion

import com.btelly.core.common.settings.AppSettingsDataStore

/**
 * 「Min化」动画总开关（设置 → 界面 → Min化，默认开）。
 *
 * 关掉后全站动效（焦点弹簧、过场编排、Hero 转场等）退化为零动画直出，
 * 等价于动效系统的"减少动态效果"无障碍选项。
 *
 * 读取策略：懒读。设置缓存由 Application 异步加载（不卡首帧），onCreate 里
 * 同步 refresh 会race 读到默认值；因此 [isEnabled] 每次现读缓存（ ConcurrentHashMap
 * 取值，焦点事件级调用成本可忽略），缓存未就绪时自愈（ensureCacheReady 兜底加载）。
 */
object UiMinStyle {

    const val KEY_MIN_STYLE = "min_style"
    const val VALUE_ON = "开"
    const val VALUE_OFF = "关"

    @Volatile
    private var settings: AppSettingsDataStore? = null

    /** BaseActivity.onCreate 调用，注入设置数据源（不再快照值）。 */
    fun refresh(source: AppSettingsDataStore) {
        settings = source
    }

    /** 动效是否启用（电视模式/车机模式通用）。 */
    val isEnabled: Boolean
        get() = settings?.getCachedString(KEY_MIN_STYLE, VALUE_ON) != VALUE_OFF
}
