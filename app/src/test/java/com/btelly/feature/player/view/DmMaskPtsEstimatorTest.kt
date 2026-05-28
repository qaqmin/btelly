package com.btelly.feature.player.view

import org.junit.Assert.assertEquals
import org.junit.Test

class DmMaskPtsEstimatorTest {

    @Test
    fun estimateFromAnchor_usesRelativeMaskDelayInsteadOfFullFrameLookahead() {
        val query = DmMaskPtsEstimator.estimateFromAnchor(
            anchorPtsMs = 47_166L,
            anchorAgeMs = -18L,
            pipelineDelayMs = 21L,
            vsyncPeriodMs = 17L,
            playbackSpeed = 1f,
            anchorIntervalMs = 33L,
        )

        assertEquals(47_152L, query)
    }

    @Test
    fun estimateFromAnchor_clampsStaleAnchorAgeDuringVideoJank() {
        val query = DmMaskPtsEstimator.estimateFromAnchor(
            anchorPtsMs = 10_000L,
            anchorAgeMs = 500L,
            pipelineDelayMs = 21L,
            vsyncPeriodMs = 17L,
            playbackSpeed = 1f,
            anchorIntervalMs = 33L,
        )

        assertEquals(10_070L, query)
    }

    @Test
    fun estimateFromAnchor_preservesFutureAnchorAgeOnFastDevices() {
        val query = DmMaskPtsEstimator.estimateFromAnchor(
            anchorPtsMs = 10_000L,
            anchorAgeMs = -250L,
            pipelineDelayMs = 33L,
            vsyncPeriodMs = 17L,
            playbackSpeed = 1f,
            anchorIntervalMs = 33L,
        )

        assertEquals(9_766L, query)
    }

    @Test
    fun estimateFromAnchor_scalesRelativeDelayByPlaybackSpeed() {
        val query = DmMaskPtsEstimator.estimateFromAnchor(
            anchorPtsMs = 10_000L,
            anchorAgeMs = 10L,
            pipelineDelayMs = 25L,
            vsyncPeriodMs = 17L,
            playbackSpeed = 2f,
            anchorIntervalMs = 33L,
        )

        assertEquals(10_036L, query)
    }
}
