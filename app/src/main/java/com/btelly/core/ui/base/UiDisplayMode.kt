package com.btelly.core.ui.base

import com.btelly.core.common.settings.AppSettingsDataStore

/**
 * 全局显示模式（电视 / 车机）。车机模式放大卡片等自绘文字，提高车机场景可读性。
 *
 * 与 [UiTextScale] 同一刷新机制：BaseActivity.onCreate 时从设置缓存刷新（内存读，无 IO）。
 * 设置项「显示模式」写入 KEY_DISPLAY_MODE，改后 recreate 生效。
 */
object UiDisplayMode {

    const val KEY_DISPLAY_MODE = "display_mode"
    const val MODE_TV = 0
    const val MODE_CAR = 1

    /** 车机模式文字放大系数。 */
    const val CAR_BOOST = 1.25f

    @Volatile
    private var cachedMode = MODE_TV

    fun refresh(settings: AppSettingsDataStore) {
        cachedMode = settings.getCachedInt(KEY_DISPLAY_MODE, MODE_TV)
    }

    val isCarMode: Boolean
        get() = cachedMode == MODE_CAR

    /** 当前模式的文字/间距放大系数（电视 = 1.0）。 */
    val boost: Float
        get() = if (isCarMode) CAR_BOOST else 1f
}
