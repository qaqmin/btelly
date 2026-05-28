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
 * 弹幕防挡蒙版控制器（clipOutPath 方案）。
 *
 * 只保留：
 *  - anchor 接入（视频帧 PTS ↔ wall clock）
 *  - seek 状态机
 *  - currentVideoPtsMs() 一个公式
 *  - 后台段预解析（preloadThread）
 */
class DmMaskController(
    private val maskHostProvider: () -> DanmakuMaskHostLayout?,
    private var repository: DmMaskRepository
) {
    companion object {
        private const val TAG = "DmMaskController"
        private const val SEEK_READY_STABILIZE_MS = 80L
        private const val SEEK_RECOVER_HARD_TIMEOUT_MS = 1500L

        /**
         * 屏幕 vsync 周期兜底（ms）—— 60Hz。Controller 会尽力从 Display 拿真实刷新率，
         * 拿不到才用这个值。
         */
        private const val DEFAULT_VSYNC_PERIOD_MS = 17L

        /**
         * D_mask 初值（ms） = 2 vsync。Android 文档建议视图系统稳态延迟为 2 vsync
         * （RenderThread 上传 + SurfaceFlinger 合成）。在 [reportFramePipelineDelay] 上报
         * 真实测量值之前用此初值，几帧后就被实测值覆盖。
         */
        private const val PIPELINE_DELAY_INIT_MS = 33L

        /** 同步诊断日志节流间隔（默认 5 秒，且默认 [diagEnabled] = false 完全静默）。 */
        private const val DIAG_LOG_INTERVAL_MS = 5000L

        /** vsync 间隔合法采样范围（ns）：8ms~200ms。<8ms 是异常采样、>200ms 是后台/卡死，都跳过。 */
        private const val VSYNC_MIN_NS = 8_000_000L
        private const val VSYNC_MAX_NS = 200_000_000L

        /**
         * 触发自动关的「卡顿」阈值（vsync 间隔 EMA，ms）。
         *
         * - 60Hz 屏正常 vsync 间隔 16.67ms
         * - 120Hz 屏正常 8.33ms
         * - 间隔 EMA > 50ms 意味着主线程平均每帧需要 50ms+ 才能交付，等同于 < 20fps，
         *   绝大多数视频场景下用户已经能明显感受卡顿
         * - 取 50ms 留 3× 安全余量，避免短促 GC / scroll snap 引起的瞬时尖峰误触
         */
        private const val JANK_INTERVAL_THRESHOLD_MS = 50L

        /** 触发自动关需要持续低于上面阈值的时长（ms）：5 秒保险，避免误关。 */
        private const val JANK_TRIGGER_WINDOW_MS = 5000L

        /** 退出「卡顿态」的滞回阈值（ms）——EMA 回到 30ms 以下才认为恢复，避免抖动。 */
        private const val JANK_RECOVERY_INTERVAL_MS = 30L

        /** 自动关后冷却期：期间不再因卡顿触发。用户可在设置里手动重开。 */
        private const val AUTO_DISABLED_COOLDOWN_MS = 30_000L
    }

    private var enabled = false
    private var currentCid: Long = 0L
    private var maskReady = false
    private var currentTimeline: DmMaskTimeline? = null

    private var isPlaying: Boolean = false
    private var playbackReady: Boolean = false

    @Volatile
    private var playbackSpeed: Float = 1.0f

    @Volatile
    private var hasVideoAnchor: Boolean = false
    @Volatile
    private var anchorPresentationTimeUs: Long = 0L
    @Volatile
    private var anchorReleaseTimeNs: Long = 0L

    private var awaitingSeekReady: Boolean = false
    private var seekReadyAt: Long = 0L
    private var seekHardDeadlineMs: Long = 0L

    private var lastPreloadedSegIndex: Int = -1

    /**
     * 当前正在处理的预解析段索引（用于去重，避免 anchor 60Hz 重复 launch 协程）。
     * -1 表示空闲。仅在主/playback 线程读写——anchor 路径用 @Volatile 隔离。
     */
    @Volatile
    private var preloadingSegIndex: Int = -1

    /** 上次输出同步诊断的时刻（节流用）。 */
    @Volatile
    private var lastDiagLogMs: Long = 0L

    /**
     * 同步诊断日志开关。
     *
     * 默认关闭；调试对齐时可临时打开。
     */
    @Volatile
    private var diagEnabled: Boolean = false

    var playerPositionProvider: (() -> Long)? = null

    /**
     * mask 被「性能保护」自动关闭时的回调（已切到主线程触发）。
     * 上层（PlayerActivity）订阅这个就能在底部弹 toast 提示用户。
     *
     * 参数 reason：可直接展示给用户的中文文案。
     */
    @Volatile
    var onMaskAutoDisabled: ((reason: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 主线程 vsync 间隔 EMA（ns）。初值 16.67ms 避免冷启动误触。
     * 仅由 [JankMonitor.doFrame] 在主线程更新；其他位置只读取。
     */
    @Volatile
    private var vsyncIntervalEmaNs: Long = 16_666_667L

    /** 首次跌破阈值的时刻（ms），0 表示当前不在卡顿态。 */
    @Volatile
    private var jankStartMs: Long = 0L

    /** 冷却截止时刻：自动关 mask 后，到这之前再卡顿也不触发。 */
    @Volatile
    private var autoDisabledUntilMs: Long = 0L

    /** vsync 监控器（懒构造，必须在主线程访问 Choreographer）。 */
    private val jankMonitor = JankMonitor()

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            clearMask()
            jankMonitor.stop()
        } else if (maskReady) {
            // 用户重新打开 → 清掉冷却和卡顿计数，给一次新机会。
            autoDisabledUntilMs = 0L
            jankStartMs = 0L
            vsyncIntervalEmaNs = 16_666_667L
            invalidateMaskHost()
            jankMonitor.start()
        }
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

    /**
     * 基于 [Choreographer] 的主线程 vsync 间隔监控器。
     *
     * 信号源选择理由（替代之前错误的 dispatchDraw 频率）：
     *  - 主线程 vsync 间隔 = UI 整体卡顿率的直接量度。
     *    mask 拖累主线程时，vsync 会被推迟、间隔变大；mask 不拖累的话只要弹幕在
     *    invalidate（akdanmaku 内部 60Hz Choreographer 驱动），vsync 一定贴近 16.67ms。
     *  - 视频 surface 是独立 SurfaceView，**不会**通过 view 系统重绘——所以早先用
     *    DanmakuMaskHostLayout.dispatchDraw 频率当 fps 是错的：弹幕少的时候 host 几乎不重绘，
     *    fps 被误读为很低，导致「视频不卡却报掉帧」的 bug。
     *
     * 启停规则（避免空转）：
     *  - [setEnabled] true + maskReady → start
     *  - [setEnabled] false / [release] → stop
     *  - Choreographer.postFrameCallback 必须在创建它的 Looper 上调用——全程切到主线程。
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
            choreographer?.postFrameCallback(this)
        }

        private fun sampleAndMaybeTrigger(frameTimeNs: Long) {
            val last = lastFrameNs
            lastFrameNs = frameTimeNs
            if (last <= 0L) return
            val intervalNs = frameTimeNs - last
            if (intervalNs !in VSYNC_MIN_NS..VSYNC_MAX_NS) return

            // 7/8 旧权重 + 1/8 新值——~8 帧反应时间约 130ms，足够屏蔽 GC 尖峰。
            vsyncIntervalEmaNs = (vsyncIntervalEmaNs * 7 + intervalNs) / 8

            if (!isPlaying || !enabled || !maskReady) {
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
                // 滞回：必须 EMA 间隔回到 30ms 以下才认为脱离卡顿态
                jankStartMs = 0L
            }
        }
    }

    suspend fun loadMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        currentCid = cid
        maskReady = false
        lastPreloadedSegIndex = -1
        clearMask()

        val data = repository.downloadAndParse(maskUrl, cid, fps)
        maskReady = data != null
        if (!maskReady) {
            AppLog.e(TAG, "Mask load failed for cid=$cid")
            return false
        }
        currentTimeline = repository.getTimeline(cid)
        maskHostProvider()?.let { host -> host.timeline = currentTimeline }
        if (enabled) invalidateMaskHost()
        preloadAhead(0)
        return true
    }

    fun pushMaskUpdate() {
        if (!enabled || !maskReady || currentCid <= 0L) return
        if (shouldSkipForSeek()) {
            clearMask()
            return
        }
        // 检查当前段是否需要预解析下一段
        checkAndPreloadNext()
        invalidateMaskHost()
    }

    /**
     * 返回 mask 当前应该查询 timeline 的 PTS（ms）。
     *
     * # 第一性原理公式（三项物理量，零魔法数字）
     *
     * ```
     * query = anchor.pts + (ageMs + D_mask_relative) × playbackSpeed
     * ```
     *
     * 我们想让 query 等于「mask 真实上屏的瞬间，屏上正在显示的 video PTS」。
     * 每一项都是可测/已知物理量：
     *
     * ## 1. `anchor.pts + ageMs` —— 现在屏上的 video PTS
     * - `anchor.pts` 是 ExoPlayer 通过 [VideoFrameMetadataListener] 告知的「PTS=P 的视频帧
     *   将于 anchor.releaseTimeNs 上屏」
     * - `ageMs = now − anchor.releaseTimeNs`（anchor 在未来时为负数）。把「未来帧」拉回到
     *   「now 时刻」，得到当前 wall clock 屏上正在显示的 video PTS
     * - 这一项处理 ExoPlayer 解码队列深度的差异：硬解码 ageMs≈-16~-35，软解码可达 -500
     *
     * ## 2. `D_mask_relative` —— mask 相对 video Surface 多出来的管线延迟
     * - 通过 [Window.OnFrameMetricsAvailableListener]（API 24+）实测 [FrameMetrics.TOTAL_DURATION]，
     *   加 1 vsync（SurfaceFlinger 合成）得到完整的 dispatchDraw → present 延迟
     * - ExoPlayer 的 video anchor 已经按 video Surface 时钟排程，所以这里要减掉 video Surface
     *   自己的 1 vsync 合成延迟，只补 mask layer 额外多走的部分
     * - 由 [reportFramePipelineDelay] 上报后 EMA 平滑，存于 [pipelineDelayMsEma]
     * - 不再硬编码 32/48/67ms 这类魔法数字；设备 / 负载变化时自适应
     * - <Android N 拿不到 metrics 时退回 [PIPELINE_DELAY_INIT_MS]=33ms（2 vsync 的官方约定值）
     *
     * ## `× playbackSpeed`
     * - 所有时间偏移都是 wall clock 量，按播放速度映射回 media 时间轴
     *
     * # 推导
     *
     * mask 真实上屏 wall clock 时刻 `t = now + D_mask_relative`，那一刻屏上的 video PTS：
     * ```
     * P_onscreen(t) = anchor.pts + (t - anchor.releaseTimeNs)
     *               = anchor.pts + (now - anchor.releaseTimeNs) + D_mask_relative
     *               = anchor.pts + ageMs + D_mask_relative
     * ```
     *
     * playerPos 只在两种 fallback 用：
     *  1. 启动期 anchor 还没推出
     *  2. seek 后 anchor 被 onSeek() 清掉、还没有新 anchor 的 ~100ms 窗口
     */
    fun currentVideoPtsMs(): Long {
        if (hasVideoAnchor && isPlaying) {
            val nowNs = System.nanoTime()
            val ageMs = (nowNs - anchorReleaseTimeNs) / 1_000_000L
            return DmMaskPtsEstimator.estimateFromAnchor(
                anchorPtsMs = anchorPresentationTimeUs / 1000L,
                anchorAgeMs = ageMs,
                pipelineDelayMs = pipelineDelayMsEma,
                vsyncPeriodMs = vsyncPeriodMs,
                playbackSpeed = playbackSpeed,
                anchorIntervalMs = anchorIntervalMsEma,
            )
        }
        if (hasVideoAnchor) return anchorPresentationTimeUs / 1000L
        val pos = playerPositionProvider?.invoke() ?: 0L
        return pos.coerceAtLeast(0L)
    }

    /**
     * mask 渲染管线延迟 EMA（ms），由 [reportFramePipelineDelay] 实测更新。
     * 初值 [PIPELINE_DELAY_INIT_MS]（2 vsync），几帧后被真实测量值覆盖。
     */
    @Volatile
    private var pipelineDelayMsEma: Long = PIPELINE_DELAY_INIT_MS

    /**
     * SurfaceFlinger 一个 vsync 周期（ms）兜底——60Hz=17ms。
     * 未来可在 attach 到 window 后读 `Display.getRefreshRate()` 替换为真实刷新率。
     */
    @Volatile
    private var vsyncPeriodMs: Long = DEFAULT_VSYNC_PERIOD_MS

    /**
     * 由 [DanmakuMaskHostLayout] 上报：本次 frame 的 [FrameMetrics.TOTAL_DURATION]（ns）。
     *
     * D_mask = TOTAL_DURATION + 1 vsync（SurfaceFlinger 合成→显示）。
     * 用 EMA(α=1/8) 平滑：几帧后即可稳定收敛到真实管线延迟。
     *
     * 合法范围 [4ms, 200ms]：
     *  - <4ms：metrics 异常（帧被丢弃），忽略
     *  - >200ms：单帧严重超时（GC / 后台 / 模拟器抖动），忽略避免污染 EMA
     */
    fun reportFramePipelineDelay(totalDurationNs: Long) {
        if (totalDurationNs <= 0L) return
        val totalMs = totalDurationNs / 1_000_000L
        if (totalMs !in 4L..200L) return
        val pipelineMs = totalMs + vsyncPeriodMs
        pipelineDelayMsEma = (pipelineDelayMsEma * 7 + pipelineMs) / 8
    }

    /**
     * 由 host attach 时调用：把屏幕真实 vsync 周期（ns）传进来。
     * 高刷屏（90/120Hz）周期更短，避免硬编码 60Hz=17ms 导致 D_mask 偏大。
     */
    fun reportVsyncPeriod(periodNs: Long) {
        if (periodNs <= 0L) return
        val periodMs = (periodNs / 1_000_000L).coerceIn(4L, 100L)
        if (periodMs != vsyncPeriodMs) {
            vsyncPeriodMs = periodMs
            AppLog.d(TAG, "vsync period → ${periodMs}ms (${1_000_000_000L / periodNs}Hz)")
        }
    }

    /**
     * 由 [DanmakuMaskHostLayout.dispatchDraw] 在每次成功查到 mask frame 后调用。
     * 当 frame 引用变化或每秒采样窗口到达时输出一条诊断，让对齐偏差具体到数字：
     *
     *  - `query`：mask 用来查 timeline 的 PTS（含 lookahead）
     *  - `frame.pts`：timeline 返回的 mask frame 实际 PTS（presentationTimeMs）
     *  - `frame-query`：query 与实际 frame.pts 的偏差，± maskDt/2 是正常 round-to-nearest 量纲
     *  - `anchor.pts`：最近 video frame anchor 的 PTS
     *  - `anchor.age`：anchor 距 now 的 wall clock 间隔（越大说明 anchor 推送越落后）
     *  - `anchor.interval(ema)`：anchor 推送的 EMA 间隔（video 帧率倒数，<= 50ms 正常）
     *  - `playerPos`：ExoPlayer.currentPosition（master clock）
     *  - `D_mask(ema)`：实测的 mask 渲染管线延迟；`relative`：扣掉 video Surface 合成后的补偿
     */
    fun reportFrameQuery(queryPtsMs: Long, framePtsMs: Long) {
        if (!diagEnabled) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDiagLogMs < DIAG_LOG_INTERVAL_MS) return
        lastDiagLogMs = nowMs
        val playerPos = playerPositionProvider?.invoke() ?: -1L
        val anchorPtsMs = if (hasVideoAnchor) anchorPresentationTimeUs / 1000L else -1L
        val nowNs = System.nanoTime()
        val anchorAgeMs = if (hasVideoAnchor) (nowNs - anchorReleaseTimeNs) / 1_000_000L else -1L
        val frameMinusQuery = framePtsMs - queryPtsMs
        val queryMinusPlayer = if (playerPos > 0) queryPtsMs - playerPos else 0L
        val relativeDelayMs = (pipelineDelayMsEma - vsyncPeriodMs).coerceAtLeast(0L)
        AppLog.d(
            TAG,
            "pts diag: query=${queryPtsMs}ms frame.pts=${framePtsMs}ms frame-query=${frameMinusQuery}ms " +
                "query-player=${queryMinusPlayer}ms anchor.pts=${anchorPtsMs}ms anchor.age=${anchorAgeMs}ms " +
                "anchor.interval(ema)=${anchorIntervalMsEma}ms playerPos=${playerPos}ms " +
                "speed=$playbackSpeed playing=$isPlaying " +
                "D_mask(ema)=${pipelineDelayMsEma}ms vsync=${vsyncPeriodMs}ms relative=${relativeDelayMs}ms"
        )
    }

    /**
     * 打开/关闭 pts diag 日志（默认关）。
     * 调试对齐时打开看 query-player 偏差，定位完再关。
     */
    fun setDiagEnabled(enabled: Boolean) {
        if (diagEnabled == enabled) return
        diagEnabled = enabled
        AppLog.d(TAG, "diagEnabled → $enabled")
    }

    /**
     * 供 [DanmakuMaskHostLayout.shouldRenderMask] 调用：mask 数据未就绪、用户关闭、
     * 或 seek 等待视频首帧的窗口期都返回 false，host 直接走原始 dispatchDraw 不裁剪，
     * 避免「mask 跳到新 PTS 而视频还停在旧位置」的可见错位。
     */
    fun shouldRenderMask(): Boolean {
        if (!enabled || !maskReady || currentCid <= 0L) return false
        if (shouldSkipForSeek()) return false
        return true
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed.isFinite() && speed > 0f) {
            playbackSpeed = speed
        }
    }

    fun onVideoFrameAnchor(presentationTimeUs: Long, releaseTimeNs: Long) {
        // VideoFrameMetadataListener 协议：
        //   releaseTimeNs == 0      → "立刻 release"（特殊指令值）
        //   releaseTimeNs == MAX    → "暂不 release"（特殊指令值）
        // 都不能当 wall clock 用——用它做差会算出几十亿 ns 的 deltaNs，让 mask PTS 飞到几十秒后。
        // 直接退化成 nanoTime()，让 deltaNs ≈ 0，mask 用 anchor.pts 渲染（基本对齐）。
        val safeReleaseNs = if (releaseTimeNs > 0L && releaseTimeNs < Long.MAX_VALUE) {
            releaseTimeNs
        } else {
            System.nanoTime()
        }
        anchorPresentationTimeUs = presentationTimeUs
        anchorReleaseTimeNs = safeReleaseNs
        hasVideoAnchor = true

        // 顺手测 anchor 推送间隔：间隔异常大（如 > 80ms）意味着 video 解码卡顿，
        // mask 外推距离过远，必然错位——日志输出后用户能立刻判断是 video 端的问题。
        recordAnchorIntervalAndMaybeWarn(safeReleaseNs)

        // anchor 是视频解码侧 60Hz 推送的高频回调——天然是「播放推进」的真实信号。
        // 利用它驱动段进度预解析，避免「pushMaskUpdate 只在 seek 触发」导致播放跨段后
        // timeline 永远查不到 cachedFrames 的死局。
        maybePreloadAroundCurrentPts(presentationTimeUs / 1000L)
    }

    @Volatile
    private var lastAnchorReleaseNs: Long = 0L
    @Volatile
    private var anchorIntervalMsEma: Long = 0L

    private fun recordAnchorIntervalAndMaybeWarn(releaseNs: Long) {
        val prev = lastAnchorReleaseNs
        lastAnchorReleaseNs = releaseNs
        if (prev <= 0L) return
        val intervalMs = (releaseNs - prev) / 1_000_000L
        // 5~150ms 之间是正常视频帧间隔（6.7~200fps）；外面的极可能是 seek 跳变 / 渲染抖动。
        if (intervalMs !in 5L..150L) return
        anchorIntervalMsEma = if (anchorIntervalMsEma == 0L) intervalMs
        else (anchorIntervalMsEma * 7 + intervalMs) / 8
    }

    /**
     * 由 [onVideoFrameAnchor] 在 playback thread 调用。
     * 跨段或下一段未缓存时，去重 launch 一次后台预解析（current ± 2 段）。
     */
    private fun maybePreloadAroundCurrentPts(ptsMs: Long) {
        val timeline = currentTimeline ?: return
        val segIdx = timeline.segmentIndexAt(ptsMs)
        if (segIdx == preloadingSegIndex) return
        // 当前段及下一段都已缓存 → 无需 launch（避免每秒 60 次无效协程提交）。
        if (timeline.isSegmentCached(segIdx) &&
            timeline.isSegmentCached(segIdx + 1)
        ) {
            lastPreloadedSegIndex = segIdx
            return
        }
        preloadAhead(segIdx)
        lastPreloadedSegIndex = segIdx
    }

    fun setPlaybackReady(ready: Boolean) {
        if (playbackReady == ready) return
        playbackReady = ready
        if (ready) {
            if (awaitingSeekReady && seekReadyAt == 0L) {
                seekReadyAt = SystemClock.elapsedRealtime() + SEEK_READY_STABILIZE_MS
            }
        } else {
            seekReadyAt = 0L
        }
    }

    fun onSeek() {
        awaitingSeekReady = true
        seekReadyAt = if (playbackReady) {
            SystemClock.elapsedRealtime() + SEEK_READY_STABILIZE_MS
        } else {
            0L
        }
        seekHardDeadlineMs = SystemClock.elapsedRealtime() + SEEK_RECOVER_HARD_TIMEOUT_MS
        hasVideoAnchor = false
        lastPreloadedSegIndex = -1
        clearMask()
    }

    fun onPositionChanged(positionMs: Long) {
        if (!enabled || !maskReady) return
        if (shouldSkipForSeek()) {
            clearMask()
            return
        }
        invalidateMaskHost()
    }

    fun setRepository(repository: DmMaskRepository) {
        this.repository = repository
    }

    fun release() {
        currentCid = 0L
        maskReady = false
        currentTimeline = null
        lastPreloadedSegIndex = -1
        jankMonitor.stop()
        clearMask()
    }

    fun dispose() {
        release()
    }

    // ---- 内部实现 ----

    private fun checkAndPreloadNext() {
        val timeline = currentTimeline ?: return
        val pts = currentVideoPtsMs()
        val segIdx = timeline.segmentIndexAt(pts)
        if (segIdx > lastPreloadedSegIndex || !timeline.isSegmentCached(segIdx)) {
            preloadAhead(segIdx)
            lastPreloadedSegIndex = segIdx
        }
    }

    private fun preloadAhead(currentSegIdx: Int) {
        val cid = currentCid
        val timeline = currentTimeline ?: return
        if (preloadingSegIndex == currentSegIdx) return
        preloadingSegIndex = currentSegIdx
        val totalSegs = timeline.totalSegments()
        // 预解析当前段 ± 2
        val range = (currentSegIdx - 1).coerceAtLeast(0)..(currentSegIdx + 2).coerceAtMost(totalSegs - 1)
        preloadScope.launch {
            try {
                for (idx in range) {
                    repository.preloadSegmentFrames(cid, idx)
                }
            } finally {
                // 释放去重锁，让下次跨段能再 launch；用 == 是为了避免覆盖更新的 segIdx。
                if (preloadingSegIndex == currentSegIdx) preloadingSegIndex = -1
            }
        }
    }

    private fun shouldSkipForSeek(): Boolean {
        if (!awaitingSeekReady) return false
        val now = SystemClock.elapsedRealtime()
        if (now >= seekHardDeadlineMs) {
            awaitingSeekReady = false
            return false
        }
        if (seekReadyAt > 0L && now >= seekReadyAt) {
            awaitingSeekReady = false
            return false
        }
        return true
    }

    private fun invalidateMaskHost() {
        maskHostProvider()?.invalidate()
    }

    private fun clearMask() {
        maskHostProvider()?.invalidate()
    }
}
