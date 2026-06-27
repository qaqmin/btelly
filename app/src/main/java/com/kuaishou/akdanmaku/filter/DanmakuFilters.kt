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

package com.kuaishou.akdanmaku.filter

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.utils.DanmakuTimer

class DanmakuFilters {

  var dataFilter = emptyList<DanmakuDataFilter>()
  var layoutFilter = emptyList<DanmakuLayoutFilter>()

  val isDataFilterResultCacheable: Boolean
    get() {
      for (filter in dataFilter) {
        when (filter) {
          is TypeFilter -> continue
          else -> return false
        }
      }
      return true
    }

  fun isDataFiltered(item: DanmakuItem, timer: DanmakuTimer, config: DanmakuConfig): Boolean {
    for (filter in dataFilter) {
      if (filter.filter(item, timer, config)) {
        return true
      }
    }
    return false
  }

  fun filterData(item: DanmakuItem, timer: DanmakuTimer, config: DanmakuConfig): FilterResult {
    var filtered = false
    var param = 0
    for (filter in dataFilter) {
      filtered = filter.filter(item, timer, config)
      if (filtered) {
        param = filter.filterParams
        break
      }
    }
    return FilterResult(filtered, param)
  }

  fun filterLayout(item: DanmakuItem, willHit: Boolean, currentTimeMills: Long, config: DanmakuConfig): FilterResult {
    val (filtered, param) = layoutFilter.fold(false to 0) { result, filter ->
      if (result.first) result
      else {
        val filtered = filter.filter(item, willHit, currentTimeMills, config)
        filtered to filter.filterParams
      }
    }
    return FilterResult(filtered, param)
  }

  class FilterResult(
    val filtered: Boolean,
    val filterParam: Int
  )

  companion object {
    // filter type
    const val FILTER_TYPE_TYPE = 1
  }
}
