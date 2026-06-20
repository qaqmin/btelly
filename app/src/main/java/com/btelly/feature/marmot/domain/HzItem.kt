package com.btelly.feature.marmot.domain

import com.google.gson.annotations.SerializedName

/**
 * 画质选项（对标 utao `domain/HzItem`）。
 *
 * 由页面 JS 上报给原生，字段名对齐云端 tv-web 脚本与 utao 参考：
 * - [name]：画质显示名（如「1080P」「蓝光」）
 * - [action]：选中该画质时要执行的 JS（直接 evaluateJavascript）
 * - [isCurrent]：是否为当前画质（高亮显示）。**JS 实际上报的字段名是 `isCurrent`**
 *   （云端 cctv/ysptv detail.js 与 utao HzItem 均用此名）；旧代码误写 `@SerializedName("current")`
 *   导致永远读不到，高亮失效。[current] 作为兼容别名保留。
 * - [id]：画质项 DOM id（如 `resolution_item_720_player`），用于 action 脚本
 * - [level]：画质等级数值
 * - [isVip]：是否 VIP 画质（CCTV 无，部分源有）
 *
 * 注意：当前部分云端 JS（如 cctv 旧版）上报时**不带 isCurrent 字段**，此时 [isCurrent] 为 null，
 * 由调用方（[com.btelly.ui.activity.MarmotLiveActivity]）自行维护选中态。
 */
data class HzItem(
    @SerializedName("name") val name: String? = "",
    @SerializedName("action") val action: String? = "",
    /** 当前画质标记。优先读 `isCurrent`（JS/utao 实际字段名）。 */
    @SerializedName(value = "isCurrent", alternate = ["current"]) val isCurrent: Boolean? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("level") val level: Int? = null,
    @SerializedName(value = "isVip", alternate = ["vip"]) val isVip: Boolean? = null
)
