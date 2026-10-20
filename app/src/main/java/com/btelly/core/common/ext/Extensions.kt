package com.btelly.core.common.ext

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.btelly.core.common.settings.AppSettingsDataStore
import com.btelly.core.ui.motion.UiToastMin
import org.koin.mp.KoinPlatform

private const val VALUE_ON = "开"
private const val VALUE_OFF = "关"
private const val VALUE_FILTER_LEVEL_MIN = 1
private const val VALUE_FILTER_LEVEL_MAX = 10
private val appSettings: AppSettingsDataStore
    get() = KoinPlatform.getKoin().get()

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    UiToastMin.show(this, message, duration)
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    val text = runCatching { getString(resId) }.getOrNull()
    if (text.isNullOrBlank()) return
    UiToastMin.show(this, text, duration)
}

fun isOpenDetailFirstEnabled(): Boolean {
    // 默认开启：点视频卡片进详情页（Hero 转场），从详情页点播放再进播放器。
    // 之前默认关着，卡片直进播放器导致详情页不可达；需要旧行为的用户可在设置里关闭。
    return getToggleSetting("show_video_detail", true)
}

fun getDanmakuSmartFilterLevel(): Int {
    val raw = appSettings.getCachedString("dm_filter_weight")?.trim() ?: return 0
    val parsed = raw.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return parsed
    }
    return when (raw) {
        "低" -> 1
        "中" -> 5
        "高" -> 10
        VALUE_ON -> 1
        else -> 0
    }
}

fun normalizeDanmakuSmartFilterValue(value: String?): String {
    val trimmed = value?.trim()
    val parsed = trimmed?.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return trimmed
    }
    return when (trimmed) {
        "低" -> "1"
        "中" -> "5"
        "高" -> "10"
        VALUE_ON -> "1"
        else -> VALUE_OFF
    }
}

fun isVipColorfulDanmakuAllowed(): Boolean {
    return getToggleSetting("dm_allow_vip_colorful_dm", true)
}

fun getHomeDefaultStartPageIndex(maxIndex: Int, defaultIndex: Int = 1): Int {
    val safeMaxIndex = maxIndex.coerceAtLeast(0)
    val clampedDefault = defaultIndex.coerceIn(0, safeMaxIndex)
    // 首页 tab 顺序：推荐(0) / 热门(1) / 排行榜(2) / 番剧(3) / 影视(4)。
    // 排行榜是在「热门」之后插入的，所以「番剧」「影视」的下标相对插入前各 +1。
    // 这里按标签名映射而不是按旧下标，插 tab 才不会让用户已保存的设置指错页面。
    val mappedFromLabel = when (appSettings.getCachedString("default_start_page")?.trim()) {
        "推荐" -> 0
        "热门" -> 1
        "排行榜" -> 2
        "番剧" -> 3
        "影视" -> 4
        "动态" -> clampedDefault
        else -> null
    }
    if (mappedFromLabel != null) {
        val normalized = mappedFromLabel.coerceIn(0, safeMaxIndex)
        if (appSettings.getCachedInt("defaultStartPage", Int.MIN_VALUE) != normalized) {
            appSettings.putIntAsync("defaultStartPage", normalized)
        }
        return normalized
    }
    return appSettings.getCachedInt("defaultStartPage", clampedDefault)
        .coerceIn(0, safeMaxIndex)
}

private fun getToggleSetting(key: String, defaultValue: Boolean): Boolean {
    val fallback = if (defaultValue) VALUE_ON else VALUE_OFF
    return (appSettings.getCachedString(key) ?: fallback) == VALUE_ON
}
