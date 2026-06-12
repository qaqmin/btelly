package com.btelly.feature.cctv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.btelly.core.common.log.AppLog

object CctvWebViewPrewarmer {

    private val handler = Handler(Looper.getMainLooper())
    private var requested = false
    private var completed = false
    private var started = false
    private var pendingContext: Context? = null
    private val prewarmRunnable = Runnable {
        val context = pendingContext
        pendingContext = null
        if (context == null) {
            requested = false
            return@Runnable
        }
        runCatching {
            started = true
            prewarmNow(context)
        }.onFailure { error ->
            AppLog.e(TAG, "prewarmError ${error.message}")
            requested = false
            started = false
        }
    }

    fun prewarm(context: Context) {
        if (requested || completed) return
        requested = true
        pendingContext = context.applicationContext
        handler.postDelayed(prewarmRunnable, PREWARM_DELAY_MS)
    }

    fun cancelIfPending() {
        if (!requested || completed || started) return
        handler.removeCallbacks(prewarmRunnable)
        pendingContext = null
        requested = false
        AppLog.i(TAG, "prewarmCanceled")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prewarmNow(context: Context) {
        val startMs = SystemClock.elapsedRealtime()
        val webView = WebView(context)
        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.loadDataWithBaseURL(
            "https://tv.cctv.com/",
            "<!doctype html><html><body></body></html>",
            "text/html",
            "UTF-8",
            null
        )
        handler.postDelayed({
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            completed = true
            requested = false
            started = false
            AppLog.i(TAG, "prewarmDone elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }, DESTROY_DELAY_MS)
    }

    private const val TAG = "CctvWebViewPrewarmer"
    private const val PREWARM_DELAY_MS = 1_200L
    private const val DESTROY_DELAY_MS = 800L
}
