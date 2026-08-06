package com.btelly.feature.player.danmaku

import kotlin.math.abs
import kotlin.math.exp

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
        val dtMs = dtNanos.toDouble() / 1_000_000.0
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        if (!isPlaying) {
            // 暂停/缓冲：保留当前平滑位置不动；仅当 raw 明显往前跳（如 seek 到更晚位置）才向前收敛。
            // 注意：缓冲期间 ExoPlayer 的 currentPosition 可能已按解码时间前进，但这里不追，
            // 等恢复播放时再由下方恢复分支渐进追上，避免缓冲中弹幕乱跳。
            if (!lastPlaying && raw - smoothPositionMs >= IDLE_REANCHOR_THRESHOLD_MS) {
                smoothPositionMs = convergeTo(raw, dtMs)
            }
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        // 从暂停/缓冲恢复，或倍速变化：不再把平滑位置一次性硬锚到 raw，而是渐进收敛。
        // 这是"所有字幕统一大跳"的根因所在——缓冲期间弹幕停在原地，恢复瞬间 raw 已领先
        // 较多，硬锚会让每一条弹幕在同一帧整体前移 `(raw - smoothPositionMs) * pxPerMs`。
        if (!lastPlaying || abs(speed - lastPlaybackSpeed) >= SPEED_CHANGE_EPSILON) {
            lastPlaying = true
            lastPlaybackSpeed = speed
            smoothPositionMs = convergeTo(raw, dtMs)
            return smoothPositionMs.toLong()
        }

        if (dtNanos > 0L) {
            smoothPositionMs += dtMs * speed
        }

        // Clamp for safety.
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) {
            smoothPositionMs = raw
        }
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0
        if (abs(raw - smoothPositionMs) >= EXTREME_DRIFT_REANCHOR_THRESHOLD_MS) {
            // 未报告的极端漂移：渐进收敛而非瞬间拉回，避免肉眼可见的整体跳变。
            // 收敛期间该分支每帧都会再次命中，convergeTo 幂等，效果为持续的加速追赶。
            smoothPositionMs = convergeTo(raw, dtMs)
        }
        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs.toLong()
    }

    /**
     * 将平滑位置向目标 target 渐进收敛，避免把播放器位置 raw 的一次性硬锚放大成
     * "所有弹幕统一大跳"。以指数衰减速率收敛，时间常数 [CATCH_UP_TIME_CONSTANT_MS]
     * 控制追赶快慢（越小追得越快）；偏差小于 [CATCH_UP_TOLERANCE_MS] 时直接吸合。
     */
    private fun convergeTo(target: Double, deltaMs: Double): Double {
        val diff = target - smoothPositionMs
        if (!diff.isFinite()) return target
        if (abs(diff) <= CATCH_UP_TOLERANCE_MS) return target
        val rate = 1.0 - exp(-deltaMs.coerceAtLeast(0.0) / CATCH_UP_TIME_CONSTANT_MS)
        return smoothPositionMs + diff * rate
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

        // 平滑位置向播放器位置收敛的参数：偏差小于该值直接吸合（肉眼看不出跳变）。
        private const val CATCH_UP_TOLERANCE_MS = 30.0
        // 收敛时间常数（ms）：偏差越大追赶越久，但保持"短暂加速追上"而非"瞬间跳变"。
        // 300ms 时：1 帧(~16ms)收敛约 5%，300ms 收敛约 63%，1s 收敛约 96%。
        private const val CATCH_UP_TIME_CONSTANT_MS = 300.0
    }
}
