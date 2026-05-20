package com.btelly.feature.home

import android.content.Context
import android.os.SystemClock
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.log.HomeVideoCardDebugLogger
import com.btelly.core.ui.image.ImageLoader
import com.btelly.model.video.VideoModel
import com.btelly.network.api.BiliApi
import com.btelly.repository.cache.HomeCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class RecommendFeedRepository(
    context: Context
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 12
        private const val COVER_PREFETCH_COUNT = 8
    }

    private val firstPageDeferred = CompletableDeferred<NetworkPage>()

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "STARTUP T2 preloadFirstPage start")
        val preloadSize = PRELOAD_PAGE_SIZE
        val result = loadNetworkPage(page = 1, pageSize = preloadSize, freshIdx = 0)
        val page = result.getOrNull()
        if (page != null) {
            AppLog.i(TAG, "STARTUP T3 preload api done items=${page.items.size} hasMore=${page.hasMore}")
            writeCache(page.items)
            prefetchCovers(page.items)
            firstPageDeferred.complete(page)
        } else {
            firstPageDeferred.completeExceptionally(
                result.exceptionOrNull() ?: IllegalStateException("preload failed")
            )
        }
        AppLog.i(TAG, "STARTUP T3b preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    suspend fun awaitFirstPage(timeoutMs: Long): NetworkPage? {
        return try {
            withTimeout(timeoutMs) { firstPageDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "STARTUP awaitFirstPage timeout after ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "STARTUP awaitFirstPage failed: ${e.message}")
            null
        }
    }

    private fun prefetchCovers(items: List<VideoModel>) {
        if (items.isEmpty()) return
        val urls = items.asSequence()
            .take(COVER_PREFETCH_COUNT)
            .map { it.bangumi?.cover?.takeIf { c -> c.isNotBlank() } ?: it.coverUrl }
            .toList()
        ImageLoader.prefetchVideoCovers(appContext, urls)
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
        AppLog.i(TAG, "APP_STARTUP recommend cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP recommend cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${formatCacheAge(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_RECOMMEND_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): Result<NetworkPage> = runCatching {
        loadWebFeedPage(page, pageSize, freshIdx)
    }

    private suspend fun loadWebFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): NetworkPage {
        val t0 = SystemClock.elapsedRealtime()
        val rawItems = BiliApi.recommend(freshIdx, pageSize)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend_web(page=$page,freshIdx=$freshIdx) raw=${rawItems.size} api=${t1 - t0}ms")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "recommend_web(page=$page,freshIdx=$freshIdx)",
            items = rawItems
        )
        val filtered = rawItems.filter { it.isSupportedHomeVideoCard }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend_web filter=${t2 - t1}ms api=${t1 - t0}ms raw=${rawItems.size}→${filtered.size}")
        return NetworkPage(
            items = filtered,
            hasMore = rawItems.size >= pageSize
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_RECOMMEND_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_RECOMMEND_ITEMS)
    }

    private fun formatCacheAge(savedAtMs: Long): Long {
        return if (savedAtMs > 0L) {
            System.currentTimeMillis() - savedAtMs
        } else {
            -1L
        }
    }
}
