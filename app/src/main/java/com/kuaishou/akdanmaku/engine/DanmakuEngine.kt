/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.engine

import android.graphics.Canvas
import android.os.SystemClock
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.kuaishou.akdanmaku.runtime.DanmakuRuntime
import com.kuaishou.akdanmaku.utils.DanmakuTimer

/**
 * 弹幕引擎入口。
 *
 * 这里已经不再使用 Ashley/ECS：普通播放路径只有一个时间线 Runtime，负责数据窗口、预算入场、
 * 测量缓存、轨道布局和帧命令输出。配置变化通过 [DanmakuConfig] generation 传递到 Runtime。
 */
class DanmakuEngine private constructor(renderer: DanmakuRenderer) {

  companion object {
    const val TAG = "DanmakuEngine"

    internal fun get(renderer: DanmakuRenderer) = DanmakuEngine(renderer)
  }

  internal val context = DanmakuContext(renderer)
  internal val timer: DanmakuTimer = context.timer
  internal val runtime = DanmakuRuntime(context)

  private var pendingConfig: DanmakuConfig? = null
  private var lastActTime = 0L

  internal val isPaused: Boolean
    get() = timer.paused

  internal fun step(deltaTimeSeconds: Float? = null) {
    startTrace("Engine_step")
    timer.step(deltaTimeSeconds)
    endTrace()
  }

  internal fun act() {
    startTrace("act")
    val startTime = SystemClock.elapsedRealtime()
    val interval = timer.currentTimeMs - lastActTime
    applyPendingConfig()
    runtime.update()
    val cost = SystemClock.elapsedRealtime() - startTime
    if (cost >= 20) {
      Log.w(TAG, "[Engine][ACT] overload act: interval: $interval, cost: $cost")
    }
    lastActTime = timer.currentTimeMs
    endTrace()
  }

  internal fun preAct() {
    // 新 Runtime 在 act() 内完成预算调度；保留空方法兼容 DanmakuPlayer 调用时序。
  }

  internal fun draw(canvas: Canvas, onRenderReady: () -> Unit) {
    runtime.draw(canvas, onRenderReady)
  }

  internal fun getCurrentTimeMs(): Long = timer.currentTimeMs

  internal fun start() {
    runtime.warmUp()
    timer.start()
    timer.paused = false
  }

  internal fun pause() {
    timer.paused = true
  }

  internal fun release() {
    timer.paused = true
    runtime.release()
  }

  internal fun seekTo(positionMs: Long) {
    timer.start(positionMs)
    runtime.seekTo(positionMs)
    context.config.updateVisibility()
    context.config.updateRetainer()
    context.config.updateLayout()
  }

  internal fun updateConfig(danmakuConfig: DanmakuConfig) {
    pendingConfig = danmakuConfig
  }

  internal fun setInitialConfig(danmakuConfig: DanmakuConfig) {
    pendingConfig = null
    context.updateConfig(danmakuConfig)
  }

  internal fun getConfig(): DanmakuConfig? = pendingConfig ?: context.config

  internal fun updateTimerFactor(factor: Float) {
    timer.factor = factor
  }

  private fun applyPendingConfig() {
    val config = pendingConfig ?: return
    pendingConfig = null
    context.updateConfig(config)
  }
}
