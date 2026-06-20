package com.btelly.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.btelly.core.common.log.AppLog
import com.btelly.core.ui.base.BaseActivity
import com.btelly.databinding.ActivityMarmotLiveBinding
import com.btelly.feature.marmot.MarmotCloudUpdate
import com.btelly.feature.marmot.MarmotJsBridge
import com.btelly.feature.marmot.MarmotLiveData
import com.btelly.feature.marmot.domain.HzItem
import com.btelly.feature.marmot.domain.MarmotModels.Live
import com.btelly.feature.marmot.domain.MarmotModels.Vod
import com.btelly.ui.adapter.QualityAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV 直播主界面（对标参考 utao `LiveActivity.java`，基于系统 WebView）。
 *
 * 核心能力：
 * 1. **播放**：系统 WebView 加载官网直播页（如 tv.cctv.com），[MarmotWebViewClient] 注入 tv.user.js 劫持播放。
 * 2. **频道列表**（确认键弹）：[showChannelMenu] 显示分组+频道，D-pad 左右切分组、上下选频道。
 * 3. **画质选择**（菜单键弹）：[showQualityMenu] 横向列表，数据来自页面 JS 上报的 videoQuality。
 * 4. **切台**：上下左右键触发 [goNext]，调 [MarmotLiveData.liveNext] 四向环形切台，延迟 1s 加载防抖。
 *
 * 按键映射（按需求，与参考不同）：
 * - **确认键(OK/ENTER)** → [showChannelMenu] 频道列表
 * - **菜单键(MENU)** → [showQualityMenu] 画质选择
 * - **上下左右** → [goNext] 切台 + 转发页面脚本
 * - **返回键** → 关闭浮层/退出
 */
class MarmotLiveActivity : BaseActivity<ActivityMarmotLiveBinding>() {

