package com.btelly.feature.player

import com.btelly.model.video.VideoModel
import com.btelly.model.video.VideoPvModel
import com.btelly.model.video.detail.UgcEpisode
import com.btelly.model.video.detail.UgcSeason
import com.btelly.model.video.detail.UgcSection
import com.btelly.model.video.detail.VideoDetailModel
import com.btelly.model.video.detail.VideoView
import com.btelly.network.api.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerEpisodeCatalogBuilderTest {

    private val builder = VideoPlayerEpisodeCatalogBuilder(unusedApiService())

    @Test
    fun ugcSeasonMergesMultiplePagesFromSameArchive() = runBlocking {
        val detail = VideoDetailModel(
            view = VideoView(
                aid = 1L,
                bvid = "BVcurrent",
                pic = "cover",
                ugcSeason = UgcSeason(
                    sections = listOf(
                        UgcSection(
                            episodes = listOf(
                                ugcEpisode(
                                    aid = 100L,
                                    bvid = "BV100",
                                    cid = 1002L,
                                    page = 2,
                                    part = "P2 title",
                                    archiveTitle = "Archive A"
                                ),
                                ugcEpisode(
                                    aid = 100L,
                                    bvid = "BV100",
                                    cid = 1001L,
                                    page = 1,
                                    part = "P1 title",
                                    archiveTitle = "Archive A"
                                ),
                                ugcEpisode(
                                    aid = 200L,
                                    bvid = "BV200",
                                    cid = 2001L,
                                    page = 1,
                                    part = "Only page",
                                    archiveTitle = "Archive B"
                                )
                            )
                        )
                    )
                )
            )
        )

        val episodes = builder.buildUgcEpisodes(detail)

        assertEquals(2, episodes.size)
        assertEquals(1001L, episodes[0].cid)
        assertEquals("Archive A", episodes[0].title)
        assertEquals("Archive A", episodes[0].panelTitle)
        assertEquals(2001L, episodes[1].cid)
        assertEquals("Archive B", episodes[1].title)
    }

    private fun ugcEpisode(
        aid: Long,
        bvid: String,
        cid: Long,
        page: Int,
        part: String,
        archiveTitle: String
    ): UgcEpisode {
        return UgcEpisode(
            aid = aid,
            bvid = bvid,
            cid = cid,
            title = part,
            arc = VideoModel(
                aid = aid,
                bvid = bvid,
                cid = cid,
                title = archiveTitle,
                pic = "cover-$aid"
            ),
            pageInfo = VideoPvModel(
                cid = cid,
                page = page,
                part = part
            )
        )
    }

    private fun unusedApiService(): ApiService {
        return Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)
        ) { _, method, _ ->
            error("Unexpected ApiService call: ${method.name}")
        } as ApiService
    }
}
