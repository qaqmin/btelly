/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData

internal class DanmakuTrackLayout {
  private val rollingTracks = RollingTrackAllocator()
  private val topTracks = FixedTrackAllocator(fromBottom = false)
  private val bottomTracks = FixedTrackAllocator(fromBottom = true)

  fun updateExisting(
    item: DanmakuItem,
    width: Int,
    height: Int,
    config: DanmakuConfig
  ): Boolean =
    when (item.data.mode) {
      DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.updateExisting(item, width, height, config)
      DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.updateExisting(item, width, height, config)
      else -> rollingTracks.updateExisting(item, height, config)
    }

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean =
    when (item.data.mode) {
      DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.layout(item, nowMs, width, height, margin, config)
      DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.layout(item, nowMs, width, height, margin, config)
      else -> rollingTracks.layout(item, nowMs, width, height, margin, config)
    }

  fun remove(item: DanmakuItem) {
    rollingTracks.remove(item)
    topTracks.remove(item)
    bottomTracks.remove(item)
  }

  fun clear() {
    rollingTracks.clear()
    topTracks.clear()
    bottomTracks.clear()
  }
}
