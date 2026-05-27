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

@file:Suppress("SpellCheckingInspection")

package com.kuaishou.akdanmaku.cache

import android.os.*
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.ItemState
import com.kuaishou.akdanmaku.data.state.DrawState
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import java.util.*

/**
 * 缓存管理器，用于完成后台缓存绘制与管理缓存相关对象
 *
 * Maintained by project contributors.
 * @since 2021-06-24
 */
@Suppress("unused")
class CacheManager(private val callbackHandler: Handler, private val renderer: DanmakuRenderer) {
  private var available = false
  private val cacheThread by lazy {
    HandlerThread(THREAD_NAME).apply {
      start()
      available = true
    }
  }
  private val cacheHandler by lazy { CacheHandler(cacheThread.looper) }
  private var cancelFlag = false

  private val measureSizeCache = Collections.synchronizedMap(mutableMapOf<Long, Size>())
  private var sharedCacheGeneration = -1
  private val sharedDrawingCaches = object : LinkedHashMap<Long, DrawingCache>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DrawingCache>?): Boolean {
      val shouldRemove = size > MAX_SHARED_DRAWING_CACHE
      if (shouldRemove) {
        eldest?.value?.let(::releaseSharedDrawingCache)
      }
      return shouldRemove
    }
  }

  val cachePool = DrawingCachePool(DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE)
  var isReleased: Boolean = false
    private set

  fun warmUp() {
    // 提前启动缓存线程，避免第一批弹幕入场时 action 线程被 HandlerThread.looper 阻塞。
    cacheHandler.sendEmptyMessage(WORKER_MSG_WARM_UP)
  }

  fun requestBuildCache(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    cacheHandler.obtainMessage(
      WORKER_MSG_BUILD_CACHE,
      CacheInfo(item, displayer, config)
    ).sendToTarget()
  }

  fun requestMeasure(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    cacheHandler.obtainMessage(
      WORKER_MSG_BUILD_MEASURE,
      CacheInfo(item, displayer, config)
    ).sendToTarget()
  }

  /**
   * 发送一个 build 结束的请求，放置在当前若干 build_cache 消息后，当此批次缓存完成后会发送一个回调消息
   */
  fun requestBuildSign() {
    cacheHandler.removeMessages(WORKER_MSG_RENDER_SIGN)
    cacheHandler.sendEmptyMessage(WORKER_MSG_RENDER_SIGN)
  }

  fun cancelAllRequests() {
    cacheHandler.removeCallbacksAndMessages(null)
    cancelFlag = true
  }

  fun requestRelease() {
    cancelAllRequests()
    cacheHandler.sendEmptyMessage(WORKER_MSG_RELEASE)
  }

  fun destroyCache(cache: DrawingCache) {
    if (cache == DrawingCache.EMPTY_DRAWING_CACHE) return
    cacheHandler.obtainMessage(WORKER_MSG_DESTROY, cache).sendToTarget()
  }

  fun releaseCache(cache: DrawingCache) {
    if (cache == DrawingCache.EMPTY_DRAWING_CACHE) return
    cacheHandler.obtainMessage(WORKER_MSG_RELEASE_ITEM, cache).sendToTarget()
  }

  fun clearMeasureCache() {
    cacheHandler.obtainMessage(WORKER_MSG_CLEAR_CACHE).sendToTarget()
  }

  fun getDanmakuSize(danmaku: DanmakuItemData): Size? = synchronized(measureSizeCache) {
    measureSizeCache[danmaku.danmakuId]
  }

  fun release() {
    if (available) {
      cancelAllRequests()
      try {
        cacheThread.quitSafely()
      } catch (e: Exception) {
        Log.e(DanmakuEngine.TAG, "CacheManager.release failed", e)
      }
    }
    available = false
  }

  private class CacheInfo(
    val item: DanmakuItem,
    val displayer: DanmakuDisplayer,
    val config: DanmakuConfig
  )

  private inner class CacheHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        WORKER_MSG_BUILD_MEASURE -> {
          val info = msg.obj as? CacheInfo ?: return
          val config = info.config
          val item = info.item
          if (cancelFlag) {
            Log.d(DanmakuEngine.TAG, "[CacheManager] cancel cache.")
            cancelFlag = false
            return
          }

          startTrace("CacheManager_checkMeasure")
          val drawState = item.drawState
          // check measure
          if (!drawState.isMeasured(config.measureGeneration)) {
            try {
              val size = renderer.measure(item, info.displayer, config)
              drawState.width = size.width.toFloat()
              drawState.height = size.height.toFloat()
              drawState.measureGeneration = config.measureGeneration
              synchronized(measureSizeCache) {
                measureSizeCache[item.data.danmakuId] = size
              }
              item.state = ItemState.Measured
            } catch (e: Exception) {
              Log.e(DanmakuEngine.TAG, "CacheManager.measure failed", e)
              item.state = ItemState.Error
            }
          }
          endTrace()
        }
        WORKER_MSG_BUILD_CACHE -> {
          val info = msg.obj as? CacheInfo ?: return

          startTrace("CacheManager_buildCache")
          val config = info.config
          val item = info.item
          val drawState = item.drawState
          if (sharedCacheGeneration != config.cacheGeneration) {
            clearSharedDrawingCaches()
            sharedCacheGeneration = config.cacheGeneration
          }
          val sharedKey = sharedCacheKey(item, info.displayer, config)
          val sharedCache = sharedKey?.let { key ->
            sharedDrawingCaches[key]?.takeIf { cache ->
              cache.get() != null && isCacheSizeJustified(cache, drawState)
            } ?: run {
              removeSharedDrawingCache(key)
              null
            }
          }
          if (sharedCache != null) {
            replaceItemDrawingCache(drawState, sharedCache)
            item.state = ItemState.Rendered
            item.drawState.cacheGeneration = config.cacheGeneration
            endTrace()
            return
          }

          startTrace("CacheManager_checkCache")
          // check drawingCache
          if (drawState.drawingCache.get() == null ||
            drawState.drawingCache == DrawingCache.EMPTY_DRAWING_CACHE ||
            isSizeJustified(drawState)
          ) {
            if (drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE && drawState.drawingCache.get() != null) {
              drawState.drawingCache.decreaseReference()
            }
            drawState.drawingCache =
              cachePool.acquire(drawState.width.toInt(), drawState.height.toInt())
                ?: DrawingCache().build(
                  drawState.width.toInt(),
                  drawState.height.toInt(),
                  info.displayer.densityDpi,
                  checkSize = true
                )
            drawState.drawingCache.erase()
            drawState.drawingCache.increaseReference()
            drawState.drawingCache.cacheManager = this@CacheManager
          }
          endTrace()

          startTrace("CacheManager_drawCache")
          val holder = drawState.drawingCache.get()
          if (holder == null) {
            cachePool.release(drawState.drawingCache)
            drawState.drawingCache = DrawingCache.EMPTY_DRAWING_CACHE
            item.state = ItemState.Error
            return
          }
          // draw cache
          synchronized(drawState) {
            try {
              renderer.draw(item, holder.canvas, info.displayer, config)
              item.state = ItemState.Rendered
              item.drawState.cacheGeneration = config.cacheGeneration
              if (sharedKey != null) {
                putSharedDrawingCache(sharedKey, drawState.drawingCache)
              }
            } catch (e: Exception) {
              Log.e(DanmakuEngine.TAG, "CacheManager.draw failed", e)
              item.state = ItemState.Error
            }
          }
          endTrace()
//          callbackHandler.obtainMessage(MSG_CACHE_BUILT, info.item).sendToTarget()
          endTrace()
        }
        WORKER_MSG_SEEK -> {
          removeCallbacksAndMessages(null)
        }
        WORKER_MSG_CLEAR_CACHE -> {
          synchronized(measureSizeCache) {
            measureSizeCache.clear()
          }
          clearSharedDrawingCaches()
        }
        WORKER_MSG_DESTROY -> {
          (msg.obj as? DrawingCache)?.destroy()
        }
        WORKER_MSG_RELEASE_ITEM -> {
          (msg.obj as? DrawingCache)?.let { if (!cachePool.release(it)) it.destroy() }
        }
        WORKER_MSG_WARM_UP -> {
          // no-op，只用于触发 cacheHandler/cacheThread 初始化。
        }
        WORKER_MSG_RENDER_SIGN -> {
          callbackHandler.sendEmptyMessage(MSG_CACHE_RENDER)
        }
        WORKER_MSG_RELEASE -> {
          cachePool.clear()
          isReleased = true
          cacheThread.quitSafely()
        }
      }
    }

    private fun isSizeJustified(drawState: DrawState): Boolean =
      drawState.drawingCache.width < drawState.width ||
        drawState.drawingCache.height < drawState.height ||
        drawState.drawingCache.width - drawState.width > 5 ||
        drawState.drawingCache.height - drawState.height > 5
  }

  private fun putSharedDrawingCache(key: Long, cache: DrawingCache) {
    if (cache == DrawingCache.EMPTY_DRAWING_CACHE || cache.get() == null) return
    if (sharedDrawingCaches.containsKey(key)) return
    // sharedDrawingCaches 自己持有一份引用，避免最后一个弹幕离场后缓存立刻回池并被擦除。
    cache.increaseReference()
    sharedDrawingCaches[key] = cache
  }

  private fun removeSharedDrawingCache(key: Long) {
    val cache = sharedDrawingCaches.remove(key) ?: return
    releaseSharedDrawingCache(cache)
  }

  private fun clearSharedDrawingCaches() {
    if (sharedDrawingCaches.isEmpty()) return
    val caches = sharedDrawingCaches.values.toList()
    sharedDrawingCaches.clear()
    caches.forEach(::releaseSharedDrawingCache)
  }

  private fun releaseSharedDrawingCache(cache: DrawingCache) {
    cache.decreaseReference()
    cache.destroy()
  }

  private fun replaceItemDrawingCache(drawState: DrawState, cache: DrawingCache) {
    if (drawState.drawingCache == cache) return
    if (drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE) {
      drawState.drawingCache.decreaseReference()
    }
    cache.increaseReference()
    cache.cacheManager = this
    drawState.drawingCache = cache
  }

  private fun isCacheSizeJustified(cache: DrawingCache, drawState: DrawState): Boolean =
    cache.width >= drawState.width &&
      cache.height >= drawState.height &&
      cache.width - drawState.width <= 5 &&
      cache.height - drawState.height <= 5

  private fun sharedCacheKey(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Long? {
    val data = item.data
    // 先只复用最常见的普通文字弹幕；VIP/自发/特殊样式可能依赖外部贴图或画布边框。
    if (data.renderFlags != DanmakuItemData.RENDER_FLAG_NONE ||
      data.danmakuStyle != DanmakuItemData.DANMAKU_STYLE_NONE
    ) {
      return null
    }
    var acc = FNV_OFFSET
    acc = mix(acc, data.content.hashCode().toLong())
    acc = mix(acc, data.content.length.toLong())
    acc = mix(acc, data.textSize.toLong())
    acc = mix(acc, data.textColor.toLong())
    acc = mix(acc, if (config.bold) 1L else 0L)
    acc = mix(acc, config.textSizeScale.toBits().toLong())
    acc = mix(acc, displayer.density.toBits().toLong())
    acc = mix(acc, displayer.densityDpi.toLong())
    return acc
  }

  private fun mix(acc: Long, value: Long): Long = (acc xor value) * FNV_PRIME

  companion object {
    const val THREAD_NAME = "AkDanmaku-Cache"

    private const val WORKER_MSG_RELEASE = -100

    private const val WORKER_MSG_RENDER_SIGN = -1
    private const val WORKER_MSG_BUILD_MEASURE = 0
    private const val WORKER_MSG_BUILD_CACHE = 1
    private const val WORKER_MSG_SEEK = 2
    private const val WORKER_MSG_CLEAR_CACHE = 3
    private const val WORKER_MSG_DESTROY = 4
    private const val WORKER_MSG_RELEASE_ITEM = 5
    private const val WORKER_MSG_WARM_UP = 6

    const val MSG_CACHE_RENDER = -1
    const val MSG_CACHE_MEASURED = 0
    const val MSG_CACHE_BUILT = 1
    const val MSG_CACHE_FAILED = 2

    private const val MAX_SHARED_DRAWING_CACHE = 384
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
  }
}
