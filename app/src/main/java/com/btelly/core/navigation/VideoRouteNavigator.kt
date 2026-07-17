package com.btelly.core.navigation

import android.content.Context
import android.content.ContextWrapper
import com.btelly.model.video.HistoryVideoModel
import com.btelly.model.video.VideoModel
import com.btelly.ui.activity.LivePlayerActivity
import com.btelly.ui.activity.MainActivity
import com.btelly.ui.activity.PlayerActivity
import com.btelly.feature.detail.VideoDetailFragment
import com.btelly.feature.player.PlaybackStartupTrace
import com.btelly.feature.player.settings.PlayerSettingsStore
import com.btelly.core.common.ext.isOpenDetailFirstEnabled
import com.btelly.core.common.ext.toast
import com.btelly.core.common.log.AppLog

object VideoRouteNavigator {

    private const val TAG = "VideoRouteNav"

    fun openVideo(
        context: Context,
        video: VideoModel,
        playQueue: List<VideoModel> = emptyList(),
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        forcePlayer: Boolean = false
    ) {
        val traceId = PlaybackStartupTrace.newTraceId()
        val traceStartElapsedMs = PlaybackStartupTrace.nowMs()
        PlaybackStartupTrace.log(
            traceId = traceId,
            startElapsedMs = traceStartElapsedMs,
            step = "card_click",
            message = "aid=${video.aid} bvid=${video.bvid} cid=${video.cid} title=${video.title.take(32)}"
        )
        AppLog.d(TAG, "openVideo: bvid=${video.bvid}, aid=${video.aid}, title=${video.title}, forcePlayer=$forcePlayer")
        // 青少年模式：休息期间拦截视频入口
        com.btelly.core.common.content.TeenModeTimer.consumeBlockReason()?.let {
            context.toast(it)
            return
        }
        if (!video.hasPlaybackIdentity) {
            AppLog.w(TAG, "openVideo blocked: missing playback identity, title=${video.title}, cid=${video.cid}")
            context.toast("当前卡片数据不完整，暂时无法播放")
            return
        }
        if (!forcePlayer && shouldOpenVideoDetailFirst(video)) {
            val hostActivity = findMainActivityHost(context)
            AppLog.d(TAG, "openVideo: detailFirst=true, host=$hostActivity")
            if (hostActivity != null) {
                hostActivity.openInHostContainer(VideoDetailFragment.newInstance(video, playQueue))
                return
            }
        }
        AppLog.d(TAG, "openVideo: starting PlayerActivity")
        val shouldUseHistoryProgress = seekPositionMs <= 0L &&
            PlayerSettingsStore.load(context).resumePlayback
        PlayerActivity.start(
            context = context,
            video = video,
            seekPositionMs = seekPositionMs.takeIf { it > 0L }
                ?: if (shouldUseHistoryProgress) video.historyProgress * 1000L else 0L,
            playQueue = playQueue,
            startEpisodeIndex = startEpisodeIndex,
            startupTraceId = traceId,
            startupTraceStartElapsedMs = traceStartElapsedMs
        )
    }

    fun openHistory(
        context: Context,
        historyVideo: HistoryVideoModel,
        playQueue: List<VideoModel> = emptyList(),
        forcePlayer: Boolean = false
    ) {
        resolveLiveRoomId(historyVideo)?.let { roomId ->
            LivePlayerActivity.start(context, roomId)
            return
        }
        openVideo(
            context = context,
            video = historyVideo.toVideoModel(),
            playQueue = playQueue,
            forcePlayer = forcePlayer
        )
    }

    private fun shouldOpenVideoDetailFirst(video: VideoModel): Boolean {
        if (!isOpenDetailFirstEnabled()) {
            return false
        }
        if (video.isLive || video.roomId > 0L || video.historyBusiness == "live") {
            return false
        }
        return video.aid > 0L || video.bvid.isNotBlank()
    }

    private fun resolveLiveRoomId(historyVideo: HistoryVideoModel): Long? {
        val history = historyVideo.history ?: return null
        if (history.business != "live") {
            return null
        }
        return history.oid.takeIf { it > 0L }
    }

    private fun findMainActivityHost(context: Context): MainActivity? {
        var current: Context? = context
        while (current != null) {
            when (current) {
                is MainActivity -> {
                    if (!current.isFinishing && !current.isDestroyed) {
                        return current
                    }
                    return null
                }
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}
