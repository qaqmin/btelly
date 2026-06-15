package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingTrackAllocatorTest {

  @Test
  fun delayedBatchUsesPredictedStartTimeForCollisionState() {
    val allocator = RollingTrackAllocator()
    val config = DanmakuConfig(
      rollingDurationMs = 4_000L,
      durationMs = 4_000L,
      allowOverlap = false,
      overlapFraction = 0f
    )
    val nowMs = 10_000L
    val items = (0 until 5).map { index ->
      DanmakuItem(
        DanmakuItemData(
          danmakuId = index.toLong(),
          position = 9_000L + index,
          content = "late-$index",
          mode = DanmakuItemData.DANMAKU_MODE_ROLLING,
          textSize = 25,
          textColor = 0xffffff
        )
      ).also { item ->
        item.duration = config.rollingDurationMs
        item.drawState.width = 500f
        item.drawState.height = 40f
        item.drawState.measureGeneration = config.measureGeneration
      }
    }

    items.forEach { item ->
      assertTrue(
        allocator.layout(
          item = item,
          nowMs = nowMs,
          width = 1_280,
          height = 720,
          margin = 4,
          config = config
        )
      )
    }

    assertTrue(items.map { it.drawState.positionY }.distinct().size > 1)
    assertTrue(items.all { it.rollingStartTimeMs == nowMs })
  }
}
