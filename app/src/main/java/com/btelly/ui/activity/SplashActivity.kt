package com.btelly.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.btelly.R
import com.btelly.core.common.log.AppLog

class SplashActivity : Activity() {

    private var forwarded = false
    private val createStartMs = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.i(TAG, "STARTUP SplashActivity.onCreate start")
        if (savedInstanceState == null && shouldFinishDuplicateLauncherLaunch()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        setContentView(createSplashView())
        AppLog.i(TAG, "STARTUP SplashActivity.onCreate end elapsed=${SystemClock.elapsedRealtime() - createStartMs}ms")
    }

    override fun onResume() {
        super.onResume()
        forwardToMainIfNeeded()
    }

    private fun createSplashView(): FrameLayout {
        val surfaceColor = TypedValue().let { typed ->
            if (theme.resolveAttribute(R.attr.tvSurface, typed, true)) {
                typed.data
            } else {
                ContextCompat.getColor(this, R.color.tv_surface)
            }
        }
        return FrameLayout(this).apply {
            setBackgroundColor(surfaceColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(ImageView(this@SplashActivity).apply {
                setImageResource(R.drawable.ic_brand_splash)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.splash_brand_size),
                    resources.getDimensionPixelSize(R.dimen.splash_brand_size),
                    Gravity.CENTER
                )
            })
        }
    }

    private fun forwardToMainIfNeeded() {
        if (forwarded) return
        forwarded = true
        Choreographer.getInstance().postFrameCallback {
            if (isFinishing || isDestroyed) return@postFrameCallback
            AppLog.i(TAG, "STARTUP SplashActivity.forwardToMain elapsed=${SystemClock.elapsedRealtime() - createStartMs}ms")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            finish()
            suppressTransitionAnimation()
        }
    }

    private fun shouldFinishDuplicateLauncherLaunch(): Boolean {
        val launchIntent = intent
        val isLauncherAction = launchIntent?.action == Intent.ACTION_MAIN
        val isLauncherCategory = launchIntent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true ||
            launchIntent?.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) == true
        return !isTaskRoot &&
            isLauncherAction &&
            isLauncherCategory
    }

    private fun suppressTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "AppStartup"
    }
}
