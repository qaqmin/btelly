package com.btelly.feature.home

import android.content.Context
import android.os.SystemClock
import com.btelly.BTellyApplication
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.log.HomeVideoCardDebugLogger
import com.btelly.model.video.VideoModel
import com.btelly.network.api.BiliApi
import com.btelly.repository.cache.HomeCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecommendFeedRepository(
    context: Context
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 24
    }

    private val firstPageLock = Any()
    @Volatile
    private var firstPageInFlight: CompletableDeferred<NetworkPage>? = null
    @Volatile
    private var preloadedFirstPage: NetworkPage? = null

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "STARTUP T2 preloadFirstPage start")
        runCatching {
            loadSharedFirstPage(pageSize = PRELOAD_PAGE_SIZE, reason = "preload")
        }.onSuccess { page ->
            AppLog.i(TAG, "STARTUP T3 preload api done items=${page.items.size} hasMore=${page.hasMore}")
            writeCache(page.items)
        }.onFailure { throwable ->
            AppLog.w(TAG, "STARTUP preloadFirstPage failed: ${throwable.message}")
        }
        AppLog.i(TAG, "STARTUP T3b preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    suspend fun loadSharedFirstPage(
        pageSize: Int = PRELOAD_PAGE_SIZE,
        reason: String
    ): NetworkPage {
        preloadedFirstPage?.let { page ->
            AppLog.i(TAG, "STARTUP sharedFirstPage hit reason=$reason items=${page.items.size}")
            return page
        }

        var owner = false
        val deferred = synchronized(firstPageLock) {
            preloadedFirstPage?.let { page ->
                CompletableDeferred(page)
            } ?: firstPageInFlight ?: CompletableDeferred<NetworkPage>().also { created ->
                firstPageInFlight = created
                owner = true
            }
        }

        if (!owner) {
            val waitStartMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP sharedFirstPage await reason=$reason")
            return deferred.await().also { page ->
                AppLog.i(
                    TAG,
                    "STARTUP sharedFirstPage await_done reason=$reason elapsed=${SystemClock.elapsedRealtime() - waitStartMs}ms items=${page.items.size}"
                )
            }
        }

        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "STARTUP sharedFirstPage owner reason=$reason")
        return try {
            ensureDataRuntimeReady("sharedFirstPage/$reason")
            val page = loadNetworkPage(page = 1, pageSize = pageSize, freshIdx = 1, fetchRow = 1).getOrThrow()
            preloadedFirstPage = page
            deferred.complete(page)
            AppLog.i(
                TAG,
                "STARTUP sharedFirstPage owner_done reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms items=${page.items.size}"
            )
            page
        } catch (throwable: Throwable) {
            deferred.completeExceptionally(throwable)
            synchronized(firstPageLock) {
                if (firstPageInFlight === deferred) {
                    firstPageInFlight = null
                }
            }
            throw throwable
        }
    }

    private suspend fun ensureDataRuntimeReady(reason: String) {
        val app = appContext as? BTellyApplication ?: return
        withContext(Dispatchers.IO) {
            app.ensureDataRuntimeReady(reason)
        }
    }

    data class CachedFeed(
        val items: List<VideoModel>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    data class NetworkPage(
        val items: List<VideoModel>,
        val hasMore: Boolean,
        val rawCount: Int,
        val requestFreshIdx: Int,
        val requestFetchRow: Int,
        val source: String
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
        freshIdx: Int,
        fetchRow: Int
    ): Result<NetworkPage> = runCatching {
        loadWebFeedPage(page, pageSize, freshIdx, fetchRow)
    }

    /**
     * 首页刷新成功后同步内存预加载缓存。切 tab 回来会重建 Fragment 并走
     * loadSharedFirstPage 优先吃 preloadedFirstPage；若不更新，用户刚刷出来的
     * 新内容会被启动时的旧首页顶掉（重新登录后复现：刷新→切走→切回变旧数据）。
     */
    fun updatePreloadedFirstPage(page: NetworkPage) {
        if (page.items.isEmpty()) return
        AppLog.i(TAG, "updatePreloadedFirstPage items=${page.items.size} source=${page.source}")
        preloadedFirstPage = page
    }

    private suspend fun loadWebFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int,
        fetchRow: Int
    ): NetworkPage {
        val t0 = SystemClock.elapsedRealtime()
        val source = "recommend_v8(page=$page,freshIdx=$freshIdx,fetchRow=$fetchRow,ps=$pageSize)"
        val recommendResult = runCatching {
            BiliApi.recommendV8(freshIdx = freshIdx, ps = pageSize, fetchRow = fetchRow)
        }.getOrElse { throwable ->
            AppLog.w(TAG, "$source failed: ${throwable.message}; fallback V1")
            val fallbackItems = BiliApi.recommend(freshIdx, pageSize)
            BiliApi.RecommendPageResult(
                items = fallbackItems,
                rawCount = fallbackItems.size,
                source = "V1_FALLBACK"
            )
        }.let { result ->
            if (result.items.isNotEmpty()) {
                result
            } else {
                AppLog.w(TAG, "$source empty; fallback V1")
                val fallbackItems = BiliApi.recommend(freshIdx, pageSize)
                BiliApi.RecommendPageResult(
                    items = fallbackItems,
                    rawCount = fallbackItems.size,
                    source = "V1_FALLBACK_EMPTY"
                )
            }
        }
        val rawItems = recommendResult.items
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "$source apiSource=${recommendResult.source} raw=${recommendResult.rawCount} parsed=${rawItems.size} api=${t1 - t0}ms")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "$source apiSource=${recommendResult.source}",
            items = rawItems
        )
        val filtered = rawItems.filter { it.isSupportedHomeVideoCard }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "$source filter=${t2 - t1}ms api=${t1 - t0}ms raw=${recommendResult.rawCount} parsed=${rawItems.size}->${filtered.size}")
        return NetworkPage(
            items = filtered,
            hasMore = recommendResult.rawCount >= pageSize,
            rawCount = recommendResult.rawCount,
            requestFreshIdx = freshIdx,
            requestFetchRow = fetchRow,
            source = recommendResult.source
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
