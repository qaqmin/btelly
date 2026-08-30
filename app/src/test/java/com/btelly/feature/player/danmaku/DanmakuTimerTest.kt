package com.btelly.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合时钟策略的行为回归（硬锚仅限 seek，其余偏差渐进收敛）：
 * - resume/未报告漂移不得出现"同帧整体跳变"（PR #53 的动机）
 * - 偏差须在有限时间内收敛到 raw 附近（不能永久滞后）
 * - seek 仍瞬时硬锚、暂停边界仍不回退、softSyncFactor 仍然生效、极端漂移硬锚兜底
 */
class DanmakuTimerTest {

    private val frameNs = 16_666_667L
    private val frameMs = 17L

    @Test
    fun resumeAfterBufferingConvergesWithoutSingleFrameJump() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L

        // 初始化 + 正常播放，raw 与平滑位置同步推进
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(60) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 缓冲：暂停数帧，raw 按解码位继续前进，与平滑位置拉开偏差
        repeat(8) {
            now += frameNs
            rawMs += 100
            timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        }
        val gapAtResume = rawMs - timer.currentPositionMs()
        assertTrue("暂停期间未拉开预期偏差 gap=$gapAtResume", gapAtResume in 300L..900L)

        // 恢复播放：resume 帧不得硬跳到 raw（弹幕整体跳变的根因）
        now += frameNs
        rawMs += frameMs
        val resumePos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        assertTrue("resume 帧发生硬跳 resume=$resumePos raw=$rawMs", resumePos <= rawMs - gapAtResume + frameMs)

        // 之后渐进收敛：单帧前进量有界，且 1.5s 内贴近 raw
        var maxStep = 0L
        var prev = resumePos
        var converged = false
        repeat(90) {
            now += frameNs
            rawMs += frameMs
            val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
            maxStep = maxOf(maxStep, pos - prev)
            prev = pos
            if (rawMs - pos in 0L..30L) converged = true
        }
        assertTrue("1.5s 内未收敛到 30ms 内，最终 gap=${rawMs - prev}", converged)
        assertTrue("收敛期间出现单帧大跳 $maxStep ms", maxStep in 0L..60L)
    }

    @Test
    fun seekStillHardAnchorsImmediately() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 用户 seek：seekSerial 变化，必须瞬时硬锚
        rawMs = 120_000L
        now += frameNs
        val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 1)
        assertEquals(120_000L, pos)
    }

    @Test
    fun pauseEdgeKeepsPositionAndNeverMovesBackward() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }
        val smoothBefore = timer.currentPositionMs()

        // 暂停瞬间 raw 回退几十毫秒（解码缓冲固有行为）：平滑位置必须保持不动
        now += frameNs
        rawMs -= 200
        val pausedPos = timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        assertEquals(smoothBefore, pausedPos)
    }

    @Test
    fun pausedForwardGapConvergesGradually() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 暂停中 raw 明显前跳（≥120ms 触发向前纠偏）：应渐进收敛而非一次性硬锚
        now += frameNs
        rawMs += 500
        val first = timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        assertTrue("暂停纠偏首帧移动过大 ${(first - 60_000L) - 30 * frameMs}", first - 60_510L < 100)

        var converged = false
        repeat(120) {
            now += frameNs
            timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
            if (rawMs - timer.currentPositionMs() in 0L..30L) converged = true
        }
        assertTrue("暂停纠偏未收敛", converged)
    }

    @Test
    fun softSyncFactorStillScalesFrameAdvance() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        timer.softSyncFactor = 1.1
        val before = timer.currentPositionMs()
        now += frameNs
        rawMs += frameMs
        val after = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        // 1 帧约 16.7ms × 1.1 ≈ 18ms（死区内不会触发渐进追赶，缩放因子直接生效）
        assertEquals(18L, after - before)
    }

    @Test
    fun extremeUnreportedDiscontinuityStillHardReanchors() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 100_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 未报告的前跳 6s（seekSerial 不变）：超过 HARD_REANCHOR_GAP_MS，退回一次性硬锚
        rawMs += 6_000
        now += frameNs
        val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        assertEquals(rawMs, pos)
    }
}
