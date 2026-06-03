/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import kotlin.math.abs
import kotlin.math.max

internal object DanmakuTimelineJumpPolicy {
  const val TIME_UNSET = Long.MIN_VALUE

  fun isJump(previousMs: Long, currentMs: Long, durationMs: Long, rollingDurationMs: Long): Boolean {
    if (previousMs == TIME_UNSET) return false
    val thresholdMs = max(MIN_JUMP_THRESHOLD_MS, max(durationMs, rollingDurationMs) / 2)
    return abs(currentMs - previousMs) >= thresholdMs
  }

  private const val MIN_JUMP_THRESHOLD_MS = 2_000L
}
