package com.kuaishou.akdanmaku.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuFiltersTest {

  @Test
  fun staticDataFilters_areCacheable() {
    val filters = DanmakuFilters().apply {
      dataFilter = listOf(TypeFilter(), TextColorFilter(), DuplicateMergedFilter())
    }

    assertTrue(filters.isDataFilterResultCacheable)
  }

  @Test
  fun timeOrHistoryDependentDataFilters_areNotCacheable() {
    val filters = DanmakuFilters().apply {
      dataFilter = listOf(TypeFilter(), QuantityFilter(maxCount = 10))
    }

    assertFalse(filters.isDataFilterResultCacheable)
  }
}
