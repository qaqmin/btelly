package com.btelly.feature.player.view

/**
 * Pure PTS math for smart danmaku masking.
 *
 * ExoPlayer's video-frame anchor is already scheduled against the video Surface clock, so the
 * mask only needs to compensate for the extra UI-layer delay over the video Surface.
 */
internal object DmMaskPtsEstimator {

    fun estimateFromAnchor(
        anchorPtsMs: Long,
        anchorAgeMs: Long,
        pipelineDelayMs: Long,
        vsyncPeriodMs: Long,
        playbackSpeed: Float,
        anchorIntervalMs: Long,
    ): Long {
        val safeSpeed = if (playbackSpeed.isFinite() && playbackSpeed > 0f) {
            playbackSpeed.toDouble()
        } else {
            1.0
        }
        val relativeDelayMs = (pipelineDelayMs - vsyncPeriodMs).coerceAtLeast(0L)
        val clampedAgeMs = clampAnchorAge(anchorAgeMs, anchorIntervalMs)
        return (anchorPtsMs + ((clampedAgeMs + relativeDelayMs) * safeSpeed).toLong())
            .coerceAtLeast(0L)
    }

    fun clampAnchorAge(ageMs: Long, anchorIntervalMs: Long): Long {
        val interval = anchorIntervalMs.takeIf { it in 5L..200L } ?: 33L
        val maxAgeMs = 2L * interval
        if (ageMs <= 0L) return ageMs
        return ageMs.coerceAtMost(maxAgeMs)
    }
}
