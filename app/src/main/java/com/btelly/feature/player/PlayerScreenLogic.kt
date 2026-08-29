package com.btelly.feature.player

import android.view.View
import com.btelly.core.common.format.MediaFormatUtils
import com.btelly.core.common.format.NumberUtils
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.time.TimeUtils
import com.btelly.event.AppEventHub
import com.btelly.model.video.detail.VideoDetailModel
import com.btelly.model.video.detail.VideoView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * PlayerActivity 与 VideoPlayerFragment 逐字相同的播放器界面逻辑，单一来源。
 * 只收纯逻辑/参数传递；视图持有仍留在各自的宿主里。
 */
internal object PlayerScreenLogic {

    fun buildDebugInfo(p: ExoPlayer): String {
        val sb = StringBuilder()
        val videoFormat = p.videoFormat
        if (videoFormat != null) {
            val w = videoFormat.width
            val h = videoFormat.height
            sb.appendLine("分辨率: ${w}x${h}${MediaFormatUtils.formatAspectRatio(w, h)}")
            val codec = MediaFormatUtils.formatCodecName(videoFormat.sampleMimeType)
            val bitrate = if (videoFormat.bitrate > 0) " ${videoFormat.bitrate / 1000}kbps" else ""
            sb.appendLine("视频: $codec$bitrate")
        }
        val audioFormat = p.audioFormat
        if (audioFormat != null) {
            val codec = MediaFormatUtils.formatCodecName(audioFormat.sampleMimeType)
            val sr = if (audioFormat.sampleRate > 0) " ${audioFormat.sampleRate}Hz" else ""
            sb.appendLine("音频: $codec$sr")
        }
        if (p.duration > 0) {
            val pos = NumberUtils.formatTimeMs(p.currentPosition)
            val dur = NumberUtils.formatTimeMs(p.duration)
            val speed = p.playbackParameters.speed
            sb.appendLine("进度: $pos / $dur (${speed}x)")
        }
        val bufferedAhead = p.bufferedPosition - p.currentPosition
        if (bufferedAhead > 0) {
            sb.appendLine("缓冲: ${"%.1f".format(bufferedAhead / 1000.0)}s")
        }
        val stateLabel = when (p.playbackState) {
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> if (p.playWhenReady) "播放中" else "暂停"
            Player.STATE_ENDED -> "已结束"
            else -> ""
        }
        if (stateLabel.isNotEmpty()) {
            sb.append("状态: $stateLabel")
        }
        return sb.toString().trimEnd()
    }

    /** 调试浮层文案；返回 null 表示应隐藏。 */
    fun debugOverlayText(
        showDebugInfo: Boolean,
        errorMessage: String?,
        player: Player?,
        loadingText: String
    ): String? {
        if (!showDebugInfo) return null
        if (!errorMessage.isNullOrBlank()) return errorMessage
        if (player == null || player.playbackState == Player.STATE_IDLE) return loadingText
        return (player as? ExoPlayer)?.let(::buildDebugInfo) ?: loadingText
    }

