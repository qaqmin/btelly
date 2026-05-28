package com.btelly.model.dm

import android.graphics.Path

data class DmMaskData(
    val fps: Int,
    val rawSegments: List<LazyMaskSegment>,
    /** webmask 文件总字节数，由 HTTP Content-Range/Length 拿到。 */
    val totalFileSize: Long = 0L,
)

/**
 * 单个 mask 段的延迟加载条目。
 *
 * 设计为可变 class（不是 data class）：
 *  - `byteOffset`/`byteEnd` 是文件中的绝对位置（不依赖全文件 ByteArray）
 *  - `segData` 是该段独立的字节切片，**按需 Range 下载填充**
 *  - `cachedFrames` 是 [WebmaskParser.parseSegmentFrames] 解析后的帧列表
 *
 * 三阶段：未加载 → 已下载未解析（segData != null）→ 已解析（cachedFrames != null）。
 * 解析完成后 [segData] 会被释放（避免占用约 100KB × N 段的内存）。
 */
class LazyMaskSegment(
    val timeMs: Long,
    val byteOffset: Long,
    val byteEnd: Long,
) {
    @Volatile
    var segData: ByteArray? = null

    @Volatile
    var cachedFrames: List<MaskFrame>? = null

    /** 该段字节数（= byteEnd - byteOffset）。 */
    fun byteLength(): Int = (byteEnd - byteOffset).toInt()
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
