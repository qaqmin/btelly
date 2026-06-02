package com.kuaishou.akdanmaku.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingCacheReusePolicyTest {

  @Test
  fun isReusable_acceptsSlightlyLargerCache() {
    assertTrue(DrawingCacheReusePolicy.isReusable(1032, 42, 1000, 40))
  }

  @Test
  fun isReusable_rejectsOverlyWideCache() {
    assertFalse(DrawingCacheReusePolicy.isReusable(1100, 42, 1000, 40))
  }

  @Test
  fun isReusable_rejectsExcessAreaWaste() {
    assertFalse(DrawingCacheReusePolicy.isReusable(1064, 56, 1000, 40))
  }
}
