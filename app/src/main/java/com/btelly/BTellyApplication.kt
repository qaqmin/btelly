package com.btelly

import android.app.Application
import android.os.SystemClock
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import com.btelly.core.lifecycle.AppBackgroundMonitor
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.ui.image.BTellyCoilInitializer
import com.btelly.di.appModules
import com.btelly.feature.home.RecommendFeedRepository
import com.btelly.feature.player.PlayerInstancePool
import com.btelly.network.NetworkManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.mp.KoinPlatform

class BTellyApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupPrewarmScheduled = AtomicBoolean(false)
    
    companion object {
        private const val TAG = "AppStartup"

        lateinit var instance: BTellyApplication
            private set
    }
    
    override fun onCreate() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "Application.onCreate start")
        super.onCreate()
        instance = this
        AppLog.init(this)

        trace("initKoin", startMs) { initKoin() }
        trace("initSettings", startMs) { initSettings() }
        trace("initNetwork", startMs) { initNetwork() }
        trace("initCoil", startMs) { BTellyCoilInitializer.bootstrap(this) }
        trace("initBackgroundMonitor", startMs) { AppBackgroundMonitor.init(this) }
        AppLog.i(TAG, "Application.onCreate end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private inline fun trace(name: String, appStartMs: Long, block: () -> Unit) {
        val stepStartMs = SystemClock.elapsedRealtime()
        block()
        AppLog.i(
            TAG,
            "$name end step=${SystemClock.elapsedRealtime() - stepStartMs}ms total=${SystemClock.elapsedRealtime() - appStartMs}ms"
        )
    }

    private fun initSettings() {
        KoinPlatform.getKoin().get<AppSettingsDataStore>().initCache()
    }
    
    private fun initKoin() {
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@BTellyApplication)
            modules(appModules)
        }
    }
    
    private fun initNetwork() {
        NetworkManager.init(this, syncWebViewCookies = false)
        appScope.launch {
            NetworkManager.warmUp()

            // 推荐首页只依赖本地持久化的 cookies。CookieJar 在 NetworkManager.init 阶段
            // 已经从 SP 加载完毕（initSettings 同步阻塞保证 cache 就绪），此处直接发请求即可。
            // 不再做 WebView cookie 同步：webCookieManager 里的 cookie 全部来自 OkHttp
            // saveFromResponse 反向写入，跟我们的 SP 同源，冷启动期没有任何"新 cookie"
            // 可同步；而 WebView 冷加载在电视上 300~800ms，是纯负担。
            if (NetworkManager.isLoggedIn()) {
                KoinPlatform.getKoin().get<com.btelly.event.AppEventHub>()
                    .dispatch(com.btelly.event.AppEventHub.Event.UserSessionChanged)
            }
            runCatching {
                KoinPlatform.getKoin().get<RecommendFeedRepository>().preloadFirstPage()
            }
        }
    }

    fun scheduleDeferredSessionPrewarm(delayMillis: Long = 300L) {
        if (!startupPrewarmScheduled.compareAndSet(false, true)) {
            return
        }
        appScope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            if (NetworkManager.isLoggedIn()) {
                NetworkManager.prewarmWebSession()
            } else {
                startupPrewarmScheduled.set(false)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= 80 -> { // TRIM_MEMORY_COMPLETE
                ImageLoader.clearMemory(this)
                if (!PlayerInstancePool.isAttached()) {
                    PlayerInstancePool.releaseNow("trimMemory_complete")
                }
            }
            level >= 60 -> { // TRIM_MEMORY_MODERATE
                ImageLoader.clearMemory(this)
            }
            level >= 10 -> { // TRIM_MEMORY_RUNNING_LOW
                if (!PlayerInstancePool.isAttached()) {
                    PlayerInstancePool.releaseNow("trimMemory_running_low")
                }
            }
        }
    }
}
