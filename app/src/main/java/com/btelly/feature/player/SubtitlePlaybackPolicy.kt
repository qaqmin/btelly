package com.btelly.feature.player

import com.btelly.model.subtitle.SubtitleInfoModel

internal fun isSubtitleRequestCurrent(
    requestCid: Long,
    requestBvid: String?,
    currentCid: Long,
    currentBvid: String?
): Boolean = requestCid == currentCid && requestBvid == currentBvid

internal fun trustedPlayerInfoSubtitleTracks(
    detailTracks: List<SubtitleInfoModel>,
    playerInfoTracks: List<SubtitleInfoModel>
): List<SubtitleInfoModel> {
    if (detailTracks.isEmpty()) return playerInfoTracks

    val detailTrackKeys = detailTracks
        .filter { it.id > 0L }
        .map { it.id to it.lan }
        .toSet()
    return playerInfoTracks.filter { track ->
        track.id > 0L && (track.id to track.lan) in detailTrackKeys
    }
}
