package com.btelly.feature.player

import com.btelly.model.subtitle.SubtitleInfoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePlaybackPolicyTest {

    @Test
    fun playerInfoTracksMustMatchDetailTrackIdentity() {
        val detailTracks = listOf(
            SubtitleInfoModel(id = 101L, lan = "ai-zh"),
            SubtitleInfoModel(id = 102L, lan = "ai-en")
        )
        val playerInfoTracks = listOf(
            SubtitleInfoModel(id = 999L, lan = "ai-zh", subtitleUrl = "//wrong"),
            SubtitleInfoModel(id = 102L, lan = "ai-en", subtitleUrl = "//correct")
        )

        assertEquals(
            listOf(SubtitleInfoModel(id = 102L, lan = "ai-en", subtitleUrl = "//correct")),
            trustedPlayerInfoSubtitleTracks(detailTracks, playerInfoTracks)
        )
    }

    @Test
    fun playerInfoTracksAreAllowedWhenDetailHasNoTracks() {
        val track = SubtitleInfoModel(id = 999L, lan = "ai-zh", subtitleUrl = "//subtitle")

        assertEquals(listOf(track), trustedPlayerInfoSubtitleTracks(emptyList(), listOf(track)))
    }

    @Test
    fun staleSubtitleRequestIsRejected() {
        assertTrue(isSubtitleRequestCurrent(10L, "BV1current", 10L, "BV1current"))
        assertFalse(isSubtitleRequestCurrent(10L, "BV1previous", 11L, "BV1current"))
    }
}
