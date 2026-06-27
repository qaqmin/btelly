/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.ui

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import com.kuaishou.akdanmaku.ext.AkLog as Log
import android.view.Choreographer
import androidx.core.math.MathUtils.clamp
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DataSource
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.btelly.core.common.log.AppLog
import com.kuaishou.akdanmaku.utils.Fraction
import com.kuaishou.akdanmaku.utils.ObjectPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 *
 * 弹幕播放器，与 [DanmakuView] 形成类似于视频播放器的弹幕播放结构
 * 此类应当在共享同一个弹幕播放的场景间进行共享，它持有着
 * - 播放上下文（计时器，渲染器，缓存管理等）
 * - 渲染引擎
 * 在与一个 [DanmakuView] 绑定后会通过 [Choreographer] 进行帧同步。
 *
 * 内部具有一个用于执行计算的线程，几乎所有对外 API 均为同步返回，具体操作在此线程中进行
 *
 * @param renderer 业务端自定义的弹幕渲染器
 */
@Suppress("unused")
class DanmakuPlayer(renderer: DanmakuRenderer, dataSource: DataSource? = null) {

  companion object {
    internal const val MSG_FRAME_UPDATE = 2101
    internal const val NOTIFY_DISPLAYER_SIZE_CHANGE = 2201

    private const val PLAYER_WIDTH = 682
    private const val RELEASE_LATCH_TIMEOUT_MS = 220L
    private const val MAX_PRIME_MEASURE_ON_UPDATE = 160
    private const val MAX_PRIME_MEASURE_ON_APPEND = 12
    private const val MAX_SYNC_PRIME_MEASURE_ON_REPLACE = 32
    private const val ACTION_PERF_SUMMARY_INTERVAL_MS = 2_000L
    const val MIN_DANMAKU_DURATION: Long = 4000
    const val MAX_DANMAKU_DURATION_HIGH_DENSITY: Long = 9000
    /**
     * 是否手动控制 Step 流程
     */
    var isManualStep = false
  }

  private var danmakuView: DanmakuView? = null
  internal val engine = DanmakuEngine.get(renderer)
  private val dataSourceListener = object : DataSource.DataChangeListener {
    override fun onDataAdded(additionalItems: List<DanmakuItem>) {
      engine.runtime.primeMeasureItems(additionalItems, MAX_PRIME_MEASURE_ON_APPEND)
      engine.runtime.addItems(additionalItems)
    }

    override fun onDataRemoved(removalItems: List<DanmakuItem>) {
    }
  }
  private val actionThread by lazy {
    HandlerThread("ActionThread").apply {
      start()
      // act 计算（布局/轨道/碰撞）比 cache 构建更重要（影响弹幕位置准确性），
      // 只略低于主线程渲染，避免与主线程 onDraw 平等抢 CPU 导致渲染卡顿。
      Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
    }
  }
  private val actionHandler by lazy { ActionHandler(actionThread.looper) }
  private val frameCallback by lazy { FrameCallback() }

  private var currentDisplayerWidth = 0
  private var currentDisplayerHeight = 0
  private var currentDisplayerSizeFactor = 1f
  private var config: DanmakuConfig? = null
  @Volatile
  private var frameCallbackScheduled = false
  private var actionPerfWindowStartedAtMs = 0L
  private var actionPerfFrames = 0
  private var actionPerfSlowFrames = 0
  private var actionPerfTotalMs = 0L
  private var actionPerfMaxMs = 0L

  @Volatile
  private var started = false

  /**
   * 弹幕埋点所需的接口
   */
  var listener: DanmakuListener? = null
    set(value) {
      if (field != value) {
        field = value
        engine.runtime.listener = value
      }
    }
  @Volatile
  var isReleased: Boolean = false
    private set

  val cacheHit: Fraction?
    get() = engine.runtime.cacheHit

  internal fun diagnosticSummary(): String = engine.runtime.diagnosticSummary()

  init {
    dataSource?.setListener(dataSourceListener)
  }

