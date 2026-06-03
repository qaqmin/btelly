package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTimelineJumpPolicyTest {

  @Test
  fun firstFrameDoesNotTriggerJumpReset() {
    assertFalse(
      DanmakuTimelineJumpPolicy.isJump(
        previousMs = DanmakuTimelineJumpPolicy.TIME_UNSET,
        currentMs = 60_000L,
        durationMs = 3_800L,
        rollingDurationMs = 3_800L
      )
    )
  }

  @Test
  fun normalPlaybackStepDoesNotTriggerJumpReset() {
    assertFalse(
      DanmakuTimelineJumpPolicy.isJump(
        previousMs = 12_000L,
        currentMs = 12_034L,
        durationMs = 3_800L,
        rollingDurationMs = 3_800L
      )
    )
  }

  @Test
  fun forwardSeekTriggersJumpReset() {
    assertTrue(
      DanmakuTimelineJumpPolicy.isJump(
        previousMs = 12_257L,
        currentMs = 60_528L,
        durationMs = 3_800L,
        rollingDurationMs = 3_800L
      )
    )
  }

  @Test
  fun backwardSeekTriggersJumpReset() {
    assertTrue(
      DanmakuTimelineJumpPolicy.isJump(
        previousMs = 60_528L,
        currentMs = 12_257L,
        durationMs = 3_800L,
        rollingDurationMs = 3_800L
      )
    )
  }
}
