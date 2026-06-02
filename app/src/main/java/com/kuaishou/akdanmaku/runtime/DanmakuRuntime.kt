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
  private val activeItems = ArrayList<DanmakuItem>(256)
  private val measureCandidatePool = ArrayList<MeasureItemCandidate>(256)
  private val measureCandidates = ArrayList<MeasureItemCandidate>(256)
  private val activeIds = HashSet<Long>(512)

  private val rollingTracks = RollingTrackAllocator()
  private val topTracks = FixedTrackAllocator(fromBottom = false)
  private val bottomTracks = FixedTrackAllocator(fromBottom = true)
  private val drawPaint = Paint().apply {
    isAntiAlias = true
  }

  private var dataDirty = false
  private var scanIndex = 0
  private var layoutGeneration = -1
  private var measureGeneration = -1
  private var cacheGeneration = -1
  private var visibilityGeneration = -1
  private var layoutProfileTick = 0
  private var loadShedLevel = 0
  private var filterCacheableGeneration = -1
  private var filterResultCacheable = false

  @Volatile
  private var frame: RuntimeFrame? = null
  private val pendingReleaseFrames = ArrayList<RuntimeFrame>(3)
  private var holdingItem: DanmakuItem? = null

  fun warmUp() {
    context.cacheManager.warmUp()
  }

  @Synchronized
  fun addItems(items: Collection<DanmakuItem>) {
    pendingAddItems.addAll(items)
    dataDirty = true
  }

  @Synchronized
  fun primeMeasureItems(items: List<DanmakuItem>, maxCount: Int) {
    if (items.isEmpty() || maxCount <= 0) return
    val config = context.config
    var scheduled = 0
    var cacheHits = 0
    val startedAt = SystemClock.elapsedRealtime()
    for (item in items) {
      if (scheduled >= maxCount) break
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) continue
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        cacheHits++
        continue
      }
      // 首窗口提交后先把测量排进缓存线程，减少第一批 action 帧里的集中测量抖动。
      item.state = ItemState.Measuring
      context.cacheManager.requestMeasure(item, context.displayer, config)
      scheduled++
    }
    val costMs = SystemClock.elapsedRealtime() - startedAt
    if (items.size >= 96 || scheduled >= 24 || costMs >= 4L) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] prime measure scheduled=$scheduled cacheHit=$cacheHits total=${items.size} cost=${costMs}ms"
      )
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
      return
    }
    item.state = ItemState.Measuring
    context.cacheManager.requestMeasure(item, context.displayer, config)
  }

  @Synchronized
  fun addItem(item: DanmakuItem) {
    pendingAddItems.add(item)
    dataDirty = true
  }

  @Synchronized
  fun updateItem(item: DanmakuItem) {
    sortedItems.remove(item)
    pendingAddItems.add(item)
    dataDirty = true
  }

  @Synchronized
  fun clearAllData() {
    pendingAddItems.clear()
    sortedItems.clear()
    dataDirty = false
    activeItems.forEach { item ->
      item.cacheRecycle()
      item.reset()
    }
    activeItems.clear()
    measureCandidatePool.clear()
    measureCandidates.clear()
    activeIds.clear()
    scanIndex = 0
    holdingItem = null
    loadShedLevel = 0
    clearTracks()
    releaseFrame(frame)
    frame = null
    releasePendingFrames()
  }

  @Synchronized
  fun seekTo(positionMs: Long) {
    val config = context.config
    val start = positionMs - max(config.durationMs, config.rollingDurationMs)
    scanIndex = lowerBound(start)
    activeItems.forEach { item ->
      item.cacheRecycle()
      item.drawState.layoutGeneration = -1
    }
    activeItems.clear()
    measureCandidatePool.clear()
    measureCandidates.clear()
    activeIds.clear()
    loadShedLevel = 0
    clearTracks()
    releaseFrame(frame)
    frame = null
    releasePendingFrames()
  }

  @Synchronized
  fun hold(item: DanmakuItem?) {
    if (item == holdingItem) return
    holdingItem?.unhold()
    holdingItem = item
    item?.hold()
  }

  @Synchronized
  fun update() {
    val startedAt = SystemClock.elapsedRealtime()
    var checkpoint = startedAt
    releasePendingFrames()
    val releaseMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    syncPendingData()
    val syncMs = SystemClock.elapsedRealtime() - checkpoint

    val config = context.config
    checkpoint = SystemClock.elapsedRealtime()
    if (config.layoutGeneration != layoutGeneration) {
      clearTracks()
      activeItems.forEach { it.drawState.layoutGeneration = -1 }
      layoutGeneration = config.layoutGeneration
    }
    if (config.measureGeneration != measureGeneration) {
      activeItems.forEach {
        it.state = ItemState.Uninitialized
        it.drawState.recycle()
      }
      measureGeneration = config.measureGeneration
    }
    if (config.cacheGeneration != cacheGeneration) {
      activeItems.forEach { it.cacheRecycle() }
      cacheGeneration = config.cacheGeneration
    }
    visibilityGeneration = config.visibilityGeneration
    val generationMs = SystemClock.elapsedRealtime() - checkpoint

    val now = context.timer.currentTimeMs
    checkpoint = SystemClock.elapsedRealtime()
    removeExpired(now, config)
    val expireMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    enqueueDueItems(now, config)
    val enqueueMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val scheduledMeasures = scheduleMeasureActiveItems(config)
    val measureMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val commands = layoutAndBuildFrame(now, config)
    val layoutMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    replaceFrame(RuntimeFrame(commands, visibilityGeneration))
    val frameMs = SystemClock.elapsedRealtime() - checkpoint

    val cost = SystemClock.elapsedRealtime() - startedAt
    if (cost >= RUNTIME_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] update overload cost=${cost}ms release=${releaseMs}ms sync=${syncMs}ms gen=${generationMs}ms " +
          "expire=${expireMs}ms enqueue=${enqueueMs}ms measure=${measureMs}ms scheduled=$scheduledMeasures " +
          "layout=${layoutMs}ms frame=${frameMs}ms active=${activeItems.size} draw=${commands.size}"
      )
    }
  }

  fun draw(canvas: Canvas, onRenderReady: () -> Unit) {
    val currentFrame = frame
    onRenderReady()
    val config = context.config
    if (!config.visibility || currentFrame == null ||
      currentFrame.visibilityGeneration != config.visibilityGeneration) {
      return
    }

    var hit = 0
    val commands = currentFrame.commands
    for (command in commands) {
      if (drawCommand(canvas, command, config)) {
        hit++
      }
      dispatchShown(command.item, config)
    }
    cacheHit.num = hit
    cacheHit.den = commands.size
  }

  fun getDanmakus(point: Point): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val x = point.x.toFloat()
    val y = point.y.toFloat()
    val result = ArrayList<DanmakuItem>()
    for (command in currentFrame.commands) {
      if (x >= command.left && x <= command.right && y >= command.top && y <= command.bottom) {
        result.add(command.item)
      }
    }
    return result
  }

  fun getDanmakus(rect: RectF): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val result = ArrayList<DanmakuItem>()
    for (command in currentFrame.commands) {
      if (rect.left < command.right && rect.right > command.left &&
        rect.top < command.bottom && rect.bottom > command.top) {
        result.add(command.item)
      }
    }
    return result
  }

  fun release() {
    clearAllData()
    context.cacheManager.release()
  }

  private fun syncPendingData() {
    if (pendingAddItems.isEmpty()) return
    val pending = ArrayList(pendingAddItems)
    pendingAddItems.clear()
    val canAppendInOrder = canAppendWithoutSort(pending)
    sortedItems.addAll(pending)
    if (!canAppendInOrder) {
      sortedItems.sortWith(comparator)
    }
    dataDirty = false
    if (liveMode) {
      trimLiveHistory()
    }
  }

  private fun canAppendWithoutSort(pending: List<DanmakuItem>): Boolean {
    if (pending.isEmpty()) return true
    var previousTime = sortedItems.lastOrNull()?.timePosition ?: Long.MIN_VALUE
    for (item in pending) {
      if (item.timePosition < previousTime) {
        return false
      }
      previousTime = item.timePosition
    }
    return true
  }

  private fun removeExpired(now: Long, config: DanmakuConfig) {
    val iterator = activeItems.iterator()
    while (iterator.hasNext()) {
      val item = iterator.next()
      item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
        config.rollingDurationMs
      } else {
        config.durationMs
      }
      if (!item.isHolding && item.isRuntimeTimeout(now)) {
        activeIds.remove(item.data.danmakuId)
        removeFromTracks(item)
        item.cacheRecycle()
        iterator.remove()
      }
    }
  }

  private fun enqueueDueItems(now: Long, config: DanmakuConfig) {
    if (sortedItems.isEmpty()) return
    val maxDuration = max(config.durationMs, config.rollingDurationMs)
    val windowStart = now - maxDuration
    if (scanIndex >= sortedItems.size || sortedItems.getOrNull(scanIndex)?.timePosition ?: Long.MAX_VALUE < windowStart) {
      scanIndex = lowerBound(windowStart)
    }

    val entryEnd = now + entryAheadMs(config)
    var added = 0
    val enqueueBudget = DanmakuLoadShedder.enqueueBudget(loadShedLevel)
    while (scanIndex < sortedItems.size && added < enqueueBudget) {
      val item = sortedItems[scanIndex]
      if (item.timePosition > entryEnd) break
      scanIndex++
      if (item.timePosition < windowStart) continue
      if (DanmakuLoadShedder.shouldSkipItem(item, loadShedLevel)) continue
      if (activeIds.add(item.data.danmakuId)) {
        item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
          config.rollingDurationMs
        } else {
          config.durationMs
        }
        item.drawState.layoutGeneration = -1
        item.rollingStartTimeMs = ROLLING_START_TIME_UNSET
        activeItems.add(item)
        added++
      }
    }
  }

  private fun scheduleMeasureActiveItems(config: DanmakuConfig): Int {
    var scheduled = 0
    val startedAt = SystemClock.elapsedRealtime()
    measureCandidates.clear()
    for (index in activeItems.indices) {
      val candidate = if (index < measureCandidatePool.size) {
        measureCandidatePool[index]
      } else {
        MeasureItemCandidate().also { measureCandidatePool.add(it) }
      }
      candidate.item = activeItems[index]
      measureCandidates.add(candidate)
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
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        continue
      }
      // 测量可能触发字体、描边和样式初始化，放到缓存线程，避免 action 线程一帧吃掉几百毫秒。
      item.state = ItemState.Measuring
      context.cacheManager.requestMeasure(item, context.displayer, config)
      scheduled++
    }
    return scheduled
  }

  private fun entryAheadMs(config: DanmakuConfig): Long =
    max(PREPARE_AHEAD_MS, config.preCacheTimeMs)

  private fun layoutAndBuildFrame(now: Long, config: DanmakuConfig): ArrayList<DrawCommand> {
    val displayer = context.displayer
    val commands = ArrayList<DrawCommand>(activeItems.size)
    var fixedCommands: ArrayList<DrawCommand>? = null
    val startedAt = SystemClock.elapsedRealtime()
    val profileDetails = (++layoutProfileTick % LAYOUT_PROFILE_DETAIL_INTERVAL) == 0
    var filterMs = 0L
    var trackMs = 0L
    var cacheMs = 0L
    var commandMs = 0L
    var filteredCount = 0
    var unmeasuredCount = 0
    var outsideCount = 0
    var trackRejectedCount = 0
    var trackFastCount = 0
    var trackLayoutCount = 0
    var cacheRequestedCount = 0
    var cacheDeferredCount = 0
    var cacheRequestBudget = MAX_CACHE_REQUESTS_PER_FRAME
    val width = displayer.width
    val height = displayer.height
    val margin = displayer.margin
    val iterator = activeItems.iterator()
    while (iterator.hasNext()) {
      val item = iterator.next()
      if (item.isRuntimeOutside(now)) {
        outsideCount++
        continue
      }
      if (item.state < ItemState.Measured) {
        unmeasuredCount++
        continue
      }
      var stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      if (isDataFiltered(item, config)) {
        if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt
        filteredCount++
        continue
      }
      if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt

      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val mode = item.data.mode
      val visible = if (item.drawState.layoutGeneration == config.layoutGeneration) {
        val updated = when (mode) {
          DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.updateExisting(item, width, height, config)
          DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.updateExisting(item, width, height, config)
          else -> rollingTracks.updateExisting(item, now, width, height, config)
        }
        if (updated) {
          trackFastCount++
          true
        } else {
          trackLayoutCount++
          layoutTrack(item, now, width, height, margin, config)
        }
      } else {
        trackLayoutCount++
        layoutTrack(item, now, width, height, margin, config)
      }
      if (profileDetails) trackMs += SystemClock.elapsedRealtime() - stepAt
      if (!visible) {
        trackRejectedCount++
        if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
          activeIds.remove(item.data.danmakuId)
          removeFromTracks(item)
          item.cacheRecycle()
          iterator.remove()
        }
        continue
      }
      if (item.state < ItemState.Rendering) {
        if (cacheRequestBudget > 0) {
          item.state = ItemState.Rendering
          stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
          context.cacheManager.requestBuildCache(item, displayer, config)
          if (profileDetails) cacheMs += SystemClock.elapsedRealtime() - stepAt
          cacheRequestBudget--
          cacheRequestedCount++
        } else {
          // 首屏窗口可能瞬间出现十几条弹幕，缓存构建分帧排队；未排到的先走直接绘制兜底。
          cacheDeferredCount++
        }
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val drawState = item.drawState
      val cache = drawState.drawingCache
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        cache.increaseReference()
      }
      val command = DrawCommand(
        item = item,
        cache = cache,
        left = drawState.positionX,
        top = drawState.positionY,
        right = drawState.positionX + drawState.width,
        bottom = drawState.positionY + drawState.height
      )
      if (mode == DanmakuItemData.DANMAKU_MODE_CENTER_TOP ||
        mode == DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM) {
        // 悬停弹幕始终最后绘制，保证层级压在滚动弹幕上方。
        val fixed = fixedCommands ?: ArrayList<DrawCommand>(8).also { fixedCommands = it }
        fixed.add(command)
      } else {
        commands.add(command)
      }
      if (profileDetails) commandMs += SystemClock.elapsedRealtime() - stepAt
    }
    fixedCommands?.let { commands.addAll(it) }
    val cost = SystemClock.elapsedRealtime() - startedAt
    updateLoadShedLevel(
      layoutCostMs = cost,
      rejectedCount = trackRejectedCount,
      unmeasuredCount = unmeasuredCount
    )
    if (cost >= LAYOUT_OVERLOAD_MS && profileDetails) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] layout profile cost=${cost}ms filter=${filterMs}ms track=${trackMs}ms cache=${cacheMs}ms " +
          "command=${commandMs}ms active=${activeItems.size} draw=${commands.size} outside=$outsideCount " +
          "unmeasured=$unmeasuredCount filtered=$filteredCount rejected=$trackRejectedCount " +
          "trackFast=$trackFastCount trackLayout=$trackLayoutCount cacheReq=$cacheRequestedCount cacheDef=$cacheDeferredCount"
      )
    } else if (cost >= LAYOUT_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] layout overload cost=${cost}ms active=${activeItems.size} draw=${commands.size} outside=$outsideCount " +
          "unmeasured=$unmeasuredCount filtered=$filteredCount rejected=$trackRejectedCount " +
          "trackFast=$trackFastCount trackLayout=$trackLayoutCount cacheReq=$cacheRequestedCount cacheDef=$cacheDeferredCount"
      )
    }
    return commands
  }

  private fun layoutTrack(
    item: DanmakuItem,
    now: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean =
    when (item.data.mode) {
      DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.layout(item, now, width, height, margin, config)
      DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.layout(item, now, width, height, margin, config)
      else -> rollingTracks.layout(item, now, width, height, margin, config)
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

  private fun drawCommand(canvas: Canvas, command: DrawCommand, config: DanmakuConfig): Boolean {
    val cache = command.cache
    if (cache != DrawingCache.EMPTY_DRAWING_CACHE &&
      command.item.drawState.cacheGeneration == config.cacheGeneration &&
      command.item.state >= ItemState.Rendered) {
      val bitmap = cache.get()?.bitmap
      if (bitmap != null && !bitmap.isRecycled) {
        drawPaint.alpha = (config.alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, command.left, command.top, drawPaint)
        return true
      }
    }
    canvas.withTranslation(command.left, command.top) {
      context.renderer.draw(command.item, canvas, context.displayer, config)
    }
    return false
  }

  private fun dispatchShown(item: DanmakuItem, config: DanmakuConfig) {
    val target = listener ?: return
    if (item.shownGeneration == config.firstShownGeneration) return
    item.shownGeneration = config.firstShownGeneration
    callbackHandler.post { target.onDanmakuShown(item) }
  }

  private fun replaceFrame(newFrame: RuntimeFrame) {
    frame?.let { pendingReleaseFrames.add(it) }
    frame = newFrame
  }

  private fun releasePendingFrames() {
    if (pendingReleaseFrames.isEmpty()) return
    for (oldFrame in pendingReleaseFrames) {
      releaseFrame(oldFrame)
    }
    pendingReleaseFrames.clear()
  }

  private fun releaseFrame(oldFrame: RuntimeFrame?) {
    oldFrame ?: return
    for (command in oldFrame.commands) {
      if (command.cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        command.cache.decreaseReference()
      }
    }
  }

  private fun clearTracks() {
    rollingTracks.clear()
    topTracks.clear()
    bottomTracks.clear()
  }

  private fun removeFromTracks(item: DanmakuItem) {
    rollingTracks.remove(item)
    topTracks.remove(item)
    bottomTracks.remove(item)
  }

  private fun updateLoadShedLevel(layoutCostMs: Long, rejectedCount: Int, unmeasuredCount: Int) {
    val oldLevel = loadShedLevel
    loadShedLevel = DanmakuLoadShedder.nextLevel(
      currentLevel = loadShedLevel,
      layoutCostMs = layoutCostMs,
      rejectedCount = rejectedCount,
      unmeasuredCount = unmeasuredCount
    )
    if (loadShedLevel != oldLevel) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] load shed level=$loadShedLevel cost=${layoutCostMs}ms rejected=$rejectedCount unmeasured=$unmeasuredCount"
      )
    }
  }

  private fun DanmakuItem.isRuntimeTimeout(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isTimeout(now)
    }
    val startTime = RollingDanmakuTiming.resolvedStartTime(rollingStartTimeMs, timePosition)
    return RollingDanmakuTiming.isTimeout(now, startTime, duration)
  }

  private fun DanmakuItem.isRuntimeOutside(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isOutside(now)
    }
    return isLate(now) || isRuntimeTimeout(now)
  }

  private fun lowerBound(timeMs: Long): Int {
    var low = 0
    var high = sortedItems.size
    while (low < high) {
      val mid = (low + high).ushr(1)
      if (sortedItems[mid].timePosition < timeMs) {
        low = mid + 1
      } else {
        high = mid
      }
    }
    return low
  }

  private fun trimLiveHistory() {
    if (sortedItems.size <= LIVE_HISTORY_MAX) return
    val removeCount = sortedItems.size - LIVE_HISTORY_MAX
    repeat(removeCount) {
      sortedItems.removeAt(0)
    }
    scanIndex = (scanIndex - removeCount).coerceAtLeast(0)
  }

  private data class RuntimeFrame(
    val commands: ArrayList<DrawCommand>,
    val visibilityGeneration: Int
  )

  private data class DrawCommand(
    val item: DanmakuItem,
    val cache: DrawingCache,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
  )

  private class MeasureItemCandidate : DanmakuMeasureScheduler.MeasureCandidate {
    lateinit var item: DanmakuItem
    override val timePositionMs: Long
      get() = item.timePosition
    override val contentLength: Int
      get() = item.data.content.length
    override val measureState: ItemState
      get() = item.state

    override fun isMeasured(measureGeneration: Int): Boolean =
      item.drawState.isMeasured(measureGeneration)
  }

  companion object {
    private const val PREPARE_AHEAD_MS = 300L
    private const val MAX_MEASURE_PER_FRAME = 12
    private const val MAX_CACHE_REQUESTS_PER_FRAME = 4
    private const val MEASURE_SCHEDULE_BUDGET_MS = 2L
    private const val LIVE_HISTORY_MAX = 2000
    private const val RUNTIME_OVERLOAD_MS = 12L
    private const val LAYOUT_OVERLOAD_MS = 12L
    private const val LAYOUT_PROFILE_DETAIL_INTERVAL = 30
  }
}
