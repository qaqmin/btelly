package com.btelly.feature.home

import com.btelly.model.video.VideoModel
import com.btelly.network.api.BiliApi
import com.btelly.repository.cache.HomeCacheStore

/**
 * 全站排行榜数据源。
 *
 * 为什么单独一个 repository 而不是复用 [HotFeedRepository]：
 * 热门是翻页流（`x/web-interface/popular`），排行榜是一次性全量（`ranking/v2` 一次
 * 返回整个榜单）。两者的分页语义、缓存大小都不同，混在一起要加分支判断。
 */
class RankFeedRepository {

    companion object {
        private const val TAG = "RankFeedRepository"
        private const val CACHE_KEY = "rankCacheList"
        /** 全站榜单一般 100 条，缓存不必比它多 */
        private const val MAX_CACHED_ITEMS = 100
    }

    data class RankResult(
        val items: List<VideoModel>
    )

    suspend fun loadRanking(pageSize: Int = MAX_CACHED_ITEMS): Result<RankResult> = runCatching {
        val rawItems = BiliApi.ranking(rid = 0, type = "all")
        RankResult(
            items = rawItems
                .filter { it.isSupportedHomeVideoCard }
                .take(pageSize)
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_ITEMS)
    }
}
