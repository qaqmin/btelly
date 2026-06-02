package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.DanmakuItem

internal object DanmakuRejectionPolicy {
  fun shouldDropRejectedItem(item: DanmakuItem): Boolean {
    return shouldDropRejectedItem(item.isHolding)
  }

  fun shouldDropRejectedItem(isHolding: Boolean): Boolean {
    return !isHolding
  }
}
