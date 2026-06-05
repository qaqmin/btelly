package com.btelly.feature.player.view

/**
 * Pure PTS math for smart danmaku masking.
 *
 * Matches the reference player model: smart mask timing follows the same player clock as
 * danmaku/interactive layers.
 */
internal object DmMaskPtsEstimator {

    fun estimateFromPlayerClock(
        playerPositionMs: Long,
        @Suppress("UNUSED_PARAMETER") elapsedSinceClockMs: Long,
        @Suppress("UNUSED_PARAMETER") playbackSpeed: Float,
        @Suppress("UNUSED_PARAMETER") isPlaying: Boolean,
    ): Long {
        return playerPositionMs.coerceAtLeast(0L)
    }

    fun estimateFromVideoFrameAnchor(
        @Suppress("UNUSED_PARAMETER") presentationTimeUs: Long,
        @Suppress("UNUSED_PARAMETER") releaseTimeNs: Long,
    ): Long? = null
}
