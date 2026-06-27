/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.cache.DrawingCache
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.ItemState
import com.kuaishou.akdanmaku.engine.DanmakuContext
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.ext.isOutside
import com.kuaishou.akdanmaku.ext.isLate
import com.kuaishou.akdanmaku.ext.isTimeout
import com.kuaishou.akdanmaku.ui.DanmakuListener
import com.kuaishou.akdanmaku.utils.Fraction
import kotlin.math.max

/**
 * 新弹幕运行时：面向视频播放时间线，而不是面向 ECS 实体。
 *
 * 旧 ECS 会让一条普通滚动弹幕经过 Data/Layout/Cache/Render 多个系统，每帧还要遍历实体和同步组件。
 * 这里把播放态弹幕压成四段流水线：数据窗口 -> 预算准备 -> 轨道布局 -> 帧命令。
 * 普通视频弹幕走这条路径，减少 TV 4K 下 CPU 与主线程压力。
 */
internal class DanmakuRuntime(private val context: DanmakuContext) {

  var listener: DanmakuListener? = null
  var liveMode: Boolean = false
  val cacheHit: Fraction = Fraction(1, 1)

  private val callbackHandler = Handler(Looper.getMainLooper())
  private val comparator = Comparator<DanmakuItem> { a, b -> a.compareTo(b) }

  private val pendingAddItems = ArrayList<DanmakuItem>(128)
  private val sortedItems = ArrayList<DanmakuItem>(2048)
  private val timelineWindow = DanmakuTimelineWindow(sortedItems, comparator, LIVE_HISTORY_MAX) { it.timePosition }
  private val activeStates = ArrayList<ActiveItemState>(256)
  private val waitingStates = ArrayList<ActiveItemState>(128)
  private val stateById = HashMap<Long, ActiveItemState>(512)
  private val measureCandidates = ArrayList<ActiveItemState>(128)
  private val measureQueue = ArrayDeque<MeasureQueueEntry>()
  private val measureEntryPool = ArrayList<MeasureQueueEntry>(256)
  private val statePool = ArrayList<ActiveItemState>(256)

  private val trackLayout = DanmakuTrackLayout()
  private val framePool = RuntimeFramePool(MAX_RUNTIME_FRAME_POOL_SIZE)
  private val drawPaint = Paint().apply {
    isAntiAlias = true
  }

  private var layoutGeneration = -1
  private var measureGeneration = -1
  private var cacheGeneration = -1
  private var visibilityGeneration = -1
  private var layoutProfileTick = 0
  private var filterCacheableGeneration = -1
  private var filterResultCacheable = false
  private var fallbackLimitLogTick = 0
  private var lastFrameStallLogAtMs = 0L
  @Volatile private var lastDrawLockReleasedAt = 0L
  private var lastDrawStallLogAtMs = 0L
  private var lastZeroFrameLogAtMs = 0L
  // 卡顿期 now 跳变检测:记录上一拍 now 和滚动平均的单帧间隔,
  // removeExpired 据此跳过滚动弹幕的 timeout 移除,避免还在屏幕中段的弹幕被误杀。
  private var lastUpdateTimeMs = 0L
  private var smoothedFrameDeltaMs = 16L
  private val timelineAnchor = DanmakuTimelineAnchor()

  @Volatile
  private var frame: RuntimeFrame? = null
  @Volatile
  private var transitionFrame: RuntimeFrame? = null
  private val pendingReleaseFrames = ArrayList<RuntimeFrame>(3)

  fun warmUp() {
    context.cacheManager.warmUp()
  }

  @Synchronized
  fun addItems(items: Collection<DanmakuItem>) {
    pendingAddItems.addAll(items)
  }

