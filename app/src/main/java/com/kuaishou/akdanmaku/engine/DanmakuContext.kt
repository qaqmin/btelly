/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.engine

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.cache.CacheManager
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.filter.DanmakuFilters
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.DanmakuTimer
import com.kuaishou.akdanmaku.utils.Size

/**
 * 弹幕运行上下文。
 *
 * 只保存 Runtime 必需的共享对象：计时器、配置、过滤器、显示信息和缓存管理器。
 * 旧 ECS 的实体列表/切片状态已移除，数据时间线由 DanmakuRuntime 独立维护。
 */
internal class DanmakuContext(val renderer: DanmakuRenderer) {
  val timer = DanmakuTimer()
  val cacheManager = CacheManager(CacheCallbackHandler(Looper.myLooper()!!), renderer)
  val filter = DanmakuFilters()

  var config = DanmakuConfig()
    private set

  internal var displayer: DanmakuDisplayer = object : DanmakuDisplayer {
    override var height: Int = 0
    override var width: Int = 0
    override val margin: Int = 4
    override val allMarginTop: Float = 0f
    override val density: Float = 1f
    override val scaleDensity: Float = 1f
    override val densityDpi: Int = 200
  }

  fun updateConfig(config: DanmakuConfig) {
    val current = this.config
    if (current !== config) {
      markGenerationsForChangedValues(current, config)
    }
    this.config = config
    filter.dataFilter = config.dataFilter.toList()
    filter.layoutFilter = config.layoutFilter.toList()
  }

  private fun markGenerationsForChangedValues(current: DanmakuConfig, next: DanmakuConfig) {
    if (current.density != next.density || current.bold != next.bold) {
      next.updateMeasure()
      next.updateRetainer()
      next.updateLayout()
      next.updateCache()
    }
    if (current.textSizeScale != next.textSizeScale) {
      next.updateRetainer()
      next.updateLayout()
      next.updateMeasure()
      next.updateCache()
    }
    if (current.visibility != next.visibility) {
      next.updateVisibility()
    }
    if (current.screenPart != next.screenPart ||
      current.allowOverlap != next.allowOverlap ||
      current.overlapFraction != next.overlapFraction) {
      next.updateLayout()
      next.updateVisibility()
      next.updateRetainer()
    }
    if (current.durationMs != next.durationMs ||
      current.rollingDurationMs != next.rollingDurationMs) {
      next.updateRetainer()
      next.updateLayout()
    }
    if (current.dataFilter.size != next.dataFilter.size ||
      current.layoutFilter.size != next.layoutFilter.size ||
      current.filterGeneration != next.filterGeneration) {
      next.updateFilter()
    }
  }

  private inner class CacheCallbackHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      if (msg.what == CacheManager.MSG_CACHE_RENDER) {
        Log.w(DanmakuEngine.TAG, "[Context] onCacheSign, updateRender")
        config.updateRender()
      }
    }
  }

  companion object {
    private val NONE_RENDERER = object : DanmakuRenderer {
      override fun updatePaint(
        item: DanmakuItem,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig
      ) {}

      override fun measure(
        item: DanmakuItem,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig
      ): Size = Size(0, 0)

      override fun draw(
        item: DanmakuItem,
        canvas: Canvas,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig
      ) {}
    }

    val NONE_CONTEXT = DanmakuContext(NONE_RENDERER)
  }
}
