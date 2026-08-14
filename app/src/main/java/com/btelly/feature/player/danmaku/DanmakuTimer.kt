package com.btelly.feature.player.danmaku

import com.btelly.core.common.log.AppLog
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
            val firstInit = lastNanos == 0L
            val before = smoothPositionMs
            reset(
                positionMs = rawPositionMs,
                nowNanos = nowNanos,
                seekSerial = seekSerial,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
            )
            AppLog.i(
                DIAG_TAG,
                "anchor kind=${if (firstInit) "init" else "seekSerial"} " +
                    "before=${before.toLong()}ms after=${smoothPositionMs.toLong()}ms " +
                    "delta=${deltaLabel(smoothPositionMs - before)} play=$isPlaying"
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
                // 记录暂停边界的 raw 差值：恢复瞬间重锚若回拉，这里是上游证据。
                AppLog.i(
                    DIAG_TAG,
                    "pause-edge smooth=${smoothPositionMs.toLong()}ms raw=${raw.toLong()}ms " +
                        "rawDelta=${deltaLabel(raw - smoothPositionMs)}"
                )
            } else if (raw - smoothPositionMs >= IDLE_REANCHOR_THRESHOLD_MS) {
                // 暂停中，raw 明显往前跳（如 seek 到更晚位置），才向前重锚。
                val before = smoothPositionMs
                smoothPositionMs = raw
                AppLog.i(
                    DIAG_TAG,
                    "anchor kind=idle-jump before=${before.toLong()}ms after=${smoothPositionMs.toLong()}ms " +
                        "delta=${deltaLabel(smoothPositionMs - before)}"
                )
            }
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (!lastPlaying || abs(speed - lastPlaybackSpeed) >= SPEED_CHANGE_EPSILON) {
            val before = smoothPositionMs
            smoothPositionMs = raw
            AppLog.i(
                DIAG_TAG,
                "anchor kind=${if (!lastPlaying) "resume" else "speed"} " +
                    "before=${before.toLong()}ms after=${smoothPositionMs.toLong()}ms " +
                    "delta=${deltaLabel(smoothPositionMs - before)} speed=$speed"
            )
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
            // delta 为负 = 平滑位置被回拉（"弹幕倒退/重放误判"上游），
            // 为正 = 播放位置前跳（"elapsed 突增导致弹幕提前退场"上游）。
            val before = smoothPositionMs
            smoothPositionMs = raw
            AppLog.w(
                DIAG_TAG,
                "anchor kind=drift(before=${before.toLong()}ms raw=${raw.toLong()}ms) " +
                    "after=${smoothPositionMs.toLong()}ms delta=${deltaLabel(smoothPositionMs - before)}"
            )
        }
        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs.toLong()
    }

    private fun deltaLabel(delta: Double): String =
        (if (delta >= 0) "+" else "") + delta.toLong() + "ms"

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        playbackSpeed
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 1.0

    private companion object {
        private const val DIAG_TAG = "BTellyDmDiag"

        private const val IDLE_REANCHOR_THRESHOLD_MS = 120.0
        private const val EXTREME_DRIFT_REANCHOR_THRESHOLD_MS = 2_000.0
        private const val SPEED_CHANGE_EPSILON = 0.0001
    }
}
