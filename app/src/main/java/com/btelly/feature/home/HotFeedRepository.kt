package com.btelly.feature.home

import android.os.SystemClock
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.log.HomeVideoCardDebugLogger
import com.btelly.model.video.VideoModel
import com.btelly.network.api.BiliApi
import com.btelly.repository.cache.HomeCacheStore

class HotFeedRepository {

    companion object {
        private const val TAG = "HotFeedRepository"
        private const val CACHE_KEY = "hotCacheList"
        private const val MAX_CACHED_HOT_ITEMS = 24
    }

    data class CachedFeed(
        val items: List<VideoModel>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    data class NetworkPage(
        val items: List<VideoModel>,
        val hasMore: Boolean
    )

    suspend fun readCachedFeed(): CachedFeed {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP hot cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP hot cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${HomeCacheStore.cacheAgeMs(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_HOT_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(page: Int, pageSize: Int): Result<NetworkPage> = runCatching {
        val rawItems = BiliApi.hotList(page, pageSize)
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "hot(page=$page,pageSize=$pageSize)",
            items = rawItems
        )
        NetworkPage(
            items = rawItems.filter { it.isSupportedHomeVideoCard },
            hasMore = rawItems.size >= pageSize
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_HOT_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_HOT_ITEMS)
    }

}