    companion object {
        private const val TAG = "MarmotLiveActivity"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        /** DataStore key：上次观看频道的 URL（下次进直播恢复）。 */
        private const val KEY_LAST_CHANNEL_URL = "marmot_last_channel_url"

        /** 启动入口。 */
        fun start(context: Context) {
            context.startActivity(Intent(context, MarmotLiveActivity::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    /** 当前播放频道。 */
    private var currentVod: Vod? = null
    /** 全部分组（频道列表数据）。 */
    private val provinces = ArrayList<Live>()
    /** 频道列表当前选中的分组索引。 */
    private var currentProvinceIndex = 0
    /** 频道列表是否显示。 */
    private var isChannelMenuShowing = false
    /** 画质列表是否显示。 */
    private var isQualityMenuShowing = false
    /** 页面上报的画质数据（JSON 字符串）。 */
    private var videoQualityData: String? = null
    /** 已自动应用最高画质的频道 URL（避免重复执行）。 */
    private var lastQualityAppliedUrl: String? = null
    /** 两次返回退出计时（对标 PlayerActivity.setupBackHandler）。 */
    private var exitTime: Long = 0
    private val exitInterval = 2000L

    /** 切台防抖：延迟 1s 后真正加载（避免快速连按）。 */
    private val switchChannelRunnable = Runnable {
        // 切台走 playCurrent（统一保存 URL 记录，以便下次恢复）
        playCurrent()
    }

    private var webEngine: com.btelly.feature.marmot.web.WebEngine? = null

    /** JS 桥回调实现。 */
    private val jsCallbacks = object : MarmotJsBridge.Callbacks {
        override fun onVideoQuality(rawData: String) {
            videoQualityData = rawData
            Log.i(TAG, "收到画质数据（${rawData.length} 字符）")
            // 自动切换到最高画质：JS 桥回调在子线程，必须切主线程执行 evaluateJavascript
            runOnUiThread { applyBestQualityOnce(rawData) }
        }
        override fun onEvalJs(js: String) {
            runOnUiThread { webEngine?.evaluateJavascript(js) }
        }
        override fun onSimulateKey(keyCodeStr: String) {
            // 页面请求模拟按键（数字），参考 BaseActivity.keyEventAll
            try {
                val keyCode = keyCodeStr.toInt()
                Thread {
                    try {
                        val inst = android.app.Instrumentation()
                        inst.sendKeyDownUpSync(keyCode)
                    } catch (e: Exception) {
                        Log.w(TAG, "模拟按键失败: $keyCode", e)
                    }
                }.start()
            } catch (e: NumberFormatException) {
                // key 事件支持映射（SPACE/F），参考 keyCodeMap
                val mapped = when (keyCodeStr) {
                    "SPACE" -> KeyEvent.KEYCODE_SPACE
                    "F" -> KeyEvent.KEYCODE_F
                    else -> return
                }
                Thread {
                    try {
                        android.app.Instrumentation().sendKeyDownUpSync(mapped)
                    } catch (e: Exception) { }
                }.start()
            }
        }
    }
    private lateinit var jsBridge: MarmotJsBridge

    override fun getViewBinding(): ActivityMarmotLiveBinding =
        ActivityMarmotLiveBinding.inflate(layoutInflater)

    @SuppressLint("SetJavaScriptEnabled")
    override fun initView() {
        jsBridge = MarmotJsBridge(this, jsCallbacks)
        initWebView()
        setupChannelMenu()
        setupQualityMenu()
        setupMenuDismiss()
    }

    /** 创建并配置 WebView（对标参考 `BaseActivity.initWebView`）。
     *  按 X5 内核是否加载选择系统 WebView 或 X5 WebView（WebEngine 封装）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        // 对标参考 APP：已安装 X5（installLocalTbsCore 成功过）就直接用 X5 WebView，
        // 不查 canLoadX5（内核加载是异步的，参考 APP 也是直接 new X5 WebView 让 SDK 自行加载）。
        val useX5 = com.btelly.feature.marmot.x5.X5TbsDownloader.isInstalled(this)
        Log.i(TAG, "WebView 内核：${if (useX5) "X5" else "系统"}")
        val engine = com.btelly.feature.marmot.web.WebEngine(
            context = this,
            useX5 = useX5,
            jsBridge = jsBridge,
            onProgress = { progress ->
                binding.progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            },
            onPageLoaded = { url ->
                val vod = MarmotLiveData.getByUrl(url)
                if (vod != null) {
                    currentVod = vod
                    showLiveName(vod.name)
                }
            },
            onShowCustomView = { customView ->
                // 全屏视频：把播放器视图加到 fullscreenContainer，隐藏 WebView 容器
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.addView(
                    customView,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webviewWrapper.visibility = View.GONE
            },
            onHideCustomView = {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webviewWrapper.visibility = View.VISIBLE
            }
        )
        webEngine = engine
        binding.webviewWrapper.addView(
            engine.view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        engine.requestFocus()
    }

    override fun initData() {
        // 协程：云端更新 + 加载频道 + 播放首个频道
        CoroutineScope(Dispatchers.Main).launch {
            // 1. 云端更新 tv-web（静默失败，用内置种子兜底）
            val updated = withContext(Dispatchers.IO) {
                runCatching { MarmotCloudUpdate.checkAndUpdateRes(this@MarmotLiveActivity) }
                    .onFailure { AppLog.e(TAG, "云端更新异常: ${it.javaClass.simpleName}: ${it.message}", it) }
                    .getOrDefault(false)
            }
            AppLog.i(TAG, "initData: 云端更新结果=$updated")
            // 2. 加载频道表
            val loaded = withContext(Dispatchers.IO) { MarmotLiveData.load(this@MarmotLiveActivity) }
            AppLog.i(TAG, "initData: 频道表加载结果=$loaded")
            if (!loaded) {
                AppLog.e(TAG, "initData: 频道数据加载失败，请检查网络/明文流量权限/filesDir 是否损坏")
                Toast.makeText(this@MarmotLiveActivity, "频道数据加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            provinces.clear()
            provinces.addAll(MarmotLiveData.getLives())
            // 3. 恢复上次观看频道：优先按保存的 URL 反查，查不到才回退首个频道 0_0
            val lastUrl = appSettings.getCachedString(KEY_LAST_CHANNEL_URL, null)
            currentVod = if (!lastUrl.isNullOrEmpty()) {
                MarmotLiveData.getByUrl(lastUrl) ?: MarmotLiveData.getByKey("0_0")
            } else {
                MarmotLiveData.getByKey("0_0")
            }
            if (currentVod == null) {
                Toast.makeText(this@MarmotLiveActivity, "无频道数据", Toast.LENGTH_SHORT).show()
                return@launch
            }
            playCurrent()
            Toast.makeText(this@MarmotLiveActivity, "已支持遥控器上下左右可快速切台", Toast.LENGTH_SHORT).show()
        }
    }

    /** 播放当前频道，并记录 URL 以便下次恢复（对标参考 `HistoryDaoX.updateChannel`）。 */
    private fun playCurrent() {
        val vod = currentVod ?: return
        mainHandler.removeCallbacks(switchChannelRunnable)
        webEngine?.loadUrl(vod.url)
        showLiveName(vod.name)
        // 记录上次观看频道（IO 写入，不阻塞）
        appSettings.putStringAsync(KEY_LAST_CHANNEL_URL, vod.url)
    }

    /** 短暂显示频道名（2 秒后清除）。 */
    private fun showLiveName(name: String) {
        binding.liveName.text = name
        binding.liveName.visibility = View.VISIBLE
        mainHandler.postDelayed({ binding.liveName.visibility = View.GONE }, 2000)
    }

    // ==================== 频道列表浮层（确认键弹出）====================

    /** 频道列表适配器（RecyclerView，对标截图样式）。 */
    private val channelAdapter = com.btelly.ui.adapter.ChannelAdapter { vod ->
        if (vod.url.isNotEmpty()) {
            currentVod = vod
            playCurrent()
            hideChannelMenu()
        }
    }
    /** 分组列表适配器（左排）。点击/确认展开右排，焦点留在左排。 */
    private val groupAdapter = com.btelly.ui.adapter.GroupAdapter { index, live ->
        currentProvinceIndex = index
        // 展开右排频道列表（不切焦点，焦点留在左排当前分组）
        channelAdapter.currentPlayingUrl = currentVod?.url
        channelAdapter.submitList(live.vods)
    }

    /** 初始化频道列表控件（双排：groupList + channelList）。 */
    private fun setupChannelMenu() {
        binding.groupList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.groupList.adapter = groupAdapter
        binding.channelList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        // 互相关联：OnKeyListener 里跨列表切焦点用
        groupAdapter.channelRecyclerView = binding.channelList
        channelAdapter.groupRecyclerView = binding.groupList
        channelAdapter.groupAdapter = groupAdapter
        // 空白区点击关闭
        binding.menuBlankArea.setOnClickListener { hideChannelMenu() }
    }

    /** 显示频道列表浮层（双排）。 */
    private fun showChannelMenu() {
        if (provinces.isEmpty()) {
            Toast.makeText(this, "频道数据加载中...", Toast.LENGTH_SHORT).show()
            return
        }
        isChannelMenuShowing = true
        binding.menuContainer.visibility = View.VISIBLE
        // 定位到当前频道所在分组
        currentVod?.let { currentProvinceIndex = it.tagIndex }
        // 初始化左排分组列表（滚动定位当前分组，不抢焦点）
        groupAdapter.currentSelected = currentProvinceIndex
        groupAdapter.submitList(provinces) {
            binding.groupList.scrollToPosition(currentProvinceIndex)
        }
        // 右排显示当前分组频道，焦点给当前播放频道（用户直接选频道）
        if (currentProvinceIndex < provinces.size) {
            val live = provinces[currentProvinceIndex]
            channelAdapter.currentPlayingUrl = currentVod?.url
            channelAdapter.submitList(live.vods) {
                binding.channelList.post {
                    val idx = if (currentVod != null && currentVod!!.tagIndex == currentProvinceIndex) {
                        currentVod!!.detailIndex.coerceAtLeast(0).coerceAtMost(live.vods.lastIndex)
                    } else 0
                    binding.channelList.scrollToPosition(idx)
                    // 焦点给右排当前播放频道
                    binding.channelList.layoutManager?.findViewByPosition(idx)?.requestFocus()
                }
            }
        }
    }

    /** 隐藏频道列表浮层。 */
    private fun hideChannelMenu() {
        isChannelMenuShowing = false
        binding.menuContainer.visibility = View.GONE
    }

    // ==================== 画质选择浮层（菜单键弹出）====================

    private fun setupQualityMenu() {
        binding.qualityList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.qualityContainer.setOnClickListener { hideQualityMenu() }
    }

    /** 显示画质选择浮层。 */
    private fun showQualityMenu() {
        val items = parseQualityData(videoQualityData)
        if (items.isEmpty()) {
            Toast.makeText(this, "当前频道暂无画质选项", Toast.LENGTH_SHORT).show()
            return
        }
        isQualityMenuShowing = true
        binding.qualityContainer.visibility = View.VISIBLE
        binding.qualityList.adapter = QualityAdapter(items) { item ->
            // 执行画质切换脚本
            item.action?.takeIf { it.trim().isNotEmpty() }?.let { action ->
                webEngine?.evaluateJavascript(action)
            }
            hideQualityMenu()
        }
        // 焦点定位到第一项：先让 RecyclerView 获取焦点，布局完成后聚焦首项
        binding.qualityList.requestFocus()
        binding.qualityList.post {
            binding.qualityList.layoutManager?.findViewByPosition(0)?.requestFocus()
                ?: binding.qualityList.requestFocus()
        }
    }

    private fun hideQualityMenu() {
        isQualityMenuShowing = false
        binding.qualityContainer.visibility = View.GONE
    }

    /**
     * 自动切换到最高画质（第一项），每个频道只执行一次（对标"默认最好画质"）。
     * 页面上报 videoQuality 后调用，执行第一项（通常"超清"/"1080P"）的 action。
     */
    private fun applyBestQualityOnce(rawData: String) {
        val url = currentVod?.url ?: return
        // 同一频道已应用过，跳过（切台后 URL 变化才会重新应用）
        if (url == lastQualityAppliedUrl) return
        val items = parseQualityData(rawData)
        if (items.isEmpty()) return
        // 第一项通常是最高画质
        val best = items[0]
        best.action?.takeIf { it.trim().isNotEmpty() }?.let { action ->
            Log.i(TAG, "自动切换最高画质: ${best.name}")
            webEngine?.evaluateJavascript(action)
            lastQualityAppliedUrl = url
        }
    }

    /** 解析画质数据（对标参考 `LiveActivity.A()`，支持数组或对象嵌套）。 */
    private fun parseQualityData(raw: String?): List<HzItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                gson.fromJson(trimmed, object : TypeToken<List<HzItem>>() {}.type) ?: emptyList()
            } else {
                val obj = com.google.gson.JsonParser.parseString(trimmed).asJsonObject
                val qualities = obj.getAsJsonArray("qualities")
                    ?: obj.getAsJsonObject("data")?.getAsJsonArray("qualities")
                    ?: obj.getAsJsonObject("data")?.getAsJsonArray("items")
                if (qualities != null) {
                    gson.fromJson(qualities, object : TypeToken<List<HzItem>>() {}.type) ?: emptyList()
                } else listOf(gson.fromJson(trimmed, HzItem::class.java))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析画质数据失败: ${t.message}")
            emptyList()
        }
    }

    /** 点击空白处关闭浮层。 */
    private fun setupMenuDismiss() {
        binding.marmotRoot.setOnClickListener {
            if (isChannelMenuShowing) hideChannelMenu()
            if (isQualityMenuShowing) hideQualityMenu()
        }
    }

    // ==================== 按键交互 ====================

    /**
     * 四向切台（对标参考 `LiveActivity.goNext`）。
     * 调 [MarmotLiveData.liveNext] 算下一频道 key，延迟 1s 加载防抖。
     */
    private fun goNext(direction: String): Boolean {
        val vod = currentVod ?: MarmotLiveData.getByKey("0_0").also { currentVod = it }
        if (vod == null) return false
        val key = MarmotLiveData.liveNext(vod.tagIndex, vod.detailIndex, direction)
        val next = MarmotLiveData.getByKey(key) ?: return false
        currentVod = next
        showLiveName(next.name)
        // 延迟 1s 加载（参考 handler.sendMessageDelayed），避免快速连按多次加载
        mainHandler.removeCallbacks(switchChannelRunnable)
        mainHandler.postDelayed(switchChannelRunnable, 1000)
        return true
    }

    /** 转发遥控按键到页面脚本（参考反编译版 `_messageCtrl.ctrl(action)`）。 */
    private fun forwardKeyToPage(action: String) {
        val js = "(function(){try{if(window._messageCtrl&&typeof window._messageCtrl.ctrl==='function'){window._messageCtrl.ctrl('$action');}}catch(e){}})();"
        webEngine?.evaluateJavascript(js)
    }

    /** 触屏点击：呼出频道列表（对标参考 `LiveActivity.dispatchTouchEvent`），阻止 WebView video 默认暂停。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 浮层显示时，触摸交给浮层处理
        if (!isChannelMenuShowing && !isQualityMenuShowing && ev.action == MotionEvent.ACTION_DOWN) {
            showChannelMenu()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode

        // —— 频道列表浮层（双排：FocusLockRecyclerView 在 focusSearch 层防溢出）——
        if (isChannelMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { hideChannelMenu(); return true }
            }
            return super.dispatchKeyEvent(event)
        }
        if (isQualityMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    hideQualityMenu(); return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // —— 无浮层时 ——
        when (keyCode) {
            // 确认键 → 频道列表
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannelMenu(); return true }
            // 菜单键 → 画质选择
            KeyEvent.KEYCODE_MENU -> { showQualityMenu(); return true }
            // 上下左右 → 切台 + 转发页面脚本
            KeyEvent.KEYCODE_DPAD_UP -> { forwardKeyToPage("up"); return goNext("up") }
            KeyEvent.KEYCODE_DPAD_DOWN -> { forwardKeyToPage("down"); return goNext("down") }
            KeyEvent.KEYCODE_DPAD_LEFT -> { forwardKeyToPage("left"); return goNext("left") }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { forwardKeyToPage("right"); return goNext("right") }
            KeyEvent.KEYCODE_BACK -> {
                if (System.currentTimeMillis() - exitTime <= exitInterval) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    Toast.makeText(this, "再按一次退出直播", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        webEngine?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webEngine?.onResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(switchChannelRunnable)
        webEngine?.let { engine ->
            engine.removeJavascriptInterface(jsBridge.bridgeName)
            engine.loadUrl("about:blank")
            engine.stopLoading()
            engine.destroy()
        }
        webEngine = null
        super.onDestroy()
    }
}
