@file:Suppress("unused")

package com.btelly.feature.player

import com.btelly.model.player.PlayInfoModel
import com.btelly.model.video.quality.VideoCodecEnum

data class SessionIdentity(
    val aid: Long?,
    val bvid: String?,
    val cid: Long,
    val epId: Long?
)

data class VideoPlaybackSession(
    val identity: SessionIdentity,
    val requestedQualityId: Int?,
    val requestedAudioId: Int?,
    val requestedCodec: VideoCodecEnum?,
    val actualQualityId: Int?,
    val actualAudioId: Int?,
    val actualCodec: VideoCodecEnum?,
    val playInfo: PlayInfoModel?,
    val routePlan: DashRoutePlan?,
    val currentRoute: DashRoute?,
    val expiresAtMs: Long,
    val fallbackRouteIndex: Int = 0,
    val fallbackCdnIndex: Int = 0,
    val fallbackAttemptCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
