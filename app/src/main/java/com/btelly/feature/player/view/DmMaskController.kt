package com.btelly.feature.player.view

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.btelly.core.common.log.AppLog
import com.btelly.model.dm.DmMaskRepository
import com.btelly.model.dm.DmMaskTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 弹幕防挡蒙版控制器。
 *
 * 按 Bilibili 参考链路完全重构：
 *  - 播放器 clock（onPlayerClockChanged / player.currentPosition）是唯一时间源
 *  - 清晰的状态机：IDLE → READY → ACTIVE → SEEKING → ACTIVE
 *  - 预加载跟随 player clock，不跟 video frame PTS
 *  - Choreographer JankMonitor 检测主线程卡顿，自动关 mask
 *  - 后台回前台重置 jank EMA，防止误触自动关
 */
class DmMaskController(
    private val maskHostProvider: () -> DanmakuMaskHostLayout?,
    private var repository: DmMaskRepository,
) {
    companion object {
        private const val TAG = "DmMaskController"

        /** seek 后等待 playbackReady 的硬超时。 */
        private const val SEEK_HARD_TIMEOUT_MS = 1500L

        /** 诊断日志节流间隔。 */
        private const val DIAG_LOG_INTERVAL_MS = 5000L

        /** vsync 间隔合法采样范围（ns）。 */
        private const val VSYNC_MIN_NS = 8_000_000L
        private const val VSYNC_MAX_NS = 200_000_000L

        /** 卡顿阈值（vsync 间隔 EMA，ms）——超过 50ms ≈ <20fps。 */
        private const val JANK_INTERVAL_THRESHOLD_MS = 50L

        /** 触发自动关需要持续卡顿的时长。 */
        private const val JANK_TRIGGER_WINDOW_MS = 5000L

        /** 退出卡顿态的滞回阈值。 */
        private const val JANK_RECOVERY_INTERVAL_MS = 30L

        /** 自动关后冷却期。 */
        private const val AUTO_DISABLED_COOLDOWN_MS = 30_000L
    }

    // ---- 状态 ----

    private enum class State { IDLE, READY, ACTIVE, SEEKING }

    private var state = State.IDLE
    private var currentCid: Long = 0L
    private var currentTimeline: DmMaskTimeline? = null
    private var isPlaying: Boolean = false
    private var playbackReady: Boolean = false
    private var enabled: Boolean = false

    // ---- 时钟 ----

    /**
     * 播放器 position 提供者。由外部注入 `player?.currentPosition`。
     * 这是唯一的时间源（参考文档结论 1）。
     */
    var playerPositionProvider: (() -> Long)? = null

    @Volatile
    private var playbackSpeed: Float = 1.0f

    // ---- Seek 状态 ----

    private var seekHardDeadlineMs: Long = 0L

    // ---- 预加载 ----

    private var lastPreloadedSegIndex: Int = -1

    @Volatile
    private var preloadingSegIndex: Int = -1

    // ---- 卡顿检测 ----

    @Volatile
    private var vsyncIntervalEmaNs: Long = 16_666_667L

    @Volatile
    private var jankStartMs: Long = 0L

    @Volatile
    private var autoDisabledUntilMs: Long = 0L

    private val jankMonitor = JankMonitor()

    // ---- 诊断 ----

    @Volatile
    private var diagEnabled: Boolean = false

    @Volatile
    private var lastDiagLogMs: Long = 0L

    // ---- 回调 ----

    @Volatile
    var onMaskAutoDisabled: ((reason: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ========== 公开 API ==========

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        when {
            !enabled -> {
                state = State.IDLE
                maskHostProvider()?.clearCachedMask()
                jankMonitor.stop()
            }
            currentTimeline != null -> {
                // 用户重新打开 → 重置卡顿状态
                autoDisabledUntilMs = 0L
                jankStartMs = 0L
                vsyncIntervalEmaNs = 16_666_667L
                state = if (isPlaying) State.ACTIVE else State.READY
                jankMonitor.start()
                invalidateMaskHost()
            }
        }
    }

    suspend fun loadMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        currentCid = cid
        state = State.IDLE
        lastPreloadedSegIndex = -1
        maskHostProvider()?.clearCachedMask()

        val data = repository.downloadAndParse(maskUrl, cid, fps)
        if (data == null) {
            AppLog.e(TAG, "Mask load failed for cid=$cid")
            return false
        }

        currentTimeline = repository.getTimeline(cid)
        maskHostProvider()?.let { it.timeline = currentTimeline }

        state = if (enabled && isPlaying) State.ACTIVE else State.READY

        if (enabled) {
            jankMonitor.start()
            invalidateMaskHost()
        }

        // 触发初始预加载
        maybePreload(currentVideoPtsMs())
        return true
    }

    /**
     * 唯一时钟入口。由播放器的 onPlayerClockChanged / syncDanmakuPosition 调用。
     * 对齐参考：onPlayerClockChanged(rate, position)。
     */
    fun onPlayerClockChanged(rate: Float, positionMs: Long) {
        if (rate.isFinite() && rate > 0f) {
            playbackSpeed = rate
        }
        if (state == State.ACTIVE) {
            maybePreload(positionMs)
            invalidateMaskHost()
        }
    }

    /** 进度更新时调用（由 syncDanmakuPosition 调用）。 */
    fun pushMaskUpdate() {
        if (state != State.ACTIVE) return
        checkAndPreloadNext()
        invalidateMaskHost()
    }

    /** Seek 操作。进入 SEEKING 状态，保留旧遮罩冻结（不清除），等新 segment 加载后自动替换。 */
    fun onSeek() {
        state = State.SEEKING
        seekHardDeadlineMs = SystemClock.elapsedRealtime() + SEEK_HARD_TIMEOUT_MS
        lastPreloadedSegIndex = -1
        // 不调用 clearCachedMask()：旧遮罩在新 segment 加载完成前继续显示，
        // 避免 seek 后 100~300ms 的无遮罩窗口。
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (playing && state == State.READY && enabled) {
            state = State.ACTIVE
            jankMonitor.start()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed.isFinite() && speed > 0f) {
            playbackSpeed = speed
            lastPreloadedSegIndex = -1  // 速度变了，重新触发预加载
        }
    }

    fun setPlaybackReady(ready: Boolean) {
        playbackReady = ready
        if (ready && state == State.SEEKING) {
            transitionFromSeeking()
        }
    }

    fun onPositionChanged(positionMs: Long) {
        if (state == State.SEEKING && playbackReady) {
            transitionFromSeeking()
        }
        if (state == State.ACTIVE) {
            invalidateMaskHost()
        }
    }

    /**
     * 返回 mask 当前应该查询 timeline 的 PTS（ms）。
     * 唯一时间源：playerPositionProvider（= player.currentPosition）。
     */
    fun currentVideoPtsMs(): Long {
        return (playerPositionProvider?.invoke() ?: 0L).coerceAtLeast(0L)
    }

    /**
     * HostLayout 调用：是否应该渲染遮罩。
     * IDLE → false。READY / ACTIVE / SEEKING → true（SEEKING 时用旧遮罩冻结）。
     */
    fun shouldRenderMask(): Boolean {
        return state != State.IDLE
    }

    /** HostLayout 调用：当前是否处于 SEEKING 冻结状态。 */
    fun isSeeking(): Boolean {
        return state == State.SEEKING
    }

    fun setRepository(repository: DmMaskRepository) {
        this.repository = repository
    }

    /** 后台回前台时调用：重置 jank EMA，防止脏数据触发自动关。 */
    fun onResume() {
        vsyncIntervalEmaNs = 16_666_667L
        jankStartMs = 0L
        if (state == State.ACTIVE) {
            invalidateMaskHost()
        }
    }

    fun release() {
        state = State.IDLE
        currentCid = 0L
        currentTimeline = null
        lastPreloadedSegIndex = -1
        jankMonitor.stop()
        maskHostProvider()?.clearCachedMask()
    }

    fun dispose() {
        release()
    }

    // ---- 诊断 API（保持兼容） ----

    fun reportFrameQuery(queryPtsMs: Long, framePtsMs: Long) {
        if (!diagEnabled) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDiagLogMs < DIAG_LOG_INTERVAL_MS) return
        lastDiagLogMs = nowMs
        val playerPos = playerPositionProvider?.invoke() ?: -1L
        AppLog.d(
            TAG,
            "pts diag: query=$queryPtsMs frame.pts=$framePtsMs frame-query=${framePtsMs - queryPtsMs}ms " +
                "playerPos=$playerPos speed=$playbackSpeed state=$state"
        )
    }

    fun setDiagEnabled(enabled: Boolean) {
        diagEnabled = enabled
    }

    // 以下为兼容空实现（参考方案不用这些，但 MyPlayerView 有接线）
    fun reportFramePipelineDelay(@Suppress("UNUSED_PARAMETER") totalDurationNs: Long) {}
    fun reportVsyncPeriod(@Suppress("UNUSED_PARAMETER") periodNs: Long) {}
    fun onVideoFrameAnchor(
        @Suppress("UNUSED_PARAMETER") presentationTimeUs: Long,
        @Suppress("UNUSED_PARAMETER") releaseTimeNs: Long,
    ) {}

    // ========== 内部实现 ==========

    private fun transitionFromSeeking() {
        state = if (enabled && isPlaying) State.ACTIVE else State.READY
        if (state == State.ACTIVE) {
            maybePreload(currentVideoPtsMs())
            invalidateMaskHost()
        }
    }

    private fun checkAndPreloadNext() {
        val timeline = currentTimeline ?: return
        val pts = currentVideoPtsMs()
        val segIdx = timeline.segmentIndexAt(pts)
        if (segIdx > lastPreloadedSegIndex || !timeline.isSegmentCached(segIdx)) {
            preloadAhead(segIdx)
            lastPreloadedSegIndex = segIdx
        }
    }

    /**
     * 预加载：当前段 ± 2。由 player clock 驱动。
     */
    private fun maybePreload(ptsMs: Long) {
        val timeline = currentTimeline ?: return
        val segIdx = timeline.segmentIndexAt(ptsMs)
        if (segIdx == preloadingSegIndex) return
        if (timeline.isSegmentCached(segIdx) && timeline.isSegmentCached(segIdx + 1)) {
            lastPreloadedSegIndex = segIdx
            return
        }
        preloadAhead(segIdx)
        lastPreloadedSegIndex = segIdx
    }

    private fun preloadAhead(currentSegIdx: Int) {
        val cid = currentCid
        val timeline = currentTimeline ?: return
        if (preloadingSegIndex == currentSegIdx) return
        preloadingSegIndex = currentSegIdx
        val totalSegs = timeline.totalSegments()
        val range = (currentSegIdx - 1).coerceAtLeast(0)..(currentSegIdx + 2).coerceAtMost(totalSegs - 1)
        preloadScope.launch {
            try {
                for (idx in range) {
                    repository.preloadSegmentFrames(cid, idx)
                }
            } finally {
                if (preloadingSegIndex == currentSegIdx) preloadingSegIndex = -1
            }
        }
    }

    private fun invalidateMaskHost() {
        maskHostProvider()?.postInvalidateOnAnimation()
    }

    private fun triggerAutoDisable(fpsAtTrigger: Float) {
        autoDisabledUntilMs = SystemClock.elapsedRealtime() + AUTO_DISABLED_COOLDOWN_MS
        jankStartMs = 0L
        val reason = "渲染掉帧严重（${fpsAtTrigger.toInt()}fps），已自动关闭弹幕防遮挡"
        AppLog.d(TAG, "auto-disable mask: vsync-fps=$fpsAtTrigger")
        mainHandler.post {
            setEnabled(false)
            onMaskAutoDisabled?.invoke(reason)
        }
    }

    // ========== JankMonitor ==========

    /**
     * 基于 Choreographer 的主线程 vsync 间隔监控器。
     *
     * 信号源选择：主线程 vsync 间隔 = UI 整体卡顿率的直接量度。
     * 视频 surface 是独立 SurfaceView，不通过 view 系统重绘。
     */
    private inner class JankMonitor : Choreographer.FrameCallback {
        @Volatile
        private var running: Boolean = false
        private var choreographer: Choreographer? = null
        private var lastFrameNs: Long = 0L

        fun start() {
            mainHandler.post {
                if (running) return@post
                running = true
                lastFrameNs = 0L
                val ch = choreographer ?: Choreographer.getInstance().also { choreographer = it }
                ch.postFrameCallback(this)
            }
        }

        fun stop() {
            mainHandler.post {
                if (!running) return@post
                running = false
                choreographer?.removeFrameCallback(this)
                lastFrameNs = 0L
            }
        }

        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            sampleAndMaybeTrigger(frameTimeNs)
            // ACTIVE 状态下每 vsync invalidate mask host，保证遮罩实时更新
            if (state == State.ACTIVE) {
                invalidateMaskHost()
            }
            choreographer?.postFrameCallback(this)
        }

        private fun sampleAndMaybeTrigger(frameTimeNs: Long) {
            val last = lastFrameNs
            lastFrameNs = frameTimeNs
            if (last <= 0L) return
            val intervalNs = frameTimeNs - last
            if (intervalNs !in VSYNC_MIN_NS..VSYNC_MAX_NS) return

            // 7/8 旧权重 + 1/8 新值
            vsyncIntervalEmaNs = (vsyncIntervalEmaNs * 7 + intervalNs) / 8

            if (state != State.ACTIVE) {
                jankStartMs = 0L
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now < autoDisabledUntilMs) return
            val intervalMs = vsyncIntervalEmaNs / 1_000_000L

            if (intervalMs > JANK_INTERVAL_THRESHOLD_MS) {
                if (jankStartMs == 0L) {
                    jankStartMs = now
                } else if (now - jankStartMs >= JANK_TRIGGER_WINDOW_MS) {
                    triggerAutoDisable(1000f / intervalMs)
                }
            } else if (intervalMs <= JANK_RECOVERY_INTERVAL_MS) {
                jankStartMs = 0L
            }
        }
    }
}
