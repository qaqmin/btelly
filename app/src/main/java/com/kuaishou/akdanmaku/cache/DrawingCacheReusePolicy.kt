package com.kuaishou.akdanmaku.cache

internal object DrawingCacheReusePolicy {
  private const val MAX_EXTRA_WIDTH = 64
  private const val MAX_EXTRA_HEIGHT = 16
  private const val MAX_AREA_WASTE_NUMERATOR = 118
  private const val MAX_AREA_WASTE_DENOMINATOR = 100

  fun isReusable(cacheWidth: Int, cacheHeight: Int, width: Int, height: Int): Boolean {
    if (cacheWidth < width || cacheHeight < height) return false
    if (cacheWidth - width > MAX_EXTRA_WIDTH || cacheHeight - height > MAX_EXTRA_HEIGHT) return false
    val requiredArea = width * height
    val cacheArea = cacheWidth * cacheHeight
    return cacheArea * MAX_AREA_WASTE_DENOMINATOR <= requiredArea * MAX_AREA_WASTE_NUMERATOR
  }
}
