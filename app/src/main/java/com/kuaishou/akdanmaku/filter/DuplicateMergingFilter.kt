/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the
 * following conditions:
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

package com.kuaishou.akdanmaku.filter

import android.os.SystemClock
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.ext.isOutside
import com.kuaishou.akdanmaku.ext.isTimeout
import com.kuaishou.akdanmaku.utils.DanmakuTimer

/**
 * 重复弹幕合并过滤器。
 *
 * @author Xana
 * @since 2021-06-30
 */
open class DuplicateMergingFilter : DanmakuDataFilter(DanmakuFilters.FILTER_TYPE_DUPLICATE_MERGE) {

  // 用 LinkedHashMap 当 Set（value 占位），替代原 TreeSet：
  // contains/add/remove 从 O(log n) 降到 O(1)；迭代顺序为插入顺序，
  // 弹幕按时间顺序入队，故"从头删超时项、碰到第一个未超时即停"的清理语义保持不变。
  private val blockedDanmakus = LinkedHashMap<DanmakuItem, Boolean>()
  protected val currentDanmakus = mutableMapOf<String, DanmakuItem>()
  private val passedDanmakus = LinkedHashMap<DanmakuItem, Boolean>()

  private fun removeTimeoutDanmakus(limitTime: Int, currentTimeMills: Long) {
    val startTime: Long = SystemClock.uptimeMillis()
    removeTimeoutSet(blockedDanmakus, startTime, limitTime, currentTimeMills)
    removeTimeoutSet(passedDanmakus, startTime, limitTime, currentTimeMills)
    removeTimeoutMap(currentDanmakus, startTime, limitTime, currentTimeMills)
  }

  private fun removeTimeoutSet(
    set: MutableMap<DanmakuItem, Boolean>,
    startTime: Long,
    limitTime: Int,
    currentTimeMills: Long
  ) {
    val iter = set.keys.iterator()
    while (iter.hasNext()) {
      if (SystemClock.uptimeMillis() - startTime <= limitTime) return
      if (iter.next().isTimeout(currentTimeMills)) iter.remove() else return
    }
  }

  private fun removeTimeoutMap(
    map: MutableMap<String, DanmakuItem>,
    startTime: Long,
    limitTime: Int,
    currentTimeMills: Long
  ) {
    val iter = map.values.iterator()
    while (iter.hasNext()) {
      if (SystemClock.uptimeMillis() - startTime <= limitTime) return
      if (iter.next().isTimeout(currentTimeMills)) iter.remove() else return
    }
  }

  override fun filter(item: DanmakuItem, timer: DanmakuTimer, config: DanmakuConfig): Boolean {
    val data = item.data
    val currentTimeMills = timer.currentTimeMs
    removeTimeoutDanmakus(7, currentTimeMills)
    return if (blockedDanmakus.containsKey(item) && !item.isOutside(currentTimeMills)) {
      true
    } else if (passedDanmakus.containsKey(item)) {
      false
    } else if (currentDanmakus.containsKey(data.content)) {
      currentDanmakus[data.content] = item
      blockedDanmakus.remove(item)
      blockedDanmakus[item] = true
      true
    } else {
      currentDanmakus[data.content] = item
      passedDanmakus[item] = true
      true
    }
  }
}
