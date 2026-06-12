package com.btelly.feature.player

import com.btelly.feature.player.settings.AfterPlayMode
import com.btelly.model.video.VideoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionCoordinatorPlayQueueTest {

    @Test
    fun playQueueBuildsContinuationForListItemWithoutCid() {
        val coordinator = PlayerSessionCoordinator()
        val next = VideoModel(aid = 2L, bvid = "BV2", title = "next", cid = 0L)
        coordinator.updateCurrentVideo(VideoModel(aid = 1L, bvid = "BV1", title = "current", cid = 101L))
        coordinator.replacePlayQueue(listOf(next))

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.PLAY_QUEUE,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = false,
            nextEpisode = null
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.PlayIntent)
        val intent = (plan as PlayerSessionCoordinator.ContinuationPlan.PlayIntent).intent
        assertEquals(ContinuationPlaybackIntent.Kind.PLAY_QUEUE, intent.kind)
        assertEquals(2L, intent.target.aid)
        assertEquals("BV2", intent.target.bvid)
        assertEquals(0L, intent.target.cid)
    }
}
