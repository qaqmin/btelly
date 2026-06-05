package com.btelly.feature.player.view

import org.junit.Assert.assertEquals
import org.junit.Test

class DmMaskPtsEstimatorTest {

    @Test
    fun estimateFromPlayerClock_usesPlayerClockPositionWithoutLocalExtrapolation() {
        val query = DmMaskPtsEstimator.estimateFromPlayerClock(
            playerPositionMs = 47_166L,
            elapsedSinceClockMs = 120L,
            playbackSpeed = 1f,
            isPlaying = true,
        )

        assertEquals(47_166L, query)
    }

    @Test
    fun estimateFromPlayerClock_ignoresSpeedBecausePlayerPositionAlreadyIncludesIt() {
        val query = DmMaskPtsEstimator.estimateFromPlayerClock(
            playerPositionMs = 10_000L,
            elapsedSinceClockMs = 100L,
            playbackSpeed = 2f,
            isPlaying = true,
        )

        assertEquals(10_000L, query)
    }

    @Test
    fun estimateFromPlayerClock_pausedClockDoesNotAdvance() {
        val query = DmMaskPtsEstimator.estimateFromPlayerClock(
            playerPositionMs = 47_166L,
            elapsedSinceClockMs = 120L,
            playbackSpeed = 1f,
            isPlaying = false,
        )

        assertEquals(47_166L, query)
    }

    @Test
    fun estimateFromPlayerClock_clampsNegativePositionToZero() {
        val query = DmMaskPtsEstimator.estimateFromPlayerClock(
            playerPositionMs = -1L,
            elapsedSinceClockMs = 0L,
            playbackSpeed = 1f,
            isPlaying = false,
        )

        assertEquals(0L, query)
    }

    @Test
    fun estimateFromVideoFrameAnchor_doesNotCreateASecondMaskClock() {
        val query = DmMaskPtsEstimator.estimateFromVideoFrameAnchor(
            presentationTimeUs = 48_000_000L,
            releaseTimeNs = 123_456_789L,
        )

        assertEquals(null, query)
    }
}