  @Synchronized
  fun primeMeasureItems(items: List<DanmakuItem>, maxCount: Int) {
    if (items.isEmpty() || maxCount <= 0) return
    val config = context.config
    var scheduled = 0
    var cacheHits = 0
    var cachePrimed = 0
    val startedAt = SystemClock.elapsedRealtime()
    for (item in items) {
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) {
        if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
          requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
          cachePrimed++
        }
        continue
      }
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        item.pendingMeasureGeneration = -1
        cacheHits++
        if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
          requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
          cachePrimed++
        }
        continue
      }
      if (scheduled >= maxCount) break
      // 首窗口提交后先把测量排进缓存线程，减少第一批 action 帧里的集中测量抖动。
      item.state = ItemState.Measuring
      item.pendingMeasureGeneration = config.measureGeneration
      context.cacheManager.requestMeasure(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = CACHE_PRIORITY_VISIBLE,
        buildAfterMeasure = cachePrimed < MAX_PRIME_CACHE_BUILDS
      )
      if (cachePrimed < MAX_PRIME_CACHE_BUILDS) {
        cachePrimed++
      }
      scheduled++
    }
    val costMs = SystemClock.elapsedRealtime() - startedAt
    if (items.size >= 96 || scheduled >= 24 || costMs >= 4L) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] prime measure scheduled=$scheduled cacheHit=$cacheHits cachePrimed=$cachePrimed " +
          "total=${items.size} cost=${costMs}ms"
      )
    }
  }

  @Synchronized
  fun primeMeasureItemsNow(items: List<DanmakuItem>, maxCount: Int) {
    if (items.isEmpty() || maxCount <= 0) return
    val config = context.config
    var measured = 0
    var cacheHits = 0
    var cachePrimed = 0
    var failed = 0
    var scanned = 0
    val startedAt = SystemClock.elapsedRealtime()
    for (item in items) {
      if (measured >= maxCount) break
      scanned++
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) {
        if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
          requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
          cachePrimed++
        }
        continue
      }
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      val size = cachedSize ?: context.cacheManager.measureNow(item, context.displayer, config)
      if (size == null) {
        item.state = ItemState.Error
        item.pendingMeasureGeneration = -1
        failed++
        continue
      }
      item.drawState.width = size.width.toFloat()
      item.drawState.height = size.height.toFloat()
      item.drawState.measureGeneration = config.measureGeneration
      item.state = ItemState.Measured
      item.pendingMeasureGeneration = -1
      if (cachedSize != null) {
        cacheHits++
      }
      if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
        requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
        cachePrimed++
      }
      measured++
    }
    val costMs = SystemClock.elapsedRealtime() - startedAt
    if (measured > 0 || failed > 0 || costMs >= 4L) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] sync prime measure measured=$measured cacheHit=$cacheHits failed=$failed " +
          "cachePrimed=$cachePrimed total=${items.size} cost=${costMs}ms"
      )
    }
    if (scanned < items.size) {
      primeMeasureItems(items.drop(scanned), maxCount)
    }
  }

  @Synchronized
  fun primeMeasureItem(item: DanmakuItem) {
    val config = context.config
    if (item.state == ItemState.Measuring) return
    if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) return
    val cachedSize = context.cacheManager.getDanmakuSize(item.data)
    if (cachedSize != null) {
      item.drawState.width = cachedSize.width.toFloat()
      item.drawState.height = cachedSize.height.toFloat()
      item.drawState.measureGeneration = config.measureGeneration
      item.state = ItemState.Measured
      item.pendingMeasureGeneration = -1
      requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)
      return
    }
    item.state = ItemState.Measuring
    item.pendingMeasureGeneration = config.measureGeneration
    context.cacheManager.requestMeasure(
      item = item,
      displayer = context.displayer,
      config = config,
      priority = CACHE_PRIORITY_VISIBLE,
      buildAfterMeasure = true
    )
  }

  @Synchronized
  fun addItem(item: DanmakuItem) {
    pendingAddItems.add(item)
  }

  @Synchronized
  fun updateItem(item: DanmakuItem) {
    sortedItems.remove(item)
    pendingAddItems.add(item)
  }

  @Synchronized
  fun clearAllData() {
    pendingAddItems.clear()
    timelineWindow.clear()
    activeStates.forEach { state ->
      state.item.cacheRecycle()
      state.item.reset()
      recycleState(state)
    }
    waitingStates.forEach { state ->
      state.item.cacheRecycle()
      state.item.reset()
      recycleState(state)
    }
    activeStates.clear()
    waitingStates.clear()
    measureCandidates.clear()
    measureQueue.clear()
    stateById.clear()
    timelineAnchor.clear()
    clearTracks()
    releaseFrame(frame)
    releaseFrame(transitionFrame)
    frame = null
    transitionFrame = null
    releaseAllPendingFrames()
  }

  @Synchronized
  fun seekTo(positionMs: Long) {
    val config = context.config
    resetRuntimeWindow(positionMs, config, keepCurrentFrame = true)
    timelineAnchor.syncTo(positionMs)
  }

  fun update() {
    val requestAt = SystemClock.elapsedRealtime()
    synchronized(this) {
      val startedAt = SystemClock.elapsedRealtime()
      updateLocked(startedAt, startedAt - requestAt)
    }
  }

  private fun updateLocked(startedAt: Long, syncWaitMs: Long) {
    val diagDrawGapMs = startedAt - lastDrawLockReleasedAt
    var checkpoint = startedAt
    val releaseProfile = releasePendingFrames()
    val releaseMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val syncedItems = syncPendingData()
    val syncMs = SystemClock.elapsedRealtime() - checkpoint

    val config = context.config
    val now = context.timer.currentTimeMs
    // 计算本帧 now 的增量,维护滚动平均,供 removeExpired 检测卡顿期跳变。
    val frameDeltaMs = if (lastUpdateTimeMs > 0L) (now - lastUpdateTimeMs).coerceAtLeast(0L) else smoothedFrameDeltaMs
    if (lastUpdateTimeMs > 0L && frameDeltaMs in 1L..200L) {
      // 指数移动平均,忽略异常跳变(>200ms 不参与平均,避免卡顿把基准拉高)
      smoothedFrameDeltaMs = (smoothedFrameDeltaMs * 0.8 + frameDeltaMs * 0.2).toLong().coerceAtLeast(1L)
    }
    lastUpdateTimeMs = now
    checkpoint = SystemClock.elapsedRealtime()
    val timelineReset = resetForTimelineJumpIfNeeded(now, config)
    val timelineResetMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    if (config.layoutGeneration != layoutGeneration) {
      clearTracks()
      activeStates.forEach { it.item.drawState.layoutGeneration = -1 }
      waitingStates.forEach { it.item.drawState.layoutGeneration = -1 }
      layoutGeneration = config.layoutGeneration
    }
    if (config.measureGeneration != measureGeneration) {
      activeStates.forEach { state ->
        val it = state.item
        it.state = ItemState.Uninitialized
        it.pendingMeasureGeneration = -1
        it.drawState.recycle()
        enqueueMeasureState(state)
      }
      waitingStates.forEach { state ->
        val it = state.item
        it.state = ItemState.Uninitialized
        it.pendingMeasureGeneration = -1
        it.drawState.recycle()
        enqueueMeasureState(state)
      }
      measureGeneration = config.measureGeneration
    }
    if (config.cacheGeneration != cacheGeneration) {
      activeStates.forEach { it.item.cacheRecycle() }
      waitingStates.forEach { it.item.cacheRecycle() }
      cacheGeneration = config.cacheGeneration
    }
    visibilityGeneration = config.visibilityGeneration
    val generationMs = SystemClock.elapsedRealtime() - checkpoint

    checkpoint = SystemClock.elapsedRealtime()
    val expiredItems = removeExpired(now, config, frameDeltaMs)
    val expireMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val enqueuedActiveItems = enqueueDueItems(now, config)
    val enqueueMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val promotedItems = promoteWaitingItems(now)
    val promoteMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val scheduledMeasures = scheduleMeasureActiveItems(config)
    val measureMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val newFrame = layoutAndBuildFrame(now, config)
    val layoutMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val replacedFrame = shouldReplaceWithFrame(newFrame)
    if (replacedFrame) {
      replaceFrame(newFrame)
    } else {
      framePool.release(newFrame)
    }
    val frameMs = SystemClock.elapsedRealtime() - checkpoint
    logFrameStallIfNeeded(
      now = now,
      config = config,
      syncedItems = syncedItems,
      enqueuedActiveItems = enqueuedActiveItems,
      promotedItems = promotedItems,
      scheduledMeasures = scheduledMeasures,
      builtCommands = newFrame.commands.size,
      replacedFrame = replacedFrame
    )

    val cost = SystemClock.elapsedRealtime() - startedAt
    if (syncWaitMs >= 5L) {
      Log.w(
        DanmakuEngine.TAG,
        "[Diag] update syncWait=${syncWaitMs}ms held=${cost}ms active=${activeStates.size} " +
          "commands=${frame?.commands?.size ?: 0} sinceDrawRelease=${diagDrawGapMs}ms"
      )
    }
    if (diagDrawGapMs >= 20L) {
      Log.w(DanmakuEngine.TAG, "[Diag] update gapSinceDraw=" + diagDrawGapMs + "ms cost=" + cost + "ms active=" + activeStates.size + " commands=" + (frame?.commands?.size ?: 0))
    }
    if (cost >= RUNTIME_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] update overload cost=${cost}ms release=${releaseMs}ms sync=${syncMs}ms " +
          "jump=${timelineResetMs}ms resetActive=${timelineReset?.activeCount ?: 0} " +
          "resetWaiting=${timelineReset?.waitingCount ?: 0} gen=${generationMs}ms " +
          "expire=${expireMs}ms enqueue=${enqueueMs}ms enqActive=$enqueuedActiveItems " +
          "promote=${promoteMs}ms promoted=$promotedItems " +
          "measure=${measureMs}ms scheduled=$scheduledMeasures layout=${layoutMs}ms frame=${frameMs}ms " +
          "active=${activeStates.size} waiting=${waitingStates.size} draw=${frame?.commands?.size ?: 0} " +
          "built=${newFrame?.commands?.size ?: -1} replaced=${if (replacedFrame) 1 else 0} " +
          "releaseFrames=${releaseProfile.frames} releaseCaches=${releaseProfile.caches} " +
          "releasePost=${releaseProfile.postMs}ms releaseRef=${releaseProfile.refMs}ms releaseRecycle=${releaseProfile.recycleMs}ms"
      )
    }
  }

  fun draw(canvas: Canvas, onRenderReady: () -> Unit) {
    val requestAt = SystemClock.elapsedRealtime()
    synchronized(this) {
      val drawStartedAt = SystemClock.elapsedRealtime()
      drawLocked(canvas, onRenderReady, drawStartedAt, drawStartedAt - requestAt)
    }
  }

  private fun drawLocked(
    canvas: Canvas,
    onRenderReady: () -> Unit,
    drawStartedAt: Long,
    syncWaitMs: Long
  ) {
    try {
      val liveFrame = frame
      val fallbackTransitionFrame = transitionFrame
      val liveFrameDrawable = liveFrame != null && liveFrame.commands.size > 0
      val transitionFrameDrawable =
        fallbackTransitionFrame != null && fallbackTransitionFrame.commands.size > 0 &&
          fallbackTransitionFrame.isTransitionAlive()
      val currentFrame = when {
        liveFrameDrawable -> liveFrame
        transitionFrameDrawable -> fallbackTransitionFrame
        else -> liveFrame ?: fallbackTransitionFrame
      }
      val drawingTransitionFrame = currentFrame === fallbackTransitionFrame
      val transitionElapsedMs = if (drawingTransitionFrame) {
        currentFrame?.transitionElapsedMs() ?: 0L
      } else {
        0L
      }
      val config = context.config
      if (!config.visibility || currentFrame == null) {
        logDrawStallIfNeeded(
          reason = when {
            !config.visibility -> "hidden"
            currentFrame == null -> "noFrame"
            else -> "unavailable"
          },
          liveFrame = liveFrame,
          transitionFrame = fallbackTransitionFrame,
          currentFrame = currentFrame,
          drawn = 0,
          skippedOutside = 0,
          commandCount = currentFrame?.commands?.size ?: 0
        )
        return
      }
      val generationMismatch =
        currentFrame.visibilityGeneration != config.visibilityGeneration
      val canDrawStaleFrame =
        drawingTransitionFrame && currentFrame.isTransitionAlive()
      if (generationMismatch && !canDrawStaleFrame) {
        logDrawStallIfNeeded(
          reason = "visibility:${currentFrame.visibilityGeneration}->${config.visibilityGeneration}",
          liveFrame = liveFrame,
          transitionFrame = fallbackTransitionFrame,
          currentFrame = currentFrame,
          drawn = 0,
          skippedOutside = 0,
          commandCount = currentFrame.commands.size
        )
        return
      }

      var hit = 0
      var fallbackDraws = 0
      var rollingFallbackDraws = 0
      var fixedFallbackDraws = 0
      var fallbackSkipped = 0
      var fallbackCacheMiss = 0
      var fallbackUnmeasured = 0
      var fallbackCacheBoosts = 0
      var visibleCommandCount = 0
      var skippedOutside = 0
      val now = context.timer.currentTimeMs
      val commandCount = currentFrame.commands.size
      // alpha 只依赖 config，整个 draw 循环不变，提前算一次避免每条弹幕重复乘法。
      val drawAlpha = (config.alpha * 255).toInt().coerceIn(0, 255)
      var diagDrawCacheNs = 0L
      var diagDrawTextNs = 0L
      // 采样式诊断：与 shouldProfileLayoutFrame 共用同一 tick，非采样帧跳过 per-item nanos
      // 系统调用（消除每条弹幕每帧 2 次 SystemClock.elapsedRealtimeNanos），采样帧仍精确归因。
      val profileDrawDetails = shouldProfileLayoutFrame()
      for (index in 0 until commandCount) {
        val item = currentFrame.commands.itemAt(index)
        if (!drawingTransitionFrame && item.isRuntimeOutside(now)) {
          skippedOutside++
          continue
        }
        val fixedCommand = index >= currentFrame.fixedCommandStartIndex
        visibleCommandCount++
        val diagCmdT0 = if (profileDrawDetails) SystemClock.elapsedRealtimeNanos() else 0L
        val drawResult = drawCommand(
          canvas = canvas,
          frame = currentFrame,
          commands = currentFrame.commands,
          index = index,
          config = config,
          allowFallback = if (fixedCommand) {
            fixedFallbackDraws < MAX_FIXED_FALLBACK_DRAWS_PER_FRAME
          } else {
            rollingFallbackDraws < MAX_FALLBACK_DRAWS_PER_FRAME
          },
          transitionElapsedMs = transitionElapsedMs,
          drawAlpha = drawAlpha
        )
        if (profileDrawDetails) {
          val diagCmdNs = SystemClock.elapsedRealtimeNanos() - diagCmdT0
          if (drawResult.cacheHit) diagDrawCacheNs += diagCmdNs else if (drawResult.fallbackDrawn) diagDrawTextNs += diagCmdNs
        }
        if (drawResult.cacheHit) {
          hit++
        } else if (drawResult.fallbackDrawn) {
          fallbackDraws++
          if (fixedCommand) {
            fixedFallbackDraws++
          } else {
            rollingFallbackDraws++
          }
        } else {
          fallbackSkipped++
          when (drawResult.skipReason) {
            DrawCommandResult.SKIP_CACHE_MISS -> {
              fallbackCacheMiss++
              if (fallbackCacheBoosts < MAX_FALLBACK_CACHE_BOOSTS_PER_FRAME &&
                boostVisibleCacheIfNeeded(item, config)) {
                fallbackCacheBoosts++
              }
            }
            DrawCommandResult.SKIP_UNMEASURED -> fallbackUnmeasured++
          }
        }
        dispatchShown(item, config)
      }
      if (visibleCommandCount == 0 || fallbackDraws + hit == 0) {
        logDrawStallIfNeeded(
          reason = if (visibleCommandCount == 0) "noVisibleCommand" else "noDrawnCommand",
          liveFrame = liveFrame,
          transitionFrame = fallbackTransitionFrame,
          currentFrame = currentFrame,
          drawn = fallbackDraws + hit,
          skippedOutside = skippedOutside,
          commandCount = commandCount
        )
      }
      if (fallbackSkipped > 0 && shouldLogFallbackLimit()) {
        Log.w(
          DanmakuEngine.TAG,
          "[Runtime] draw fallback limited drawn=$fallbackDraws skipped=$fallbackSkipped " +
            "rollingDrawn=$rollingFallbackDraws fixedDrawn=$fixedFallbackDraws " +
            "cacheMiss=$fallbackCacheMiss unmeasured=$fallbackUnmeasured " +
            "boosts=$fallbackCacheBoosts commands=$commandCount"
        )
      }
      cacheHit.num = hit
      cacheHit.den = visibleCommandCount
      val drawCostMs = SystemClock.elapsedRealtime() - drawStartedAt
      val topSummary = if (syncWaitMs >= 5L || drawCostMs >= 15L) {
        summarizeCommandTops(currentFrame.commands, currentFrame.fixedCommandStartIndex)
      } else {
        ""
      }
      if (syncWaitMs >= 5L) {
        Log.w(
          DanmakuEngine.TAG,
          "[Diag] draw syncWait=${syncWaitMs}ms held=${drawCostMs}ms commands=$commandCount " +
            "visible=$visibleCommandCount cacheHit=$hit fallback=$fallbackDraws skipped=$fallbackSkipped $topSummary"
        )
      }
      if (drawCostMs >= 15L) {
        Log.w(DanmakuEngine.TAG, "[Diag] draw held=" + drawCostMs + "ms commands=" + commandCount + " cacheHit=" + hit + " cacheMiss=" + (visibleCommandCount - hit - fallbackDraws) + " fallback=" + fallbackDraws + " drawCache=" + (diagDrawCacheNs / 1000000) + "ms drawText=" + (diagDrawTextNs / 1000000) + "ms " + topSummary)
      }
    } finally {
      lastDrawLockReleasedAt = SystemClock.elapsedRealtime()
      onRenderReady()
    }
  }

  private fun logDrawStallIfNeeded(
    reason: String,
    liveFrame: RuntimeFrame?,
    transitionFrame: RuntimeFrame?,
    currentFrame: RuntimeFrame?,
    drawn: Int,
    skippedOutside: Int,
    commandCount: Int
  ) {
    val elapsed = SystemClock.elapsedRealtime()
    if (elapsed - lastDrawStallLogAtMs < DRAW_STALL_LOG_INTERVAL_MS) return
    lastDrawStallLogAtMs = elapsed
    Log.w(
      DanmakuEngine.TAG,
      "[Runtime] draw stall reason=$reason now=${context.timer.currentTimeMs}ms " +
        "live=${liveFrame?.commands?.size ?: -1} transition=${transitionFrame?.commands?.size ?: -1} " +
        "current=${currentFrame?.commands?.size ?: -1} commands=$commandCount drawn=$drawn " +
        "outside=$skippedOutside active=${activeStates.size} waiting=${waitingStates.size} " +
        "pending=${pendingAddItems.size} sorted=${sortedItems.size} scan=${timelineWindow.scanIndex} " +
        "measureQueue=${measureQueue.size} visible=${context.config.visibility} " +
        "size=${context.displayer.width}x${context.displayer.height}"
    )
  }

  private fun shouldLogFallbackLimit(): Boolean {
    fallbackLimitLogTick++
    return fallbackLimitLogTick == 1 || fallbackLimitLogTick % FALLBACK_LIMIT_LOG_INTERVAL == 0
  }

  fun getDanmakus(point: Point): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val x = point.x.toFloat()
    val y = point.y.toFloat()
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    val now = context.timer.currentTimeMs
    for (index in 0 until commands.size) {
      val item = commands.itemAt(index)
      if (item.isRuntimeOutside(now)) continue
      if (x >= commands.leftAt(index) && x <= commands.rightAt(index) &&
        y >= commands.topAt(index) && y <= commands.bottomAt(index)) {
        result.add(item)
      }
    }
    return result
  }

  @Synchronized
  fun diagnosticSummary(): String {
    val liveFrame = frame
    val transition = transitionFrame
    return "active=${activeStates.size} waiting=${waitingStates.size} pending=${pendingAddItems.size} " +
      "sorted=${sortedItems.size} scan=${timelineWindow.scanIndex} measureQueue=${measureQueue.size} " +
      "commands=${liveFrame?.commands?.size ?: 0} transition=${transitionFrame?.commands?.size ?: 0} " +
      "cacheHit=${cacheHit.num}/${cacheHit.den}"
  }

  fun getDanmakus(rect: RectF): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    val now = context.timer.currentTimeMs
    for (index in 0 until commands.size) {
      val item = commands.itemAt(index)
      if (item.isRuntimeOutside(now)) continue
      if (rect.left < commands.rightAt(index) && rect.right > commands.leftAt(index) &&
        rect.top < commands.bottomAt(index) && rect.bottom > commands.topAt(index)) {
        result.add(item)
      }
    }
    return result
  }

  @Synchronized
  fun release() {
    clearAllData()
    context.cacheManager.release()
  }

  @Synchronized
  fun syncTimerTo(positionMs: Long) {
    context.timer.start(positionMs)
    timelineAnchor.syncTo(positionMs)
  }

  private fun syncPendingData(): Int {
    val config = context.config
    val nowMs = context.timer.currentTimeMs
    val added = timelineWindow.syncPending(
      pending = pendingAddItems,
      liveMode = liveMode,
      nowMs = nowMs,
      durationMs = config.durationMs,
      rollingDurationMs = config.rollingDurationMs
    )
    return added
  }

  private fun resetForTimelineJumpIfNeeded(now: Long, config: DanmakuConfig): TimelineResetProfile? {
    val jump = timelineAnchor.updateAndCheckJump(
      currentMs = now,
      durationMs = config.durationMs,
      rollingDurationMs = config.rollingDurationMs
    )
    if (!jump.isJump) {
      return null
    }
    val profile = resetRuntimeWindow(now, config, keepCurrentFrame = true)
    Log.i(
      DanmakuEngine.TAG,
      "[Runtime] timeline jump reset previous=${jump.previousMs}ms now=${now}ms " +
        "active=${profile.activeCount} waiting=${profile.waitingCount} frame=${profile.hadFrame}"
    )
    return profile
  }

  @Synchronized
  fun clearRuntimeData(keepCurrentFrame: Boolean) {
    val config = context.config
    resetRuntimeWindow(context.timer.currentTimeMs, config, keepCurrentFrame)
    if (keepCurrentFrame) {
      timelineAnchor.syncTo(context.timer.currentTimeMs)
    } else {
      timelineAnchor.clear()
    }
  }

  private fun resetRuntimeWindow(
    positionMs: Long,
    config: DanmakuConfig,
    keepCurrentFrame: Boolean
  ): TimelineResetProfile {
    val activeCount = activeStates.size
    val waitingCount = waitingStates.size
    val hadFrame = frame != null || pendingReleaseFrames.isNotEmpty()
    timelineWindow.reset(positionMs, config.durationMs, config.rollingDurationMs)
    activeStates.forEach { state ->
      val item = state.item
      item.cacheRecycle()
      item.drawState.layoutGeneration = -1
      recycleState(state)
    }
    waitingStates.forEach { state ->
      val item = state.item
      item.cacheRecycle()
      item.drawState.layoutGeneration = -1
      recycleState(state)
    }
    activeStates.clear()
    waitingStates.clear()
    measureCandidates.clear()
    measureQueue.clear()
    stateById.clear()
    clearTracks()
    if (keepCurrentFrame) {
      promoteCurrentFrameToTransition()
    } else {
      releaseFrame(frame)
      releaseFrame(transitionFrame)
      transitionFrame = null
    }
    frame = null
    releaseAllPendingFrames()
    return TimelineResetProfile(activeCount, waitingCount, hadFrame)
  }

  private fun removeExpired(now: Long, config: DanmakuConfig, frameDeltaMs: Long): Int {
    val iterator = activeStates.iterator()
    var expired = 0
    // 卡顿期 now 单帧跳变(本帧 delta 远超滚动平均):这一拍跳过滚动弹幕的 timeout 移除,
    // 等下一拍 now 稳定再判——避免还在屏幕中段的滚动弹幕被误杀(滚动到一半消失)。
    val skipRollingExpiry = frameDeltaMs > smoothedFrameDeltaMs * 3 && smoothedFrameDeltaMs > 0L
    while (iterator.hasNext()) {
      val state = iterator.next()
      val item = state.item
      item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
        config.rollingDurationMs
      } else {
        config.durationMs
      }
      val isRolling = item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING
      if (skipRollingExpiry && isRolling) {
        // 滚动弹幕在卡顿跳变帧保留,下一拍再判
        continue
      }
      if (item.isRuntimeTimeout(now)) {
        stateById.remove(item.data.danmakuId)
        removeFromTracks(item)
        item.cacheRecycle()
        iterator.remove()
        recycleState(state)
        expired++
      }
    }
    return expired
  }

  private fun enqueueDueItems(now: Long, config: DanmakuConfig): Int {
    var activeAdded = 0
    val startedAt = SystemClock.elapsedRealtime()
    val enqueueBudget = MAX_ENQUEUE_PER_FRAME
    timelineWindow.enqueueDue(
      nowMs = now,
      durationMs = config.durationMs,
      rollingDurationMs = config.rollingDurationMs,
      entryAheadMs = entryAheadMs(config),
      enqueueBudget = enqueueBudget,
      shouldSkipItem = { false },
      shouldStopAfterAdded = { addedCount ->
        addedCount >= MIN_ENQUEUE_PER_FRAME &&
          SystemClock.elapsedRealtime() - startedAt >= ENQUEUE_BUDGET_MS
      }
    ) { item ->
      if (!stateById.containsKey(item.data.danmakuId)) {
        item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
          config.rollingDurationMs
        } else {
          config.durationMs
        }
        item.drawState.layoutGeneration = -1
        item.rollingStartTimeMs = ROLLING_START_TIME_UNSET
        item.rollingMotionWidth = 0f
        val state = acquireState(item)
        enqueueMeasureState(state)
        if (item.timePosition <= now) {
          activeStates.add(state)
          activeAdded++
        } else {
          waitingStates.add(state)
        }
        stateById[item.data.danmakuId] = state
        true
      } else {
        false
      }
    }
    return activeAdded
  }

  private fun promoteWaitingItems(now: Long): Int {
    if (waitingStates.isEmpty()) return 0
    var promoteCount = 0
    while (promoteCount < waitingStates.size && waitingStates[promoteCount].item.timePosition <= now) {
      promoteCount++
    }
    if (promoteCount == 0) return 0
    repeat(promoteCount) {
      val state = waitingStates[it]
      activeStates.add(state)
    }
    waitingStates.subList(0, promoteCount).clear()
    return promoteCount
  }

  private fun scheduleMeasureActiveItems(config: DanmakuConfig): Int {
    var scheduled = 0
    var cacheHits = 0
    var scanned = 0
    val startedAt = SystemClock.elapsedRealtime()
    measureCandidates.clear()
    while (measureQueue.isNotEmpty() && scanned < MAX_MEASURE_QUEUE_SCAN_PER_FRAME) {
      val entry = measureQueue.removeFirst()
      val state = entry.state
      val token = entry.token
      recycleMeasureEntry(entry)
      if (!state.inMeasureQueue || state.measureQueueToken != token) continue
      state.inMeasureQueue = false
      scanned++
      val item = state.item
      if (item == DanmakuItem.DANMAKU_ITEM_EMPTY) continue
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) {
        state.awaitingMeasure = false
        continue
      }
      if (item.timePosition > context.timer.currentTimeMs + entryAheadMs(config)) {
        enqueueMeasureState(state)
        break
      }
      measureCandidates.add(state)
      if (measureCandidates.size >= MAX_MEASURE_CANDIDATES_PER_FRAME) break
    }
    val candidates = DanmakuMeasureScheduler.collectCandidates(
      items = measureCandidates,
      nowMs = context.timer.currentTimeMs,
      measureGeneration = config.measureGeneration,
      maxCount = MAX_MEASURE_PER_FRAME,
      scheduleAheadMs = entryAheadMs(config)
    )
    for (candidate in candidates) {
      if (SystemClock.elapsedRealtime() - startedAt >= MEASURE_SCHEDULE_BUDGET_MS) break
      val item = candidate.item
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        if (cacheHits >= MAX_MEASURE_CACHE_HITS_PER_FRAME) {
          enqueueMeasureState(candidate)
          continue
        }
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        item.pendingMeasureGeneration = -1
        candidate.awaitingMeasure = false
        cacheHits++
        continue
      }
      // 测量可能触发字体、描边和样式初始化，放到缓存线程，避免 action 线程一帧吃掉几百毫秒。
      item.state = ItemState.Measuring
      item.pendingMeasureGeneration = config.measureGeneration
      context.cacheManager.requestMeasure(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = candidate.cachePriority(context.timer.currentTimeMs, entryAheadMs(config))
      )
      scheduled++
    }
    return scheduled
  }

  private fun entryAheadMs(config: DanmakuConfig): Long =
    max(PREPARE_AHEAD_MS, config.preCacheTimeMs)


  private fun layoutAndBuildFrame(now: Long, config: DanmakuConfig): RuntimeFrame {
    val displayer = context.displayer
    val newFrame = framePool.acquire(visibilityGeneration)
    val startedAt = SystemClock.elapsedRealtime()
    val profileDetails = shouldProfileLayoutFrame()
    var filterMs = 0L
    var trackMs = 0L
    var cacheMs = 0L
    var commandMs = 0L
    var retainMs = 0L
    var fixedMergeMs = 0L
    var outsideCheckMs = 0L
    var measureCheckMs = 0L
    var filteredCount = 0
    var unmeasuredCount = 0
    var outsideCount = 0
    var trackRejectedCount = 0
    var trackFastCount = 0
    var trackLayoutCount = 0
    var cacheRequestedCount = 0
    var cacheDeferredCount = 0
    var cacheRequestBudget = MAX_CACHE_REQUESTS_PER_FRAME
    val fixedCommandBudget = fixedCommandBudgetForCurrentLoad()
    var fixedCommandCount = 0
    val width = displayer.width
    val height = displayer.height
    val margin = displayer.margin
    val layoutGeneration = config.layoutGeneration
    val measureGeneration = config.measureGeneration
    val cacheGeneration = config.cacheGeneration
    val rollingDurationMs = config.rollingDurationMs
    val topMode = DanmakuItemData.DANMAKU_MODE_CENTER_TOP
    val bottomMode = DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
    // entryAheadMs 只依赖 config，整个循环不变，提前算一次避免每条弹幕重复计算。
    val aheadMs = entryAheadMs(config)
    var activeIndex = 0
    while (activeIndex < activeStates.size) {
      val state = activeStates[activeIndex]
      val item = state.item
      var removedState = false
      var stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      if (item.isRuntimeOutside(now)) {
        if (profileDetails) outsideCheckMs += SystemClock.elapsedRealtime() - stepAt
        outsideCount++
        activeIndex++
        continue
      }
      if (profileDetails) outsideCheckMs += SystemClock.elapsedRealtime() - stepAt

      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val drawState = item.drawState
      if (item.state < ItemState.Measured || !drawState.isMeasured(measureGeneration)) {
        if (profileDetails) measureCheckMs += SystemClock.elapsedRealtime() - stepAt
        enqueueMeasureState(state)
        unmeasuredCount++
        activeIndex++
        continue
      }
      if (profileDetails) measureCheckMs += SystemClock.elapsedRealtime() - stepAt
      state.awaitingMeasure = false
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      if (isDataFiltered(item, config)) {
        if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt
        filteredCount++
        activeIndex++
        continue
      }
      if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt

      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val mode = item.data.mode
      val visible = if (drawState.layoutGeneration == layoutGeneration) {
        val updated = trackLayout.updateExisting(item, width, height, config)
        if (updated) {
          trackFastCount++
          true
        } else {
          trackLayoutCount++
          trackLayout.layout(item, now, width, height, margin, config)
        }
      } else {
        trackLayoutCount++
        trackLayout.layout(item, now, width, height, margin, config)
      }
      if (profileDetails) trackMs += SystemClock.elapsedRealtime() - stepAt
      if (!visible) {
        trackRejectedCount++
        if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
          stateById.remove(item.data.danmakuId)
          activeStates.removeAt(activeIndex)
          dropActiveState(state)
          removedState = true
        }
        if (!removedState) activeIndex++
        continue
      }
      if (cacheRequestBudget > 0) {
        stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
        val requested = requestCacheBuildIfNeeded(item, config, state.cachePriority(now, aheadMs))
        if (profileDetails) cacheMs += SystemClock.elapsedRealtime() - stepAt
        if (requested) {
          cacheRequestBudget--
          cacheRequestedCount++
        }
      } else if (item.state < ItemState.Rendering) {
        // 首屏窗口可能瞬间出现十几条弹幕，缓存构建分帧排队；未排到的先走直接绘制兜底。
        cacheDeferredCount++
      }
      if (item.state >= ItemState.Rendered && drawState.cacheGeneration != cacheGeneration) {
        item.cacheRecycle()
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val cache = drawState.drawingCache
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
        newFrame.retainCache(cache)
        if (profileDetails) retainMs += SystemClock.elapsedRealtime() - stepAt
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val left = if (mode == topMode || mode == bottomMode) {
        drawState.positionX
      } else {
        rollingLeft(item, now, width, rollingDurationMs)
      }
      val top = drawState.positionY
      val right = left + drawState.width
      val bottom = top + drawState.height
      if (mode == topMode || mode == bottomMode) {
        if (fixedCommandCount >= fixedCommandBudget) {
          trackRejectedCount++
          if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
            stateById.remove(item.data.danmakuId)
            activeStates.removeAt(activeIndex)
            dropActiveState(state)
            removedState = true
          }
          if (!removedState) activeIndex++
          continue
        }
        // 悬停弹幕始终最后绘制，保证层级压在滚动弹幕上方。
        newFrame.fixedCommands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
        fixedCommandCount++
      } else {
        newFrame.commands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
      }
      if (profileDetails) commandMs += SystemClock.elapsedRealtime() - stepAt
      activeIndex++
    }
    val fixedMergeStartedAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
    newFrame.markFixedCommandStart(newFrame.commands.size)
    newFrame.commands.addAll(newFrame.fixedCommands)
    newFrame.fixedCommands.clear()
    if (profileDetails) fixedMergeMs = SystemClock.elapsedRealtime() - fixedMergeStartedAt
    val cost = SystemClock.elapsedRealtime() - startedAt
    if (cost >= LAYOUT_OVERLOAD_MS) {
      val topSummary = summarizeCommandTops(newFrame.commands, newFrame.fixedCommandStartIndex)
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] layout profile cost=${cost}ms outside=${outsideCheckMs}ms measureCheck=${measureCheckMs}ms " +
          "filter=${filterMs}ms track=${trackMs}ms cache=${cacheMs}ms retain=${retainMs}ms " +
          "command=${commandMs}ms fixedMerge=${fixedMergeMs}ms active=${activeStates.size} waiting=${waitingStates.size} " +
          "draw=${newFrame.commands.size} outside=$outsideCount " +
          "unmeasured=$unmeasuredCount filtered=$filteredCount rejected=$trackRejectedCount " +
          "trackFast=$trackFastCount trackLayout=$trackLayoutCount cacheReq=$cacheRequestedCount " +
          "cacheDef=$cacheDeferredCount $topSummary"
      )
    }
    logZeroFrameIfNeeded(
      now = now,
      config = config,
      commandCount = newFrame.commands.size,
      outsideCount = outsideCount,
      unmeasuredCount = unmeasuredCount,
      filteredCount = filteredCount,
      trackRejectedCount = trackRejectedCount,
      cacheRequestedCount = cacheRequestedCount,
      cacheDeferredCount = cacheDeferredCount
    )
    return newFrame
  }

  private fun fixedCommandBudgetForCurrentLoad(): Int = Int.MAX_VALUE

  private fun logZeroFrameIfNeeded(
    now: Long,
    config: DanmakuConfig,
    commandCount: Int,
    outsideCount: Int,
    unmeasuredCount: Int,
    filteredCount: Int,
    trackRejectedCount: Int,
    cacheRequestedCount: Int,
    cacheDeferredCount: Int
  ) {
    if (commandCount > 0 || activeStates.isEmpty()) return
    val elapsed = SystemClock.elapsedRealtime()
    if (elapsed - lastZeroFrameLogAtMs < ZERO_FRAME_LOG_INTERVAL_MS) return
    lastZeroFrameLogAtMs = elapsed
    var measuredVisibleCandidates = 0
    var sample = ""
    activeStates.take(ZERO_FRAME_SAMPLE_LIMIT).forEachIndexed { index, state ->
      val item = state.item
      val drawState = item.drawState
      val measured = item.state >= ItemState.Measured && drawState.isMeasured(config.measureGeneration)
      if (measured && !item.isRuntimeOutside(now) && !isDataFiltered(item, config)) {
        measuredVisibleCandidates++
      }
      if (sample.isNotEmpty()) sample += ";"
      sample += "#$index{id=${item.data.danmakuId},pos=${item.timePosition},dur=${item.duration}," +
        "mode=${item.data.mode},state=${item.state},measured=$measured," +
        "outside=${item.isRuntimeOutside(now)},layoutGen=${drawState.layoutGeneration}}"
    }
    Log.w(
      DanmakuEngine.TAG,
      "[Runtime] zero frame now=${now}ms active=${activeStates.size} waiting=${waitingStates.size} " +
        "outside=$outsideCount unmeasured=$unmeasuredCount filtered=$filteredCount " +
        "rejected=$trackRejectedCount measuredVisible=$measuredVisibleCandidates " +
        "cacheReq=$cacheRequestedCount cacheDef=$cacheDeferredCount " +
        "size=${context.displayer.width}x${context.displayer.height} sample=$sample"
    )
  }

  private fun rollingLeft(
    item: DanmakuItem,
    nowMs: Long,
    screenWidth: Int,
    durationMs: Long
  ): Float {
    val startTime = RollingDanmakuTiming.resolvedStartTime(item.rollingStartTimeMs, nowMs)
    return RollingDanmakuTiming.positionX(
      screenWidth = screenWidth,
      itemWidth = item.rollingMotionWidth.takeIf { it > 0f } ?: item.drawState.width,
      nowMs = nowMs,
      startTimeMs = startTime,
      durationMs = durationMs
    )
  }

  private fun isDataFiltered(item: DanmakuItem, config: DanmakuConfig): Boolean {
    val filters = context.filter
    if (filterCacheableGeneration != config.filterGeneration) {
      filterCacheableGeneration = config.filterGeneration
      filterResultCacheable = filters.isDataFilterResultCacheable
    }
    if (!filterResultCacheable) {
      return filters.isDataFiltered(item, context.timer, config)
    }
    if (item.filterGeneration == config.filterGeneration) {
      return item.filteredInGeneration
    }
    val filtered = filters.isDataFiltered(item, context.timer, config)
    item.filterGeneration = config.filterGeneration
    item.filteredInGeneration = filtered
    return filtered
  }

  private fun requestCacheBuildIfNeeded(
    item: DanmakuItem,
    config: DanmakuConfig,
    priority: Int
  ): Boolean {
    val drawState = item.drawState
    if (!drawState.isMeasured(config.measureGeneration)) return false
    if (item.state >= ItemState.Rendering &&
      item.pendingCacheGeneration == config.cacheGeneration) return false
    if (item.state >= ItemState.Rendered &&
      drawState.cacheGeneration == config.cacheGeneration &&
      drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      drawState.drawingCache.get() != null) return false
    item.state = ItemState.Rendering
    item.pendingCacheGeneration = config.cacheGeneration
    context.cacheManager.requestBuildCache(
      item = item,
      displayer = context.displayer,
      config = config,
      priority = priority
    )
    return true
  }


  private fun logFrameStallIfNeeded(
    now: Long,
    config: DanmakuConfig,
    syncedItems: Int,
    enqueuedActiveItems: Int,
    promotedItems: Int,
    scheduledMeasures: Int,
    builtCommands: Int,
    replacedFrame: Boolean
  ) {
    val commandCount = frame?.commands?.size ?: 0
    if (commandCount > 0) return
    if (activeStates.isEmpty() && waitingStates.isEmpty() && pendingAddItems.isEmpty()) return
    val elapsed = SystemClock.elapsedRealtime()
    if (elapsed - lastFrameStallLogAtMs < FRAME_STALL_LOG_INTERVAL_MS) return
    lastFrameStallLogAtMs = elapsed

    var uninitialized = 0
    var measuring = 0
    var measuredOrAbove = 0
    var error = 0
    fun countState(state: ActiveItemState) {
      when (state.item.state) {
        ItemState.Uninitialized -> uninitialized++
        ItemState.Measuring -> measuring++
        ItemState.Error -> error++
        else -> measuredOrAbove++
      }
    }
    activeStates.forEach(::countState)
    waitingStates.forEach(::countState)
    val displayer = context.displayer
    Log.w(
      DanmakuEngine.TAG,
      "[Runtime] frame stall now=${now}ms active=${activeStates.size} waiting=${waitingStates.size} " +
        "pending=${pendingAddItems.size} sorted=${sortedItems.size} scan=${timelineWindow.scanIndex} " +
        "commands=$commandCount built=$builtCommands replaced=${if (replacedFrame) 1 else 0} " +
        "synced=$syncedItems enqActive=$enqueuedActiveItems promoted=$promotedItems " +
        "scheduledMeasure=$scheduledMeasures measureQueue=${measureQueue.size} " +
        "stateU=$uninitialized stateM=$measuring stateReady=$measuredOrAbove stateErr=$error " +
        "visible=${config.visibility} size=${displayer.width}x${displayer.height} screenPart=${config.screenPart}"
    )
  }

  private fun drawCommand(
    canvas: Canvas,
    frame: RuntimeFrame,
    commands: CommandBuffer,
    index: Int,
    config: DanmakuConfig,
    allowFallback: Boolean,
    transitionElapsedMs: Long,
    drawAlpha: Int
  ): DrawCommandResult {
    val item = commands.itemAt(index)
    val drawState = item.drawState
    if (item.state < ItemState.Measured || !drawState.isMeasured(config.measureGeneration)) {
      return DrawCommandResult.UNMEASURED_SKIPPED
    }
    val cache = resolveCommandCache(frame, commands, index, item, config.cacheGeneration)
    val left = resolveCommandLeft(commands, index, config, transitionElapsedMs)
    val top = commands.topAt(index)
    if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
      val bitmap = cache.get()?.bitmap
      if (bitmap != null && !bitmap.isRecycled) {
        drawPaint.alpha = drawAlpha
        canvas.drawBitmap(bitmap, left, top, drawPaint)
        return DrawCommandResult.CACHE_HIT
      }
    }
    if (!allowFallback) return DrawCommandResult.CACHE_MISS_SKIPPED
    // tryDrawRenderer 固定返回 true（drawRenderer 无失败路径），去掉 drawn 恒真判断与冗余间接层。
    canvas.withTranslation(left, top) {
      context.drawRenderer(item, canvas, context.displayer, config)
    }
    return DrawCommandResult.FALLBACK_DRAWN
  }

  private fun boostVisibleCacheIfNeeded(item: DanmakuItem, config: DanmakuConfig): Boolean {
    val drawState = item.drawState
    if (!drawState.isMeasured(config.measureGeneration)) return false
    if (item.state >= ItemState.Rendered &&
      drawState.cacheGeneration == config.cacheGeneration &&
      drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      drawState.drawingCache.get() != null) return false
    if (item.state >= ItemState.Rendering &&
      item.pendingCacheGeneration == config.cacheGeneration) {
      context.cacheManager.requestBuildCache(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = CACHE_PRIORITY_VISIBLE
      )
      return true
    }
    return requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)
  }

  private fun resolveCommandCache(
    frame: RuntimeFrame,
    commands: CommandBuffer,
    index: Int,
    item: DanmakuItem,
    cacheGeneration: Int
  ): DrawingCache {
    val commandCache = commands.cacheAt(index)
    if (commandCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      commands.cacheGenerationAt(index) == cacheGeneration) {
      return commandCache
    }
    val drawState = item.drawState
    val latestCache = drawState.drawingCache
    if (latestCache == DrawingCache.EMPTY_DRAWING_CACHE ||
      drawState.cacheGeneration != cacheGeneration ||
      latestCache.get() == null) {
      return DrawingCache.EMPTY_DRAWING_CACHE
    }
    frame.retainCache(latestCache)
    return latestCache
  }

  private fun resolveCommandLeft(
    commands: CommandBuffer,
    index: Int,
    config: DanmakuConfig,
    transitionElapsedMs: Long
  ): Float {
    val left = commands.leftAt(index)
    val item = commands.itemAt(index)
    if (item.data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) return left
    val itemDuration = item.duration
    val durationMs = if (itemDuration > 0L) itemDuration else config.rollingDurationMs
    if (durationMs <= 0L) return left
    return if (transitionElapsedMs > 0L) {
      val width = commands.rightAt(index) - left
      val distance = context.displayer.width + width
      left - transitionElapsedMs.toFloat() / durationMs * distance
    } else {
      rollingLeft(item, context.timer.currentTimeMs, context.displayer.width, durationMs)
    }
  }

  private fun summarizeCommandTops(commands: CommandBuffer, fixedCommandStartIndex: Int): String {
    if (commands.size == 0) return "topDist=empty"
    val counts = HashMap<Int, Int>(commands.size)
    var maxSameTop = 0
    var rollingCount = 0
    var fixedCount = 0
    val sample = StringBuilder()
    val sampleLimit = 8
    for (index in 0 until commands.size) {
      val top = commands.topAt(index).toInt()
      val count = (counts[top] ?: 0) + 1
      counts[top] = count
      if (count > maxSameTop) maxSameTop = count
      if (index < fixedCommandStartIndex) {
        rollingCount++
      } else {
        fixedCount++
      }
      if (index < sampleLimit) {
        if (sample.isNotEmpty()) sample.append(',')
        sample.append(top)
      }
    }
    return "topDist unique=${counts.size} maxSame=$maxSameTop rolling=$rollingCount fixed=$fixedCount sample=$sample"
  }

  private fun dispatchShown(item: DanmakuItem, config: DanmakuConfig) {
    val target = listener ?: return
    if (item.shownGeneration == config.firstShownGeneration) return
    item.shownGeneration = config.firstShownGeneration
    callbackHandler.post { target.onDanmakuShown(item) }
  }

  private fun replaceFrame(newFrame: RuntimeFrame) {
    frame?.let { pendingReleaseFrames.add(it) }
    transitionFrame?.let { pendingReleaseFrames.add(it) }
    transitionFrame = null
    frame = newFrame
  }

  private fun shouldReplaceWithFrame(newFrame: RuntimeFrame): Boolean {
    if (newFrame.commands.size > 0) return true
    val liveFrame = frame
    if (liveFrame != null && liveFrame.commands.size > 0 &&
      (activeStates.isNotEmpty() || waitingStates.isNotEmpty())) {
      return false
    }
    val transition = transitionFrame
    if (transition != null && transition.isTransitionAlive() &&
      transition.commands.size > 0 && (activeStates.isNotEmpty() || waitingStates.isNotEmpty())) {
      return false
    }
    return transitionFrame == null
  }

  private fun promoteCurrentFrameToTransition() {
    val current = frame ?: return
    current.markTransition(SystemClock.elapsedRealtime())
    transitionFrame?.let { pendingReleaseFrames.add(it) }
    transitionFrame = current
  }

  private fun releasePendingFrames(): ReleaseProfile {
    if (pendingReleaseFrames.isEmpty()) return ReleaseProfile.EMPTY
    val startedAt = SystemClock.elapsedRealtime()
    var frameCount = 0
    var cacheCount = 0
    var releaseRefMs = 0L
    var recycleMs = 0L
    var remainingBudget = MAX_FRAME_CACHE_RELEASES_PER_UPDATE
    val iterator = pendingReleaseFrames.iterator()
    while (iterator.hasNext() && remainingBudget > 0) {
      val oldFrame = iterator.next()
      frameCount++
      val releaseStartedAt = SystemClock.elapsedRealtime()
      val retainedCaches = oldFrame.detachRetainedCaches(remainingBudget)
      cacheCount += retainedCaches.size
      remainingBudget -= retainedCaches.size
      context.cacheManager.releaseReferenceSnapshot(retainedCaches, FRAME_CACHE_RELEASE_DELAY_MS)
      releaseRefMs += SystemClock.elapsedRealtime() - releaseStartedAt
      if (!oldFrame.hasRetainedCaches()) {
        val recycleStartedAt = SystemClock.elapsedRealtime()
        framePool.release(oldFrame)
        iterator.remove()
        recycleMs += SystemClock.elapsedRealtime() - recycleStartedAt
      }
    }
    val postMs = SystemClock.elapsedRealtime() - startedAt
    return ReleaseProfile(frameCount, cacheCount, postMs, releaseRefMs, recycleMs)
  }

  private fun releaseAllPendingFrames() {
    if (pendingReleaseFrames.isEmpty()) return
    for (oldFrame in pendingReleaseFrames) {
      releaseFrame(oldFrame, delayMs = 0L)
    }
    pendingReleaseFrames.clear()
  }

  private fun releaseFrame(oldFrame: RuntimeFrame?, delayMs: Long = 0L) {
    oldFrame ?: return
    context.cacheManager.releaseReferenceSnapshot(oldFrame.detachRetainedCaches(), delayMs)
    framePool.release(oldFrame)
  }

  private fun shouldProfileLayoutFrame(): Boolean {
    layoutProfileTick++
    // 采样式诊断：每 30 帧采一次，保留周期性性能 profiling 能力的同时，
    // 消除非采样帧里循环内每条弹幕 10-15 次 SystemClock 系统调用开销。
    // 采样那帧仍有完整开销，但 30:1 比例下整体可忽略。
    return layoutProfileTick % 30 == 0
  }

  private class ReleaseProfile(
    val frames: Int,
    val caches: Int,
    val postMs: Long,
    val refMs: Long,
    val recycleMs: Long
  ) {
    companion object {
      val EMPTY = ReleaseProfile(0, 0, 0L, 0L, 0L)
    }
  }

  private class TimelineResetProfile(
    val activeCount: Int,
    val waitingCount: Int,
    val hadFrame: Boolean
  )

  private fun acquireState(item: DanmakuItem): ActiveItemState =
    statePool.removeLastOrNull()?.also { it.reset(item) } ?: ActiveItemState(item)

  private fun recycleState(state: ActiveItemState) {
    state.clear()
    if (statePool.size < MAX_ACTIVE_STATE_POOL_SIZE) {
      statePool.add(state)
    }
  }

  private fun dropActiveState(state: ActiveItemState) {
    val item = state.item
    removeFromTracks(item)
    item.cacheRecycle()
    recycleState(state)
  }

  private fun enqueueMeasureState(state: ActiveItemState) {
    if (state.inMeasureQueue) return
    state.awaitingMeasure = true
    state.inMeasureQueue = true
    state.measureQueueToken++
    val entry = measureEntryPool.removeLastOrNull() ?: MeasureQueueEntry()
    entry.state = state
    entry.token = state.measureQueueToken
    measureQueue.addLast(entry)
  }

  private fun recycleMeasureEntry(entry: MeasureQueueEntry) {
    if (measureEntryPool.size >= MAX_MEASURE_ENTRY_POOL_SIZE) return
    entry.clear()
    measureEntryPool.add(entry)
  }

  private fun clearTracks() {
    trackLayout.clear()
  }

  private fun removeFromTracks(item: DanmakuItem) {
    trackLayout.remove(item)
  }

  private fun DanmakuItem.isRuntimeTimeout(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isTimeout(now)
    }
    if (rollingStartTimeMs == ROLLING_START_TIME_UNSET) {
      return false
    }
    val startTime = RollingDanmakuTiming.resolvedStartTime(rollingStartTimeMs, now)
    return RollingDanmakuTiming.isTimeout(now, startTime, duration)
  }

  private fun DanmakuItem.isRuntimeOutside(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isOutside(now)
    }
    return isLate(now) || isRuntimeTimeout(now)
  }

  private class ActiveItemState(
    var item: DanmakuItem
  ) : DanmakuMeasureScheduler.MeasureCandidate {
    var awaitingMeasure: Boolean = true
    var inMeasureQueue: Boolean = false
    var measureQueueToken: Int = 0

    fun reset(item: DanmakuItem) {
      this.item = item
      awaitingMeasure = true
      inMeasureQueue = false
      measureQueueToken = 0
    }

    fun clear() {
      item = DanmakuItem.DANMAKU_ITEM_EMPTY
      awaitingMeasure = false
      inMeasureQueue = false
      measureQueueToken++
    }

    override val timePositionMs: Long
      get() = item.timePosition
    override val contentLength: Int
      get() = item.data.content.length
    override val measureState: ItemState
      get() = item.state

    override fun isMeasured(measureGeneration: Int): Boolean =
      item.drawState.isMeasured(measureGeneration)

    fun cachePriority(nowMs: Long, scheduleAheadMs: Long): Int {
      val distance = (item.timePosition - nowMs).coerceAtLeast(0L)
      return when {
        distance <= 0L -> 0
        distance <= scheduleAheadMs -> 1
        else -> 2
      }
    }
  }

  private class MeasureQueueEntry {
    lateinit var state: ActiveItemState
    var token: Int = 0

    fun clear() {
      state = EMPTY_ACTIVE_STATE
      token = 0
    }
  }

  companion object {
    private const val PREPARE_AHEAD_MS = 300L
    private const val MAX_MEASURE_PER_FRAME = 12
    private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
    private const val MAX_PRIME_CACHE_BUILDS = 24
    private const val MAX_FALLBACK_DRAWS_PER_FRAME = 2
    private const val MAX_FIXED_FALLBACK_DRAWS_PER_FRAME = 1
    private const val MAX_FALLBACK_CACHE_BOOSTS_PER_FRAME = 4
    private const val MAX_FRAME_CACHE_RELEASES_PER_UPDATE = 16
    private const val FALLBACK_LIMIT_LOG_INTERVAL = 30
    private const val ZERO_FRAME_LOG_INTERVAL_MS = 1_000L
    private const val ZERO_FRAME_SAMPLE_LIMIT = 3
    private const val MEASURE_SCHEDULE_BUDGET_MS = 2L
    private const val LIVE_HISTORY_MAX = 2000
    private const val RUNTIME_OVERLOAD_MS = 12L
    private const val LAYOUT_OVERLOAD_MS = 12L
    private const val LAYOUT_PROFILE_DETAIL_INTERVAL = 30
    private const val MAX_ACTIVE_STATE_POOL_SIZE = 512
    private const val MAX_MEASURE_ENTRY_POOL_SIZE = 512
    private const val MAX_RUNTIME_FRAME_POOL_SIZE = 3
    private const val MAX_MEASURE_QUEUE_SCAN_PER_FRAME = 24
    private const val MAX_MEASURE_CANDIDATES_PER_FRAME = 12
    private const val MAX_MEASURE_CACHE_HITS_PER_FRAME = 4
    private const val FRAME_CACHE_RELEASE_DELAY_MS = 48L
    private const val FRAME_STALL_LOG_INTERVAL_MS = 1_000L
    private const val DRAW_STALL_LOG_INTERVAL_MS = 1_000L
    private const val MIN_ENQUEUE_PER_FRAME = 4
    private const val MAX_ENQUEUE_PER_FRAME = 32
    private const val ENQUEUE_BUDGET_MS = 2L
    private const val CACHE_PRIORITY_VISIBLE = 0
    private val EMPTY_ACTIVE_STATE = ActiveItemState(DanmakuItem.DANMAKU_ITEM_EMPTY)
  }
}
