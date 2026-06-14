package com.btelly.feature.player.douyin

import androidx.lifecycle.LifecycleCoroutineScope
import com.btelly.core.common.log.AppLog
import com.btelly.feature.player.PlayerSessionCoordinator
import com.btelly.feature.player.PlaybackPreloadTarget
import com.btelly.feature.player.VideoPlayerViewModel
import com.btelly.feature.player.view.DouyinModePreview
import com.btelly.feature.player.view.MyPlayerView
import com.btelly.model.video.VideoModel
import kotlinx.coroutines.launch

private const val TAG = "PlayerActivity"

/**
 * 抖音模式（竖屏连播）的播放编排协调器。
 *
 * 从 PlayerActivity 拆分而来，负责连播手势响应、上下切换、转场动画、
 * 队列预热与预加载、Header 渲染。队列数据管理由 [DouyinModeManager] 负责，
 * 本类只做编排：取下一个 → 触发播放 → 预加载下一条 → 转场动画。
 */
internal class DouyinPlaybackCoordinator(
    private val douyinModeManager: DouyinModeManager,
    private val sessionCoordinator: PlayerSessionCoordinator,
    private val playerView: MyPlayerView,
    private val viewModel: VideoPlayerViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val host: Host
) {

    /** 由 PlayerActivity 实现，提供 Coordinator 无法直接访问的宿主能力。 */
    interface Host {
        fun toast(message: String)
        fun isVideoBlockedByMinorProtection(video: VideoModel): Boolean
    }

    private var sourceAid: Long = 0L
    private var sourceKey: String = ""
    private var initializing: Boolean = false
    private var transitionRunning: Boolean = false
    private var internalNavigationKey: String = ""
    private var pendingNextAfterInit: Boolean = false

    fun isModeActive(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        return douyinModeManager.isApplicable(currentVideo)
    }

    fun handleNext(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        if (!douyinModeManager.hasList() && !initializing) {
            pendingNextAfterInit = true
            startQueueInitialization(currentVideo)
            return true
        }
        if (initializing) {
            pendingNextAfterInit = true
            return true
        }

        val next = douyinModeManager.next()
        if (next == null) {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(1)
            host.toast("该视频无推荐")
            return true
        }
        playVideo(next, direction = 1)
        return true
    }

    fun handlePrevious(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        if (!douyinModeManager.hasList()) {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(-1)
            return true
        }
        val prev = douyinModeManager.previous()
        if (prev != null) {
            playVideo(prev, direction = -1)
        } else {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(-1)
        }
        return true
    }

    fun peekNextPreview(): DouyinModePreview? {
        return douyinModeManager.peekNext()?.toDouyinPreview()
            ?: if (!douyinModeManager.hasList()) {
                DouyinModePreview(title = "加载推荐中...", coverUrl = "")
            } else {
                null
            }
    }

    fun peekPreviousPreview(): DouyinModePreview? =
        douyinModeManager.peekPrevious()?.toDouyinPreview()

    private fun playVideo(video: VideoModel?, direction: Int = 0) {
        if (video == null) return
        if (host.isVideoBlockedByMinorProtection(video)) {
            host.toast("青少年模式已拦截该视频")
            return
        }
        playerView.hideController()
        internalNavigationKey = video.douyinIdentityKey()
        sessionCoordinator.updateCurrentVideo(video)
        renderTargetHeader(video)

        val playTarget = {
            viewModel.playRelatedVideo(video, preferLastPlayTime = false)
        }

        if (direction != 0 && playerView.consumeDouyinGestureTransition()) {
            playTarget()
        } else if (direction != 0 && !transitionRunning) {
            transitionRunning = true
            val transitionStarted = playerView.startDouyinPageTransition(
                direction,
                targetPreview = video.toDouyinPreview()
            ) {
                playTarget()
                transitionRunning = false
            }
            if (!transitionStarted) {
                playTarget()
                transitionRunning = false
            }
        } else {
            playTarget()
        }

        lifecycleScope.launch {
            douyinModeManager.appendMore()
        }
    }

    fun schedulePreloadAfterPlaybackRequest(playbackRequest: VideoPlayerViewModel.PlaybackRequest) {
        if (!douyinModeManager.hasList()) return
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) return
        val requestMatchesCurrent = when {
            playbackRequest.cid > 0L && currentVideo.cid > 0L -> playbackRequest.cid == currentVideo.cid
            playbackRequest.aid != null && currentVideo.aid > 0L -> playbackRequest.aid == currentVideo.aid
            !playbackRequest.bvid.isNullOrBlank() && currentVideo.bvid.isNotBlank() -> playbackRequest.bvid == currentVideo.bvid
            else -> false
        }
        if (!requestMatchesCurrent) return
        lifecycleScope.launch {
            douyinModeManager.appendMore()
            preloadNext()
        }
    }

    private fun preloadNext() {
        val nextVideo = douyinModeManager.peekNext() ?: return
        val aid = nextVideo.aid.takeIf { it > 0 }
        val bvid = nextVideo.bvid.takeIf { it.isNotBlank() }
        val cid = nextVideo.cid
        if (cid <= 0L) return
        viewModel.preloadPlayback(
            PlaybackPreloadTarget(
                aid = aid,
                bvid = bvid,
                cid = cid,
                source = PlaybackPreloadTarget.Source.DOUYIN_MODE
            )
        )
    }

    private fun renderTargetHeader(video: VideoModel) {
        playerView.setTitle(video.title)
        playerView.setSubTitle(video.authorName.takeIf { it.isNotBlank() }.orEmpty())
    }

    fun resetIfNeeded() {
        val currentVideo = sessionCoordinator.getCurrentVideo()
        val currentAid = currentVideo?.aid ?: 0L
        val currentKey = currentVideo?.douyinIdentityKey().orEmpty()
        if (currentKey.isNotBlank() && currentKey == internalNavigationKey) {
            sourceAid = currentAid
            sourceKey = currentKey
            internalNavigationKey = ""
            return
        }
        if (currentKey.isNotBlank() && currentKey != sourceKey) {
            AppLog.i(TAG, "DouyinQueue reset_external current=${currentVideo?.douyinDebugId().orEmpty()} sourceKey=$sourceKey internalKey=$internalNavigationKey")
            sourceAid = currentAid
            sourceKey = currentKey
            douyinModeManager.reset()
            initializing = false
            internalNavigationKey = ""
            pendingNextAfterInit = false
        }
    }

    fun ensureQueueStarted() {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) {
            if (douyinModeManager.hasList()) {
                AppLog.i(TAG, "DouyinQueue ensure_reset_unplayable current=${currentVideo.douyinDebugId()}")
                douyinModeManager.reset()
            }
            initializing = false
            return
        }
        if (douyinModeManager.hasList() || initializing) return

        startQueueInitialization(currentVideo)
    }

    private fun startQueueInitialization(currentVideo: VideoModel) {
        if (initializing) return
        initializing = true
        lifecycleScope.launch {
            douyinModeManager.initialize(currentVideo)
            initializing = false
            val latestVideo = sessionCoordinator.getCurrentVideo()
            if (!currentVideo.isSameDouyinVideo(latestVideo)) {
                AppLog.i(TAG, "DouyinQueue ensure_stale initialized=${currentVideo.douyinDebugId()} latest=${latestVideo?.douyinDebugId().orEmpty()}")
                pendingNextAfterInit = false
                return@launch
            }
            if (!douyinModeManager.hasList()) {
                if (pendingNextAfterInit) {
                    playerView.cancelDouyinPageTransition()
                    playerView.showDouyinBoundaryBounce(1)
                    host.toast("该视频无推荐")
                }
                pendingNextAfterInit = false
                return@launch
            }

            if (pendingNextAfterInit) {
                pendingNextAfterInit = false
                val next = douyinModeManager.next()
                if (next == null) {
                    playerView.cancelDouyinPageTransition()
                    playerView.showDouyinBoundaryBounce(1)
                    host.toast("该视频无推荐")
                } else {
                    playVideo(next, direction = 1)
                }
            } else {
                preloadNext()
            }
        }
    }
}

private fun VideoModel.toDouyinPreview(): DouyinModePreview {
    return DouyinModePreview(
        title = title,
        coverUrl = effectiveCoverUrl
    )
}

private fun VideoModel.douyinDebugId(): String {
    return "aid=$aid,bvid=$bvid,title=${title.take(18)}"
}

private fun VideoModel.douyinIdentityKey(): String {
    val epKey = playbackEpId.takeIf { it > 0L }?.let { "ep:$it" }
    val bvidKey = bvid.takeIf { it.isNotBlank() }?.let { "bvid:$it" }
    val aidKey = aid.takeIf { it > 0L }?.let { "aid:$it" }
    val cidKey = cid.takeIf { it > 0L }?.let { "cid:$it" }
    return listOfNotNull(epKey, bvidKey, aidKey, cidKey).joinToString("|")
}

private fun VideoModel.isSameDouyinVideo(other: VideoModel?): Boolean {
    if (other == null) return false
    return when {
        aid > 0L && other.aid > 0L -> aid == other.aid
        bvid.isNotBlank() && other.bvid.isNotBlank() -> bvid == other.bvid
        else -> false
    }
}
