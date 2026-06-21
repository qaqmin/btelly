package com.btelly.feature.marmot.quality

import com.btelly.feature.marmot.domain.HzItem

/**
 * CCTV 直播画质 Provider（方案 C 第一个实现）。
 *
 * 对标 v1.5.8 `CctvPlayerActivity`：CCTV HLS 直播是单流，**运行时页面无可切换的画质 DOM**
 * （实测 `#player_resolution_bar_player` 不存在，`[itemvalue]` 匹配到的是音效按钮）。
 * 因此画质写死 4 档（1080/720/540/360），切换靠 createLivePlayer 的 `br` 参数重建播放器。
 *
 * 当前画质状态保存在本对象（[current]），切台/重建时由 Activity 读取 [current].br 传入 createLivePlayer。
 *
 * @param onSwitch 切画质时的回调（Activity 用当前 [current].br 重建 createLivePlayer）
 */
class CctvQualityProvider(
    private val onSwitch: () -> Unit
) : LiveQualityProvider {

    /** CCTV 画质档位（label 显示名 + br 传给 createLivePlayer 的码率参数）。对标 v1.5.8 CctvQuality。 */
    enum class Quality(val label: String, val br: String) {
        P1080("1080P", "1080"),
        P720("720P", "720"),
        P540("540P", "540"),
        P360("360P", "360")
    }

    /** 当前画质（createLivePlayer 的 br 参数来源）。默认最高档。 */
    var current: Quality = Quality.P1080
        private set

    override fun availableQualities(): List<HzItem> =
        Quality.values().map { HzItem(name = it.label, level = it.br.toIntOrNull()) }

    override fun currentQualityIndex(items: List<HzItem>): Int =
        items.indexOfFirst { it.name == current.label }.coerceAtLeast(0)

    override fun switchTo(item: HzItem, onSwitched: () -> Unit): Boolean {
        val next = Quality.values().firstOrNull { it.label == item.name } ?: return false
        if (next == current) return false
        current = next
        onSwitch()       // Activity 用 current.br 重建 createLivePlayer
        onSwitched()
        return true
    }
}
