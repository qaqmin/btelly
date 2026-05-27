package com.btelly.model.dm

import android.graphics.Path

data class DmMaskData(
    val fps: Int,
    val rawSegments: List<LazyMaskSegment>
)

data class LazyMaskSegment(
    val timeMs: Long,
    val startOffset: Int,
    val endOffset: Int,
    val rawData: ByteArray? = null
) {
    @Volatile
    var cachedFrames: List<MaskFrame>? = null
}

data class MaskFrame(
    /** 该帧对应的视频 PTS（毫秒），由段起始时间 + 帧索引推算。 */
    val presentationTimeMs: Long,
    val paths: List<Path>,
    /** SVG 标定宽度，0 表示未知（回退 320）。 */
    val svgWidth: Int = 0,
    /** SVG 标定高度，0 表示未知（回退 180）。 */
    val svgHeight: Int = 0,
)
