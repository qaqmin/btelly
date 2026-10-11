package com.btelly.model.live

/**
 * 直播关注列表（xlive/web-ucenter/user/following）的一页结果。
 *
 * rooms 为该页返回的全部已关注主播（开播/未开播混合，由调用方按 live_status 分组）；
 * liveCount/totalCount 为服务端直接下发的统计值；hasMore 由 totalPage 判断。
 */
data class LiveFollowPage(
    val rooms: List<LiveRoomItem> = emptyList(),
    val liveCount: Int = 0,
    val totalCount: Int = 0,
    val hasMore: Boolean = false
)
