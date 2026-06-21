package com.btelly.core.emote

import android.graphics.Bitmap

/**
 * 表情图加载器 stub（no-op）。
 *
 * 上游实现 弹幕引擎引用了 [EmoteBitmapLoader] 来加载 `[doge]` 类表情图。
 * 当前阶段不迁移真实表情功能，用 no-op stub 让引擎代码里的表情分支天然不触发
 * （getCached 永远返回 null，引擎回退到纯文本绘制路径）。
 *
 * 后续要支持表情时，在这里实现真正的 bitmap 加载逻辑即可。
 */
object EmoteBitmapLoader {
    fun getCached(url: String): Bitmap? = null
    fun prefetch(url: String?) {}
    fun load(url: String, onResult: (Bitmap?) -> Unit) {
        onResult(null)
    }
}
