package com.btelly.feature.marmot.domain

import com.google.gson.annotations.SerializedName

/**
 * 画质选项（对标参考 utao `domain/HzItem`）。
 *
 * 由页面 JS 通过 `_api.message("videoQuality", data)` 上报给原生，
 * 数据格式为数组 JSON：`[{name, action, current}]`。
 * - [name]：画质显示名（如「1080P」「蓝光」）
 * - [action]：选中该画质时要执行的 JS（直接 evaluateJavascript）
 * - [current]：是否为当前画质（高亮显示）
 */
data class HzItem(
    @SerializedName("name") val name: String? = "",
    @SerializedName("action") val action: String? = "",
    @SerializedName("current") val current: Boolean? = false
)
