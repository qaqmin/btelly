package com.btelly.model

import java.io.Serializable

/**
 * 设置项控件类型。
 *
 * 设置页原来的问题是：63 个设置项共用同一个 cell，渲染结果一律是
 * 「粗体标题 + 灰色当前值 + 右箭头」。于是「清除缓存」（一次性操作）、
 * 「抖音模式」（开关）、「默认启动页面」（多选一）、「仅使用 IPv4」（开关）
 * 四类完全不同的控件长得一模一样，用户扫一眼无法预判点下去会发生什么，
 * 而且每一行都带右箭头——箭头语义只对"会跳转"的行成立，对开关行是误导。
 *
 * 按控件类型分叉渲染后，视觉本身就说明了交互方式。
 */
enum class SettingType {
    /** 开关：标题 + 真实开关控件，OK 就地切换。 */
    TOGGLE,

    /** 多选一：标题 + 当前值 chip，OK 展开选项。 */
    CHOICE,

    /** 一次性操作：强调色按钮，点即执行（清除缓存 / 检查更新）。 */
    ACTION,

    /** 只读信息：弱化样式，无箭头（版本号 / 设备信息）。 */
    INFO,

    /** 数值滑杆：左右键直接调（缓存限制 / 文字大小 / 弹幕速度）。 */
    SLIDER,

    /** 导航：会跳到新页面，保留右箭头。 */
    NAV,
}

data class SettingModel(
    var title: String = "",
    var info: String = "",
    /** 控件类型，决定这一行长什么样、OK 键做什么。默认 [SettingType.CHOICE] 与旧行为一致。 */
    var type: SettingType = SettingType.CHOICE,
    /** 选中态图标资源 id（0 = 不显示图标）。按类型给不同图形，形成可扫读的视觉语言。 */
    var iconRes: Int = 0,
    /** 开关的当前值，仅 [SettingType.TOGGLE] 使用。 */
    var checked: Boolean = false,
) : Serializable
