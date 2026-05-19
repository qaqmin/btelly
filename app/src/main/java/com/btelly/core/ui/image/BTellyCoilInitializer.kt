package com.btelly.core.ui.image

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import com.btelly.core.common.log.AppLog
import com.btelly.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局 Coil [ImageLoader] 工厂。
 *
 * 图片域单独走一个精简的 OkHttp client：
 * - 自带独立 [Dispatcher]，`maxRequestsPerHost=16`，避免首屏 12 张图打到 i0.hdslb.com 排队等 5 个并发；
 * - 自带独立 [ConnectionPool]，16 路复用 + HTTP/2 multiplexing，TLS session 单独累积；
 * - 不挂任何业务 interceptor（HeaderInterceptor / DeflateInterceptor / TvLoginInterceptor），
 *   也不挂 OkHttp [okhttp3.Cache]：Coil 自己已经有 512MB disk cache，不需要再走 HTTP 协商缓存；
 * - Referer / UA 等 header 仍由 [ImageLoader] 通过 Coil 的 `httpHeaders` 注入，避免误伤 API 链路。
 *
 * 启动后还会立刻在后台预热 i0/i1/i2.hdslb.com 的 DNS + TCP + TLS：首屏 RecyclerView 真正 bind
 * 时连接已经在池子里，免去首张图 200~500ms 的握手开销。
 */
object BTellyCoilInitializer {

    private const val TAG = "CoilInit"
    private val initialized = AtomicBoolean(false)
    private val preheatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val imageOkHttpClient: OkHttpClient by lazy { buildImageOkHttpClient() }

    fun bootstrap(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        SingletonImageLoader.setSafe { platformContext ->
            buildImageLoader(platformContext, appContext)
        }
        AppLog.i(TAG, "Coil ImageLoader factory registered")
        preheatImageHosts()
        preheatImageLoader(appContext)
    }

    /**
     * 在后台立刻触发 SingletonImageLoader 真正构造：
     * - [SingletonImageLoader.setSafe] 注册的是工厂 lambda，buildImageLoader 实际是 lazy 调用，
     *   首屏 RecyclerView bind 时才同步执行，会阻塞主线程；
     * - 提前在 IO 线程跑一次 [SingletonImageLoader.get]，让 DiskCache 内的 journal 提前打开
     *   （冷启动这一步在低端 TV 上 100~300ms）。
     */
    private fun preheatImageLoader(appContext: Context) {
        preheatScope.launch {
            runCatching { SingletonImageLoader.get(appContext) }
                .onFailure { AppLog.w(TAG, "preheat ImageLoader failed: ${it.message}") }
            AppLog.i(TAG, "image loader prewarmed")
        }
    }

    private fun buildImageOkHttpClient(): OkHttpClient {
        val mainClient = NetworkManager.getOkHttpClient()
        val imageDispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        return OkHttpClient.Builder()
            .dns(mainClient.dns)
            .protocols(mainClient.protocols)
            .dispatcher(imageDispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 对常见图片 CDN host **并发**发一次 HEAD：
     * - 触发 DNS lookup；
     * - 完成 TCP 三次握手 + TLS handshake；
     * - 连接放入 [ConnectionPool]，5 分钟内首屏图片请求 0 RTT 复用。
     *
     * 并发提交避免 3 个 host 串行等响应（首版串行实测 1.2s+）。
     */
    private fun preheatImageHosts() {
        val hosts = listOf(
            "https://i0.hdslb.com/",
            "https://i1.hdslb.com/",
            "https://i2.hdslb.com/"
        )
        hosts.forEach { url ->
            preheatScope.launch {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .head()
                        .build()
                    imageOkHttpClient.newCall(request).execute().use { /* discard body */ }
                }.onFailure {
                    AppLog.w(TAG, "preheat $url failed: ${it.message}")
                }
                AppLog.i(TAG, "image CDN preheat done: $url")
            }
        }
    }

    private fun buildImageLoader(
        platformContext: PlatformContext,
        appContext: Context
    ): ImageLoader {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClassMb = activityManager.memoryClass
        val memoryCacheBytes = (memoryClassMb * 1024L * 1024 * 0.15).toLong()
        // 内部 cacheDir 走 ext4/f2fs，比 externalCacheDir（很多 TV / 模拟器是 FUSE emulated FS，
        // File.isInvalid() 等 IO 操作奇慢）快 10x 以上。
        // 512MB 在低端设备上 journal 初始化和清理都很重，128MB 已经能存上千张视频封面。
        val diskCacheDir = File(appContext.cacheDir, "coil_cache")
            .apply { if (!exists()) mkdirs() }

        return ImageLoader.Builder(platformContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir.toOkioPath())
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient }
                    )
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // 首屏 12 张图同时 crossfade 会让 RenderThread 持续 invalidate 4 帧 x 200ms。
            // 视频封面那条路径 (ImageLoader.loadVideoCover) 早就 crossfade=false，
            // 这里全局也关掉，给所有冷启动路径默认不开。需要淡入的场景 (头像、用户详情) 自己 opt-in。
            .crossfade(false)
            .allowHardware(true)
            .build()
    }
}
