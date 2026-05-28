package com.btelly.model.dm

/**
 * 基于 segment 的懒加载时间线。
 *
 * Range 加载策略下，构造时段字节均未下载——
 * 实际下载 + 解析由 [com.btelly.feature.player.view.DmMaskController.preloadAhead]
 * 在后台按播放进度触发。本 timeline 只负责：
 *  - O(log N) 二分定位 segment
 *  - O(1) 在 [LazyMaskSegment.cachedFrames] 里 round-to-nearest 取帧
 *  - 主线程查询绝不触发解析（dispatchDraw 不能同步阻塞）
 */
class DmMaskTimeline(
    private val segments: List<LazyMaskSegment>,
    private val fps: Int,
) {

    companion object {
        fun build(data: DmMaskData): DmMaskTimeline? {
            val segments = data.rawSegments
            if (segments.isEmpty()) return null
            return DmMaskTimeline(segments, data.fps)
        }
    }

    /**
     * 查询指定 PTS 最近的帧。
     * 1. O(log N) 二分定位 segment
     * 2. 懒解析该 segment（首次访问时）
     * 3. round-to-nearest 定位帧
     */
    fun queryAt(ptsMs: Long): MaskFrame? {
        if (segments.isEmpty()) return null
        if (ptsMs <= segments.first().timeMs) return queryInSegment(0, ptsMs)
        if (ptsMs >= segments.last().timeMs) return queryInSegment(segments.lastIndex, ptsMs)

        // 二分查找：找到 ptsMs 所在的 segment
        val segIdx = segments.binarySearchBy(ptsMs) { it.timeMs }
            .let { if (it < 0) -(it + 1) - 1 else it }
            .coerceIn(0, segments.lastIndex)

        return queryInSegment(segIdx, ptsMs)
    }

    private fun queryInSegment(segIdx: Int, ptsMs: Long): MaskFrame? {
        val segment = segments[segIdx]

        // 只查缓存，不触发解析——避免在主线程 dispatchDraw 中同步解析 SVG
        val frames = segment.cachedFrames ?: return null
        if (frames.isEmpty()) return null

        // round-to-nearest 定位帧
        val segDurationMs = if (segIdx + 1 < segments.size) {
            (segments[segIdx + 1].timeMs - segment.timeMs).coerceAtLeast(1)
        } else {
            (frames.size.toLong() * 1000L / fps.coerceAtLeast(1)).coerceAtLeast(1)
        }
        val offsetMs = ptsMs - segment.timeMs
        val frameIndex = ((offsetMs * frames.size + segDurationMs / 2) / segDurationMs).toInt()
            .coerceIn(0, frames.size - 1)

        return frames.getOrNull(frameIndex)
    }

    fun totalSegments(): Int = segments.size

    /** 根据PTS粗略定位当前段索引。 */
    fun segmentIndexAt(ptsMs: Long): Int {
        if (segments.isEmpty()) return 0
        if (ptsMs <= segments.first().timeMs) return 0
        if (ptsMs >= segments.last().timeMs) return segments.lastIndex
        return segments.binarySearchBy(ptsMs) { it.timeMs }
            .let { if (it < 0) -(it + 1) - 1 else it }
            .coerceIn(0, segments.lastIndex)
    }

    /** 检查指定段是否已缓存帧。 */
    fun isSegmentCached(segIdx: Int): Boolean {
        if (segIdx < 0 || segIdx >= segments.size) return true
        return segments[segIdx].cachedFrames != null
    }
}
