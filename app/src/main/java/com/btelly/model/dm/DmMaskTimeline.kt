package com.btelly.model.dm

import com.btelly.core.common.log.AppLog

/**
 * 基于 segment 的懒加载时间线。
 *
 * 不一次性解析所有 segment（124 段 × 300 帧 = 15+ 秒），
 * 而是预解析 segment 0，其余按需解析。query 在 O(1) 定位段 + O(1) 定位帧。
 * 段内帧解析结果缓存在 [LazyMaskSegment.cachedFrames]，同一段只解析一次。
 */
class DmMaskTimeline(
    private val segments: List<LazyMaskSegment>,
    private val fps: Int,
) {

    companion object {
        private const val TAG = "DmMaskTimeline"

        /**
         * 构建时间线：只预解析 segment 0，其余按需解析。
         * 必须在 IO/后台线程调用。
         */
        fun build(data: DmMaskData): DmMaskTimeline? {
            val segments = data.rawSegments
            if (segments.isEmpty()) return null

            // 预解析首段
            val seg0 = segments[0]
            if (seg0.cachedFrames == null) {
                val segDurationMs = if (segments.size > 1) {
                    segments[1].timeMs - seg0.timeMs
                } else {
                    300L * 1000L / data.fps.coerceAtLeast(1)
                }
                WebmaskParser.parseSegmentFrames(seg0, data.fps, segDurationMs)?.let {
                    seg0.cachedFrames = it
                    AppLog.d(TAG, "Segment 0 pre-parsed: frames=${it.size}")
                }
            }

            AppLog.d(TAG, "Timeline built: segments=${segments.size}, fps=${data.fps}, " +
                "range=[${segments.first().timeMs}..${segments.last().timeMs}]ms")
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
