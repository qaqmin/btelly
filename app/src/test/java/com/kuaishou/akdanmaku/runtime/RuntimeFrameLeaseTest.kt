package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFrameLeaseTest {

  @Test
  fun retiredFrameWaitsUntilMainThreadFinishesDrawing() {
    val frame = RuntimeFrame()
    frame.reset(visibilityGeneration = 1)
    frame.publish()

    assertTrue(frame.tryAcquireRead())
    assertFalse(frame.beginRecycleOrContinue())

    frame.releaseRead()
    assertTrue(frame.beginRecycleOrContinue())
  }

  @Test
  fun frameCannotBeReadBeforePublication() {
    val frame = RuntimeFrame()
    frame.reset(visibilityGeneration = 1)

    assertFalse(frame.tryAcquireRead())
    frame.publish()
    assertTrue(frame.tryAcquireRead())
    frame.releaseRead()
  }
}
