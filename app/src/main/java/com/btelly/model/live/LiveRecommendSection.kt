package com.btelly.model.live

data class LiveRecommendSection(
    val title: String,
    val rooms: List<LiveRoomItem>,
    /** 「查看更多」落地页链接（module_info.link）；空表示无全量页。 */
    val link: String = ""
)
