package com.btelly.feature.player.view

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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

    var playerPositionProvider: (() -> Long)? = null

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setEnabled(enabled: Boolean) {
        AppLog.d(TAG, "setEnabled: $enabled, maskReady=$maskReady")
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            clearMask()
        } else if (maskReady) {
            invalidateMaskHost()
        }
    }

    suspend fun loadMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        AppLog.d(TAG, "loadMask: cid=$cid, fps=$fps, enabled=$enabled")
        currentCid = cid
        maskReady = false
        lastPreloadedSegIndex = -1
        clearMask()

        val data = repository.downloadAndParse(maskUrl, cid, fps)
        maskReady = data != null
        if (!maskReady) {
            AppLog.d(TAG, "Mask load failed for cid=$cid")
        } else {
            val timeline = repository.getTimeline(cid)
            currentTimeline = timeline
            AppLog.d(TAG, "Mask loaded OK: segments=${data?.rawSegments?.size}, timeline=${timeline != null}")
            maskHostProvider()?.let { host ->
                host.timeline = timeline
            }
            if (enabled) invalidateMaskHost()
            // 后台预解析前几段
            preloadAhead(0)
        }
        return maskReady
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

    fun currentVideoPtsMs(): Long {
        return if (hasVideoAnchor) {
            val deltaNs = ((System.nanoTime() - anchorReleaseTimeNs) * playbackSpeed).toLong()
            (anchorPresentationTimeUs * 1000L + deltaNs) / 1_000_000L
        } else {
            playerPositionProvider?.invoke() ?: 0L
        }
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
        anchorPresentationTimeUs = presentationTimeUs
        anchorReleaseTimeNs = releaseTimeNs
        hasVideoAnchor = true
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
        val totalSegs = timeline.totalSegments()
        // 预解析当前段 ± 2
        val range = (currentSegIdx - 1).coerceAtLeast(0)..(currentSegIdx + 2).coerceAtMost(totalSegs - 1)
        preloadScope.launch {
            for (idx in range) {
                repository.preloadSegmentFrames(cid, idx)
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