    fun postPlaybackProgressEvent(
        appEventHub: AppEventHub,
        latestView: VideoView?,
        sessionCoordinator: PlayerSessionCoordinator,
        positionMs: Long
    ) {
        val info = latestView ?: return
        val episodes = sessionCoordinator.getEpisodes()
        val selectedIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isNotEmpty() && selectedIndex in episodes.indices) {
            val episode = episodes[selectedIndex]
            if (episode.epId > 0L) {
                appEventHub.dispatch(
                    AppEventHub.Event.EpisodePlaybackProgressUpdated(
                        episodeId = episode.epId,
                        progressMs = positionMs.coerceAtLeast(0L).plus(1L),
                        episodeIndex = episode.title
                    )
                )
                return
            }
        }
        val progressMs = positionMs.coerceAtLeast(0L).plus(1L)
        appEventHub.dispatch(
            AppEventHub.Event.PlaybackProgressUpdated(
                aid = info.aid,
                cid = info.cid,
                progressMs = progressMs
            )
        )
    }

    fun resolvePlaybackStartSeekPosition(
        playbackRequest: VideoPlayerViewModel.PlaybackRequest,
        currentPlayer: Player,
        tag: String
    ): Long {
        val requestedSeekMs = playbackRequest.seekPositionMs.coerceAtLeast(0L)
        if (!playbackRequest.reuseSameSource || requestedSeekMs <= 0L) {
            return requestedSeekMs
        }
        val durationMs = currentPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET }
            ?: return requestedSeekMs
        val resolution = PlaybackStartSeekResolver.resolve(
            requestedSeekMs = requestedSeekMs,
            durationMs = durationMs,
            reuseSameSource = true
        )
        if (resolution.nearEndReset) {
            AppLog.w(
                tag,
                "warm_reuse_seek_clamped reason=near_end requested=$requestedSeekMs duration=$durationMs"
            )
        }
        return resolution.positionMs
    }

    fun buildHeaderTitle(
        videoTitle: String,
        selectedEpisode: VideoPlayerViewModel.PlayableEpisode?
    ): String {
        val episodeTitle = selectedEpisode?.title?.trim().orEmpty()
        if (episodeTitle.isBlank() || episodeTitle == videoTitle) {
            return videoTitle
        }
        return "$episodeTitle ｜ $videoTitle"
    }

    fun buildHeaderMetaParts(video: VideoView): List<String> {
        return buildList {
            video.owner?.name?.takeIf { it.isNotBlank() }?.let(::add)
            video.stat?.view?.takeIf { it > 0 }?.let { add("${NumberUtils.formatCount(it)}播放") }
            if (video.pubDate > 0) {
                add(TimeUtils.formatTime(video.pubDate))
            }
        }
    }

    /** 返回 (hasOwner, hasVideoIdentity)，用于头像/操作按钮显隐。 */
    fun primaryActionFlags(view: VideoView?): Pair<Boolean, Boolean> {
        val hasOwner = view?.owner?.mid?.let { it > 0L } == true
        val hasVideoIdentity = (view?.aid ?: 0L) > 0L || !view?.bvid.isNullOrBlank()
        return hasOwner to hasVideoIdentity
    }

    fun syncChromeStateToCoordinator(
        coordinator: PlaybackUiCoordinator,
        showBottomProgressBar: Boolean,
        visibility: Int
    ) {
        coordinator.withState { coord ->
            coord.chromeState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.ChromeState.Full
                View.GONE -> PlaybackUiCoordinator.ChromeState.Hidden
                else -> coord.chromeState
            }
            coord.bottomOccupant = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.BottomOccupant.FullChrome
                View.GONE -> if (showBottomProgressBar) PlaybackUiCoordinator.BottomOccupant.SlimTimeline else PlaybackUiCoordinator.BottomOccupant.None
                else -> coord.bottomOccupant
            }
            coord.hudState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.HudState.Chrome
                View.GONE -> PlaybackUiCoordinator.HudState.Ambient
                else -> coord.hudState
            }
        }
    }

    fun shouldShowSlimTimeline(
        showBottomProgressBar: Boolean,
        controllerVisibility: Int,
        coordinator: PlaybackUiCoordinator
    ): Boolean {
        return showBottomProgressBar &&
                controllerVisibility == View.GONE &&
                coordinator.bottomOccupant == PlaybackUiCoordinator.BottomOccupant.SlimTimeline &&
                coordinator.seekState == PlaybackUiCoordinator.SeekState.None &&
                coordinator.panelState == PlaybackUiCoordinator.PanelState.None
    }

    fun shouldRefreshAmbientChrome(
        showBottomProgressBar: Boolean?,
        controllerVisibility: Int,
        coordinator: PlaybackUiCoordinator
    ): Boolean {
        if (showBottomProgressBar == null || controllerVisibility != View.GONE) {
            return false
        }
        return coordinator.seekState == PlaybackUiCoordinator.SeekState.None &&
                coordinator.panelState == PlaybackUiCoordinator.PanelState.None
    }
}