  private fun postFrameCallback() {
    // Choreographer 绑定到调用线程的 Looper。这里从 updateFrame 在 ActionThread 调用,
    // 直接在 ActionThread 的 Looper 上挂 vsync 回调,不再中转到主线程——避免主线程消息队列
    // 拥塞时回调晚挂载导致漏帧(弹幕一顿一顿的根因)。
    if (!started || isReleased || !actionThread.isAlive) {
      return
    }
    if (Looper.myLooper() !== actionThread.looper) {
      // 兜底:若从其它线程调用,转回 ActionThread 执行(保证 Choreographer ThreadLocal 一致)。
      actionHandler.post { postFrameCallback() }
      return
    }
    if (frameCallbackScheduled) {
      return
    }
    frameCallbackScheduled = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun removeFrameCallback() {
    // 必须在 ActionThread 上移除(和挂载是同一个 Choreographer 实例)。
    if (Looper.myLooper() !== actionThread.looper) {
      if (actionThread.isAlive) {
        actionHandler.post { removeFrameCallbackInternal() }
      }
      return
    }
    removeFrameCallbackInternal()
  }

  /**
   * 在 ActionThread 上执行的 Choreographer 回调移除(无线程判断)。
   * 供 [removeFrameCallback] 和 [release] 的 postAtFrontOfQueue 任务复用。
   */
  private fun removeFrameCallbackInternal() {
    runCatching {
      Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
    frameCallbackScheduled = false
  }

  private fun updateFrame(deltaTimeSeconds: Float? = null) {
    if (!started) {
      return
    }

    val frameStartedAtMs = SystemClock.elapsedRealtime()
    // Advance the danmaku clock from the frame loop, not from View.onDraw().
    // Parent mask invalidation can reuse the child display list and skip DanmakuView.onDraw for
    // seconds; advancing in draw would then jump all rolling commands in one large step.
    engine.step(deltaTimeSeconds)
    if (!started) {
      return
    }
    startTrace("updateFrame")
    // update entities before system update
    engine.preAct()
    // Do work in actionThread.
    val actStartedAtMs = SystemClock.elapsedRealtime()
    engine.act()
    val actMs = SystemClock.elapsedRealtime() - actStartedAtMs
    // Post invalidate view to force onDraw's call on next frame.
    startTrace("postInvalidate")
    danmakuView?.postInvalidateOnAnimation()
    endTrace()
    endTrace()
    val totalMs = SystemClock.elapsedRealtime() - frameStartedAtMs
    if (!isManualStep) {
      postFrameCallback()
    }
    recordActionPerf(actMs)
    if (actMs >= 12L || totalMs >= 96L) {
      AppLog.w(
        "PlaybackPerf",
        "danmaku_action act=${actMs}ms total=${totalMs}ms manual=$isManualStep " +
          diagnosticSummary()
      )
    }
  }

  private fun recordActionPerf(actMs: Long) {
    val nowMs = SystemClock.elapsedRealtime()
    if (actionPerfWindowStartedAtMs == 0L) {
      actionPerfWindowStartedAtMs = nowMs
    }
    actionPerfFrames++
    if (actMs >= 12L) {
      actionPerfSlowFrames++
    }
    actionPerfTotalMs += actMs
    actionPerfMaxMs = max(actionPerfMaxMs, actMs)
    val elapsedMs = nowMs - actionPerfWindowStartedAtMs
    if (elapsedMs < ACTION_PERF_SUMMARY_INTERVAL_MS) return
    val frames = actionPerfFrames.coerceAtLeast(1)
    val avgMs = actionPerfTotalMs.toFloat() / frames
    AppLog.d(
      "PlaybackPerf",
      "danmaku_action_summary frames=$actionPerfFrames slow=$actionPerfSlowFrames " +
        "avg=${String.format(java.util.Locale.US, "%.2f", avgMs)}ms max=${actionPerfMaxMs}ms " +
        diagnosticSummary()
    )
    actionPerfWindowStartedAtMs = nowMs
    actionPerfFrames = 0
    actionPerfSlowFrames = 0
    actionPerfTotalMs = 0L
    actionPerfMaxMs = 0L
  }

  internal fun draw(canvas: Canvas) {
    if (isReleased) {
      return
    }
    if (!started) {
      // 暂停只冻结时间线，不能把当前弹幕帧清空；否则播放器暂停后 View 重绘会让弹幕瞬间消失。
      engine.draw(canvas) {
        // 暂停态没有 action 线程等待绘制信号，这里只保留最后一帧渲染。
      }
      return
    }
    engine.draw(canvas) {
    }
  }

  /**
   * For debug use, step manually.
   */
  fun step(deltaTimeMs: Int) {
    if (isManualStep) {
      actionHandler.obtainMessage(MSG_FRAME_UPDATE, deltaTimeMs, 0).sendToTarget()
    }
  }

  /**
   * 将播放器与一个 DanmakuView 绑定，前一个被绑定的会自动解锁。
   * 绑定后弹幕的绘制将在此 View 上进行
   */
  fun bindView(danmakuView: DanmakuView) {
    this.danmakuView?.danmakuPlayer = null
    this.danmakuView = danmakuView
    danmakuView.danmakuPlayer = this
    engine.context.displayer = danmakuView.displayer
    danmakuView.postInvalidate()
  }

  /**
   * 播放弹幕
   *
   * @param danmakuConfig 弹幕配置
   */
  fun start(danmakuConfig: DanmakuConfig? = null) {
    danmakuConfig?.let {
      updateConfig(it)
    }
    engine.start()
    val wasStarted = started
    if (!started) {
      started = true
    }
    if (!isManualStep) {
      startFrameLoop(force = wasStarted)
    }
  }

  fun pause() {
    stopFrameLoop()
    engine.pause()
  }

  fun stop() {
    stopFrameLoop()
    seekTo(0)
    engine.pause()
  }

  private fun stopFrameLoop() {
    if (!started) {
      return
    }
    started = false
    removeFrameCallback()
    if (actionThread.isAlive) {
      actionHandler.postAtFrontOfQueue {
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
      }
    } else {
      actionHandler.removeMessages(MSG_FRAME_UPDATE)
    }
  }

  private fun startFrameLoop(force: Boolean = false) {
    if (!started || isReleased) {
      return
    }
    // 恢复播放时主动踢一帧，并在下一次 action 后继续挂 Choreographer 帧循环。
    danmakuView?.postInvalidateOnAnimation()
    if (actionThread.isAlive) {
      actionHandler.post {
        if (!started || isReleased) {
          return@post
        }
        if (force) {
          actionHandler.removeMessages(MSG_FRAME_UPDATE)
          removeFrameCallback()
        }
        if (!actionHandler.hasMessages(MSG_FRAME_UPDATE)) {
          actionHandler.sendEmptyMessage(MSG_FRAME_UPDATE)
        }
      }
    }
  }

  /**
   * 释放弹幕播放器，释放后弹幕播放器将不再可用。
   */
  fun release() {
    if (isReleased) {
      return
    }
    isReleased = true
    started = false
    val releaseLatch = CountDownLatch(1)
    if (actionThread.isAlive) {
      // Choreographer 回调必须在 ActionThread 上移除(和挂载是同一个实例)。
      // 把它和消息清理一起放到 postAtFrontOfQueue,保证在 ActionThread 退出前执行。
      actionHandler.postAtFrontOfQueue {
        removeFrameCallbackInternal()
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
        actionHandler.removeCallbacksAndMessages(null)
        releaseLatch.countDown()
      }
      releaseLatch.await(RELEASE_LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } else {
      actionHandler.removeCallbacksAndMessages(null)
    }
    actionThread.quitSafely()
    actionThread.join(50L)
    engine.release()
  }

  fun seekTo(positionMs: Long) {
    Log.d(DanmakuEngine.TAG, "[Player] SeekTo($positionMs)")
    getConfig()?.updateFirstShown()
    engine.seekTo(max(positionMs, 0L))
  }

  fun syncTimerTo(positionMs: Long) {
    engine.syncTimerTo(max(positionMs, 0L))
  }

  fun getCurrentTimeMs(): Long {
    return engine.getCurrentTimeMs()
  }

  fun updatePlaySpeed(speed: Float) {
    engine.updateTimerFactor(speed)
  }

  fun clearData() {
    engine.runtime.clearAllData()
  }

  fun clearDataKeepingLastFrame() {
    engine.runtime.clearRuntimeData(keepCurrentFrame = true)
  }

  fun updateData(dataList: List<DanmakuItemData>): List<DanmakuItem> {
    val items = ArrayList<DanmakuItem>(dataList.size)
    for (data in dataList) {
      items.add(obtainItem(data))
    }
    engine.runtime.primeMeasureItems(items, MAX_PRIME_MEASURE_ON_APPEND)
    engine.runtime.addItems(items)
    return items
  }

  fun replaceData(dataList: List<DanmakuItemData>): List<DanmakuItem> {
    val items = ArrayList<DanmakuItem>(dataList.size)
    for (data in dataList) {
      items.add(obtainItem(data))
    }
    engine.runtime.primeMeasureItemsNow(items, MAX_SYNC_PRIME_MEASURE_ON_REPLACE)
    if (dataList.size > MAX_SYNC_PRIME_MEASURE_ON_REPLACE) {
      engine.runtime.primeMeasureItems(items, MAX_PRIME_MEASURE_ON_UPDATE)
    }
    engine.runtime.addItems(items)
    return items
  }

  /**
   * 弹幕目前统一的数据结构就是 DanmakuItem，他是 DanmakuItemData 的超集，也是被定义为
   * 可以进行扩展的
   */
  fun updateItems(items: List<DanmakuItem>) {
    engine.runtime.primeMeasureItems(items, MAX_PRIME_MEASURE_ON_APPEND)
    engine.runtime.addItems(items)
  }

  fun send(danmaku: DanmakuItemData): DanmakuItem {
    val item = obtainItem(danmaku)
    engine.runtime.primeMeasureItem(item)
    engine.runtime.addItem(item)
    return item
  }

  fun send(item: DanmakuItem) {
    engine.runtime.primeMeasureItem(item)
    engine.runtime.addItem(item)
  }

  fun setLiveMode(enabled: Boolean) {
    engine.runtime.liveMode = enabled
  }

  /**
   * 更新一个弹幕
   */
  fun updateItem(item: DanmakuItem) {
    engine.runtime.updateItem(item)
  }

  fun updateConfig(danmakuConfig: DanmakuConfig?) {
    config = danmakuConfig
    val config = danmakuConfig ?: return
    if (started) {
      engine.updateConfig(config)
    } else {
      // 启动前配置是初始状态，不应在第一帧再从默认值切换，避免首屏弹幕重布局。
      engine.setInitialConfig(config)
    }
  }

  fun getConfig(): DanmakuConfig? = engine.getConfig()

  fun getDanmakusAtPoint(point: Point): List<DanmakuItem>? {
    return engine.runtime.getDanmakus(point)
  }

  fun getDanmakusInRect(hitRect: RectF): List<DanmakuItem>? {
    return engine.runtime.getDanmakus(hitRect)
  }


  fun obtainItem(danmaku: DanmakuItemData): DanmakuItem =
    ObjectPool.obtainItem(danmaku, this)

  fun releaseItem(item: DanmakuItem) {
    ObjectPool.releaseItem(item)
  }

  internal fun notifyDisplayerSizeChanged(width: Int, height: Int) {
    val displayer = engine.context.displayer
    updateViewportState(width, height, displayer.getViewportSizeFactor())
    updateMaxDanmakuDuration()
    if (displayer.width != width || displayer.height != height) {
      Log.d(DanmakuEngine.TAG, "notifyDisplayerSizeChanged($width, $height)")
      displayer.width = width
      displayer.height = height
      actionHandler.obtainMessage(NOTIFY_DISPLAYER_SIZE_CHANGE).sendToTarget()
    }
  }

  private fun updateViewportState(width: Int, height: Int, viewportSizeFactor: Float) {
    val config = this.config ?: return
    if (currentDisplayerWidth != width ||
      currentDisplayerHeight != height ||
      currentDisplayerSizeFactor != viewportSizeFactor) {
      val duration = clamp(
        (config.rollingDurationMs * (viewportSizeFactor * width / PLAYER_WIDTH)).toLong(),
        MIN_DANMAKU_DURATION,
        MAX_DANMAKU_DURATION_HIGH_DENSITY
      )
      if (config.durationMs != duration) {
        config.durationMs = duration
        config.updateLayout()
      }
      currentDisplayerWidth = width
      currentDisplayerHeight = height
      currentDisplayerSizeFactor = viewportSizeFactor
    }
  }

  private fun updateMaxDanmakuDuration() {
    // FIXME distinguish differ danmaku type duration
  }

  private inner class ActionHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        MSG_FRAME_UPDATE -> {
          val deltaTimeSeconds = if (msg.arg1 > 0) {
            msg.arg1 / 1000.0f
          } else null
          updateFrame(deltaTimeSeconds)
        }
        NOTIFY_DISPLAYER_SIZE_CHANGE -> {
          val newConfig = engine.context.config
          if (started) {
            newConfig.updateLayout()
            newConfig.updateMeasure()
            newConfig.updateCache()
          }
        }
      }
    }
  }

  private inner class FrameCallback : Choreographer.FrameCallback {

    override fun doFrame(frameTimeNanos: Long) {
      // doFrame 现在跑在 ActionThread 的 Choreographer 上(不再中转主线程),
      // 可直接驱动 updateFrame,省掉一次跨线程消息投递——降低帧驱动延迟。
      frameCallbackScheduled = false
      actionHandler.removeMessages(MSG_FRAME_UPDATE)
      updateFrame(null)
    }
  }
}
