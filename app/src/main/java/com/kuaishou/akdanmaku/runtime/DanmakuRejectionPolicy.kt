package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.data.DanmakuItem

internal object DanmakuRejectionPolicy {
  fun shouldDropRejectedItem(item: DanmakuItem): Boolean = true
}
