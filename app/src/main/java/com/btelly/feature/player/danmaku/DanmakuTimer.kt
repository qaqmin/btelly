package com.btelly.feature.player.danmaku

import kotlin.math.abs

/**
 * AkDanmaku-style timer:
 * - Uses System.nanoTime() for smooth advancement.
 * - Prioritizes a monotonic local clock while playing for stable motion.
 * - Re-anchors only on explicit playback events (seek/resume/speed change).
 * - Keeps a coarse fallback sync for extreme unreported discontinuities.
 */
internal class DanmakuTimer {
    @Volatile
    private var lastFrameNanos: Long = 0L

    @Volatile
    private var smoothPositionMs: Double = 0.0

    @Volatile
    private var lastSeekSerial: Int = 0

    @Volatile
    private var lastPlaying: Boolean = false

    @Volatile
    private var lastPlaybackSpeed: Double = 1.0

    fun reset(
        positionMs: Long,
        nowNanos: Long,
        seekSerial: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
        lastPlaying = isPlaying
        lastPlaybackSpeed = normalizeSpeed(playbackSpeed)
    }

    fun currentPositionMs(): Long = smoothPositionMs.toLong()

    fun step(
        nowNanos: Long,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        seekSerial: Int,
    ): Long {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val speed = normalizeSpeed(playbackSpeed)
        val lastNanos = lastFrameNanos

        if (lastNanos == 0L || seekSerial != lastSeekSerial) {
            reset(
                positionMs = rawPositionMs,
                nowNanos = nowNanos,
                seekSerial = seekSerial,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
            )
            return smoothPositionMs.toLong()
        }

        val dtNanos = (nowNanos - lastNanos).coerceAtLeast(0L)
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        if (!isPlaying) {
            // 暂停/恢复瞬间 ExoPlayer 的 raw position 常会回退几十~上百毫秒
            // （解码器缓冲固有行为）。若此时无条件重锚到 raw，弹幕会瞬间"时间倒流"。
            // 修复：暂停瞬间保留当前平滑位置不动；暂停中也只允许往前纠偏，禁止回退。
            if (lastPlaying) {
                // 刚从播放切到暂停：保持弹幕停在当前平滑位置，绝不回退。
            } else if (raw - smoothPositionMs >= IDLE_REANCHOR_THRESHOLD_MS) {
                // 暂停中，raw 明显往前跳（如 seek 到更晚位置），才向前重锚。
                smoothPositionMs = raw
            }
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (!lastPlaying || abs(speed - lastPlaybackSpeed) >= SPEED_CHANGE_EPSILON) {
            smoothPositionMs = raw
            lastPlaying = true
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (dtNanos > 0L) {
            val dtMs = dtNanos.toDouble() / 1_000_000.0
            smoothPositionMs += dtMs * speed
        }

        // Clamp for safety.
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) {
            smoothPositionMs = raw
        }
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0
        if (abs(raw - smoothPositionMs) >= EXTREME_DRIFT_REANCHOR_THRESHOLD_MS) {
            // Treat this as an unreported discontinuity instead of gradually bending speed.
            smoothPositionMs = raw
        }
        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs.toLong()
    }

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        playbackSpeed
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 1.0

    private companion object {
        private const val IDLE_REANCHOR_THRESHOLD_MS = 120.0
        private const val EXTREME_DRIFT_REANCHOR_THRESHOLD_MS = 2_000.0
        private const val SPEED_CHANGE_EPSILON = 0.0001
    }
}
