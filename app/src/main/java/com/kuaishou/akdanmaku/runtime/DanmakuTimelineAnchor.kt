package com.kuaishou.akdanmaku.runtime

internal class DanmakuTimelineAnchor {
  private var lastUpdateTimeMs = DanmakuTimelineJumpPolicy.TIME_UNSET

  fun clear() {
    lastUpdateTimeMs = DanmakuTimelineJumpPolicy.TIME_UNSET
  }

  fun syncTo(positionMs: Long) {
    lastUpdateTimeMs = positionMs
  }

  fun updateAndCheckJump(
    currentMs: Long,
    durationMs: Long,
    rollingDurationMs: Long
  ): JumpCheck {
    val previousMs = lastUpdateTimeMs
    lastUpdateTimeMs = currentMs
    return JumpCheck(
      previousMs = previousMs,
      isJump = DanmakuTimelineJumpPolicy.isJump(
        previousMs = previousMs,
        currentMs = currentMs,
        durationMs = durationMs,
        rollingDurationMs = rollingDurationMs
      )
    )
  }

  data class JumpCheck(
    val previousMs: Long,
    val isJump: Boolean
  )
}
