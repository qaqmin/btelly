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
import java.util.concurrent.locks.ReentrantLock

internal class LockWaitHistogram(
  private val reportEvery: Long = 512L
) {
  data class Snapshot(
    val samples: Long,
    val p50UpperUs: Long,
    val p95UpperUs: Long,
    val p99UpperUs: Long,
    val maxUs: Long
  )

  private val buckets = LongArray(BUCKET_UPPER_NS.size)
  private var samples = 0L
  private var maxWaitNs = 0L

  fun record(waitNs: Long): Snapshot? {
    val safeWaitNs = waitNs.coerceAtLeast(0L)
    val bucket = BUCKET_UPPER_NS.indexOfFirst { safeWaitNs <= it }
      .takeIf { it >= 0 } ?: BUCKET_UPPER_NS.lastIndex
    buckets[bucket]++
    samples++
    if (safeWaitNs > maxWaitNs) maxWaitNs = safeWaitNs
    if (reportEvery <= 0L || samples % reportEvery != 0L) return null
    val snapshot = Snapshot(
      samples = samples,
      p50UpperUs = percentileUpperUs(50),
      p95UpperUs = percentileUpperUs(95),
      p99UpperUs = percentileUpperUs(99),
      maxUs = nanosecondsToUpperMicroseconds(maxWaitNs)
    )
    buckets.fill(0L)
    samples = 0L
    maxWaitNs = 0L
    return snapshot
  }

  private fun percentileUpperUs(percentile: Int): Long {
    val target = ((samples * percentile) + 99L) / 100L
    var accumulated = 0L
    for (index in buckets.indices) {
      accumulated += buckets[index]
      if (accumulated >= target) {
        val upperNs = BUCKET_UPPER_NS[index]
        return if (upperNs == Long.MAX_VALUE) {
          nanosecondsToUpperMicroseconds(maxWaitNs)
        } else {
          upperNs / 1_000L
        }
      }
    }
    return nanosecondsToUpperMicroseconds(maxWaitNs)
  }

  private fun nanosecondsToUpperMicroseconds(nanoseconds: Long): Long =
    nanoseconds / 1_000L + if (nanoseconds % 1_000L == 0L) 0L else 1L

  companion object {
    private val BUCKET_UPPER_NS = longArrayOf(
      50_000L,
      100_000L,
      250_000L,
      500_000L,
      1_000_000L,
      2_000_000L,
      5_000_000L,
      10_000_000L,
      Long.MAX_VALUE
    )
  }
}

/**
 * 弹幕运行上下文。
 *
 * 只保存 Runtime 必需的共享对象：计时器、配置、过滤器、显示信息和缓存管理器。
 * 旧 ECS 的实体列表/切片状态已移除，数据时间线由 DanmakuRuntime 独立维护。
 */
internal class DanmakuContext(val renderer: DanmakuRenderer) {
  val timer = DanmakuTimer()
  val cacheManager = CacheManager(CacheCallbackHandler(Looper.myLooper()!!), this)
  val filter = DanmakuFilters()
  private val rendererLock = ReentrantLock()
  private val measureRendererWaits = LockWaitHistogram()
  private val drawRendererWaits = LockWaitHistogram()

  @Volatile
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

  fun measureRenderer(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Size = withRendererLock("measure", measureRendererWaits) {
    renderer.measure(item, displayer, config)
  }

  fun drawRenderer(
    item: DanmakuItem,
    canvas: Canvas,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    withRendererLock("draw", drawRendererWaits) {
      renderer.draw(item, canvas, displayer, config)
    }
  }

  private inline fun <T> withRendererLock(
    type: String,
    stats: LockWaitHistogram,
    block: () -> T
  ): T {
    val requestAtNs = System.nanoTime()
    rendererLock.lock()
    val report = stats.record(System.nanoTime() - requestAtNs)
    return try {
      block()
    } finally {
      rendererLock.unlock()
      report?.let { logLockWait(type, it) }
    }
  }

  private fun logLockWait(type: String, report: LockWaitHistogram.Snapshot) {
    Log.i(
      DanmakuEngine.TAG,
      "[LockWait] renderer_$type samples=${report.samples} p50<=${report.p50UpperUs}us " +
        "p95<=${report.p95UpperUs}us p99<=${report.p99UpperUs}us max=${report.maxUs}us"
    )
  }

  private fun markGenerationsForChangedValues(current: DanmakuConfig, next: DanmakuConfig) {
    if (current.density != next.density ||
      current.bold != next.bold ||
      current.fontBorder != next.fontBorder) {
      next.updateMeasure()
      next.updateLayout()
      next.updateCache()
    }
    if (current.textSizeScale != next.textSizeScale) {
      next.updateLayout()
      next.updateMeasure()
      next.updateCache()
    }
    if (current.visibility != next.visibility) {
      next.updateVisibility()
    }
    if (current.screenPart != next.screenPart ||
      current.allowOverlap != next.allowOverlap ||
      current.overlapFraction != next.overlapFraction ||
      current.trackSpacingFactor != next.trackSpacingFactor) {
      next.updateLayout()
      next.updateVisibility()
    }
    if (current.durationMs != next.durationMs ||
      current.rollingDurationMs != next.rollingDurationMs) {
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
        // cache 信号回调：渲染样式可能变化，由后续 frame 的 cacheGeneration 校验处理。
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
