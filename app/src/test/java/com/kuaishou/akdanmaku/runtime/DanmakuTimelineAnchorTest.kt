package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTimelineAnchorTest {

  @Test
  fun ordinaryLargePlaybackJumpStillRequestsReset() {
    val anchor = DanmakuTimelineAnchor()

    assertFalse(anchor.updateAndCheckJump(1_000L, DURATION_MS, DURATION_MS).isJump)

    assertTrue(anchor.updateAndCheckJump(8_000L, DURATION_MS, DURATION_MS).isJump)
  }

  @Test
  fun softClockSyncUpdatesAnchorWithoutRequestingResetOnNextFrame() {
    val anchor = DanmakuTimelineAnchor()

    assertFalse(anchor.updateAndCheckJump(1_000L, DURATION_MS, DURATION_MS).isJump)
    anchor.syncTo(8_000L)

    assertFalse(anchor.updateAndCheckJump(8_016L, DURATION_MS, DURATION_MS).isJump)
  }

  private companion object {
    const val DURATION_MS = 3_800L
  }
}
