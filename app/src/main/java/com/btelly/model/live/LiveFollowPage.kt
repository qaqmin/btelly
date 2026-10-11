package com.btelly.model.live

/**
 * 直播关注列表的一页结果。
 *
 * rooms 为该页返回的全部已关注主播（开播与未开播混合，由调用方按 live_status 分组）；
 * hasMore 按「满页即可能还有下一页」的启发式判断（服务端无稳定的分页结束标记）。
 */
data class LiveFollowPage(
    val rooms: List<LiveRoomItem> = emptyList(),
    val hasMore: Boolean = false
)
