package com.btelly.feature.player.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.btelly.BTellyApplication
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.koin.mp.KoinPlatform

@UnstableApi
object PlayerMediaCache {

    private const val TAG = "PlayerMediaCache"
    private const val CACHE_DIR_NAME = "player_media_cache"
    private const val CACHE_FRAGMENT_SIZE_BYTES = 2L * 1024L * 1024L
    private const val RESOURCE_PATH_MARKER = "/v1/resource/"
    private const val CACHE_HIT_LOG_INTERVAL_MS = 2_000L

    // 与 FileCacheManager 共用同一份"缓存限制"设置键，保证两处口径一致。
    private const val KEY_CACHE_LIMIT = "cache_limit"
    private const val CACHE_SIZE_200_MB: Long = 200L * 1024L * 1024L
    private const val CACHE_SIZE_500_MB: Long = 500L * 1024L * 1024L
    private const val CACHE_SIZE_1_GB: Long = 1024L * 1024L * 1024L
    // "不限制"时取一个安全大值，避免 media3 用 Long.MAX_VALUE 触发潜在溢出/异常。
    private const val CACHE_SIZE_UNLIMITED_FALLBACK: Long = 2L * 1024L * 1024L * 1024L

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var simpleCache: SimpleCache? = null

    private val lastCacheHitLogMs = AtomicLong(0L)

    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.key ?: buildStableCacheKey(dataSpec.uri)
    }

    private val cacheEventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            val now = System.currentTimeMillis()
            val last = lastCacheHitLogMs.get()
            if (now - last >= CACHE_HIT_LOG_INTERVAL_MS && lastCacheHitLogMs.compareAndSet(last, now)) {
                AppLog.i(TAG, "cache_hit bytes=$cachedBytesRead cacheSize=$cacheSizeBytes")
            }
        }

        override fun onCacheIgnored(reason: Int) {
            val reasonName = when (reason) {
                CacheDataSource.CACHE_IGNORED_REASON_ERROR -> "error"
                CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH -> "unset_length"
                else -> "unknown($reason)"
            }
            AppLog.w(TAG, "cache ignored: reason=$reasonName")
        }
    }

    fun buildDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        val appContext = context.applicationContext
        val cache = getOrCreateCache(appContext)
        val cacheWriteFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(CACHE_FRAGMENT_SIZE_BYTES)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeyFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(cacheWriteFactory)
            .setEventListener(cacheEventListener)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildDefaultDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context.applicationContext,
            buildDataSourceFactory(context, upstreamFactory)
        )
    }

    @Synchronized
    fun clear(context: Context) {
        simpleCache?.release()
        simpleCache = null
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME).deleteRecursively()
    }

    /**
     * 改完"缓存限制"后调用：仅释放当前 SimpleCache 对象（不删磁盘文件，避免影响
     * 正在播放的视频），下次播放会按新上限自动重建。
     */
    @Synchronized
    fun reset(context: Context) {
        simpleCache?.release()
        simpleCache = null
    }

    /**
     * 读取与 FileCacheManager 一致的"缓存限制"设置，换算成播放器缓存上限字节数。
     * 默认（未设置或取值异常）按 200MB 处理，对低存储电视更友好。
     */
    private fun resolveMaxCacheBytes(): Long {
        val raw = appSettings.getCachedString(KEY_CACHE_LIMIT)?.trim()
        return when (raw) {
            "不限制" -> CACHE_SIZE_UNLIMITED_FALLBACK
            "200 MB" -> CACHE_SIZE_200_MB
            "500 MB" -> CACHE_SIZE_500_MB
            "1 GB" -> CACHE_SIZE_1_GB
            else -> CACHE_SIZE_200_MB
        }
    }

    private fun buildStableCacheKey(uri: Uri): String {
        val rawPath = uri.encodedPath
            ?: uri.path
            ?: ""
        val stablePath = rawPath
            .substringAfter(RESOURCE_PATH_MARKER, rawPath)
            .trim('/')
            .ifBlank {
                uri.lastPathSegment?.trim('/')?.takeIf { it.isNotBlank() }
                    ?: uri.schemeSpecificPart.orEmpty()
            }
        return "media:$stablePath"
    }

    @Synchronized
    private fun getOrCreateCache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        val cacheDirectory = File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        return SimpleCache(
            cacheDirectory,
            LeastRecentlyUsedCacheEvictor(resolveMaxCacheBytes()),
            StandaloneDatabaseProvider(context)
        ).also { simpleCache = it }
    }
}
