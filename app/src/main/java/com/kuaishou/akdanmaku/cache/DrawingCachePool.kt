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

package com.kuaishou.akdanmaku.cache

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.ext.AkLog as Log

/**
 * 弹幕绘制缓存池，管理可复用的 DrawingCache 对象。
 *
 * 支持根据屏幕分辨率动态调整池大小，以及基于可用内存的自适应缩放。
 *
 * @param maxMemorySize 池最大内存（字节）
 *
 * @author Xana
 * @since 2021-06-23
 */
class DrawingCachePool(private var maxMemorySize: Int) {

  companion object {
    private const val BUCKET_SIZE = 16
    private const val MAX_EXTRA_WIDTH = 64
    private const val MAX_EXTRA_HEIGHT = 16
  }

  private val caches = mutableSetOf<DrawingCache>()
  private val bucketMap = mutableMapOf<Long, MutableSet<DrawingCache>>()
  private var memorySize = 0

  private fun bucketKey(width: Int, height: Int): Long {
    return ((width / BUCKET_SIZE).toLong() shl 32) or (height / BUCKET_SIZE).toLong()
  }

  fun acquire(width: Int, height: Int): DrawingCache? {
    synchronized(this) {
      val cache = findReusableCache(width, height) ?: return null
      removeFromPool(cache)
      return cache
    }
  }

  fun release(cache: DrawingCache?): Boolean {
    cache?.get() ?: return true
    if (cache in caches) return false
    return if (cache.size + memorySize > maxMemorySize) {
      Log.v("DrawingCache", "[Release][+] OOM Pool")
      false
    } else {
      synchronized(this) {
        caches.add(cache)
        cache.erase()
        memorySize += cache.size
        val key = bucketKey(cache.width, cache.height)
        bucketMap.getOrPut(key) { mutableSetOf() }.add(cache)
      }
      true
    }
  }

  fun clear() {
    synchronized(this) {
      caches.forEach { it.destroy() }
      caches.clear()
      bucketMap.clear()
      memorySize = 0
    }
  }

  fun evictAll(): List<DrawingCache> {
    synchronized(this) {
      if (caches.isEmpty()) return emptyList()
      val evicted = caches.toList()
      caches.clear()
      bucketMap.clear()
      memorySize = 0
      return evicted
    }
  }

  private fun findReusableCache(width: Int, height: Int): DrawingCache? {
    var best: DrawingCache? = null
    var bestArea = Int.MAX_VALUE
    val widthBucket = width / BUCKET_SIZE
    val heightBucket = height / BUCKET_SIZE
    val maxWidthBucket = (width + MAX_EXTRA_WIDTH) / BUCKET_SIZE
    val maxHeightBucket = (height + MAX_EXTRA_HEIGHT) / BUCKET_SIZE
    for (bucketW in widthBucket..maxWidthBucket) {
      for (bucketH in heightBucket..maxHeightBucket) {
        val bucket = bucketMap[bucketKeyFromBuckets(bucketW, bucketH)] ?: continue
        for (cache in bucket) {
          if (!isReusable(cache, width, height)) continue
          val area = cache.width * cache.height
          if (area < bestArea) {
            best = cache
            bestArea = area
          }
        }
      }
    }
    return best
  }

  private fun isReusable(cache: DrawingCache, width: Int, height: Int): Boolean {
    return DrawingCacheReusePolicy.isReusable(cache.width, cache.height, width, height)
  }

  private fun removeFromPool(cache: DrawingCache) {
    caches.remove(cache)
    bucketMap[bucketKey(cache.width, cache.height)]?.remove(cache)
    memorySize -= cache.size
  }

  private fun bucketKeyFromBuckets(widthBucket: Int, heightBucket: Int): Long {
    return (widthBucket.toLong() shl 32) or heightBucket.toLong()
  }
}
