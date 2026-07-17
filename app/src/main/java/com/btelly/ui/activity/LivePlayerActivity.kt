package com.btelly.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import com.btelly.R
import com.btelly.databinding.ActivityPlayerBinding
import com.btelly.core.ui.base.BaseActivity
import com.btelly.core.common.ext.toast
import com.btelly.feature.player.LivePlayerFragment

@OptIn(UnstableApi::class)
class LivePlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    private var exitTime: Long = 0
    private val exitInterval = 2000L

    /** 青少年模式：直播期间每 15 秒结算观看时长，达上限触发休息退出。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val teenModeTicker = object : Runnable {
        override fun run() {
            com.btelly.core.common.content.TeenModeTimer.tick()
            if (com.btelly.core.common.content.TeenModeTimer.isResting()) {
                val restMin = com.btelly.core.common.content.TeenModeTimer.getRestLimitMin().coerceAtLeast(1)
                toast("请关闭电视注意休息，还需休息 $restMin 分钟")
                finish()
                return
            }
            // 公益广告触发：启动锁死播放，原直播进入后台自动暂停，回来 onResume 恢复
            if (com.btelly.core.common.content.TeenModeTimer.checkAndConsumePsasTrigger()) {
                com.btelly.core.common.content.PsasRepository.launchRandomPsas(this@LivePlayerActivity)
            }
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    override fun getViewBinding(): ActivityPlayerBinding =
        ActivityPlayerBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1L)
        if (roomId <= 0) return finish()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - exitTime <= exitInterval) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    Toast.makeText(applicationContext, "再按一次退出播放", Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.player_container, LivePlayerFragment.newInstance(roomId))
            }
        }
        // 青少年模式：启动观看时长定时器
        mainHandler.postDelayed(teenModeTicker, 15_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(teenModeTicker)
    }

    companion object {
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, roomId: Long) {
            // 青少年模式：休息期间拦截直播入口
            com.btelly.core.common.content.TeenModeTimer.consumeBlockReason()?.let {
                context.toast(it)
                return
            }
            context.startActivity(Intent(context, LivePlayerActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            })
        }
    }
}
