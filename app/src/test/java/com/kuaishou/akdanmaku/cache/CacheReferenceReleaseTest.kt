package com.kuaishou.akdanmaku.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CacheReferenceReleaseTest {
  @Test
  fun managerReleaseDrainsOnlyRemainingFrameReferences() {
    val first = Any()
    val second = Any()
    val third = Any()
    val partiallyReleased = CacheReferenceRelease(arrayListOf(first, second), nextIndex = 1)
    val untouched = CacheReferenceRelease(arrayListOf(third))
    val released = ArrayList<Any>()

    drainCacheReferenceReleases(listOf(partiallyReleased, untouched), released::add)

    assertEquals(2, released.size)
    assertSame(second, released[0])
    assertSame(third, released[1])
    assertEquals(2, partiallyReleased.nextIndex)
    assertEquals(1, untouched.nextIndex)

    drainCacheReferenceReleases(listOf(partiallyReleased, untouched), released::add)
    assertEquals(2, released.size)
  }
}
