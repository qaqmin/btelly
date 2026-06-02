package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuRejectionPolicyTest {

  @Test
  fun shouldDropRejectedItem_dropsNormalRejectedItems() {
    assertTrue(DanmakuRejectionPolicy.shouldDropRejectedItem(isHolding = false))
  }

  @Test
  fun shouldDropRejectedItem_keepsHeldItems() {
    assertFalse(DanmakuRejectionPolicy.shouldDropRejectedItem(isHolding = true))
  }
}
