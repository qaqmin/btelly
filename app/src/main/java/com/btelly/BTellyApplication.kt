package com.btelly

import android.app.Application
import android.os.SystemClock
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import com.btelly.di.appModules
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
        
        trace("initKoin", startMs) { initKoin() }
        trace("initSettings", startMs) { initSettings() }
        trace("initNetwork", startMs) { initNetwork() }
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
            val cookieSyncStartMs = SystemClock.elapsedRealtime()
            NetworkManager.syncCookiesFromWebView()
            AppLog.i(
                TAG,
                "syncCookiesFromWebView end step=${SystemClock.elapsedRealtime() - cookieSyncStartMs}ms"
            )
            if (NetworkManager.isLoggedIn()) {
                KoinPlatform.getKoin().get<com.btelly.event.AppEventHub>()
                    .dispatch(com.btelly.event.AppEventHub.Event.UserSessionChanged)
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
}
