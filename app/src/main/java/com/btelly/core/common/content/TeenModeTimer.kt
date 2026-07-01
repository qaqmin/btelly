package com.btelly.core.common.content

import android.content.Context
import android.os.SystemClock
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.mp.KoinPlatform

/**
 * 青少年模式 - 观看/休息时长计时器（单例）。
 *
 * 设计要点：
 * - [teen_accumulated_ms] 是**持久化的累计观看毫秒**，每段播放结束立即写回 DataStore，
 *   关 APP / 杀进程 / 重启后继续累加。
 * - 播放段计时用 [SystemClock.elapsedRealtime]：只关心"实际播放了多久"，抗改系统时间。
 * - 休息判定用墙钟 [System.currentTimeMillis]：孩子改系统时间或杀进程都绕不过休息。
 * - 休息上限为 0 → 整个时间限制关闭（不计时也不拦截，青少年保护开关仍独立生效）。
 */
object TeenModeTimer {

    private const val TAG = "TeenModeTimer"

    private const val KEY_WATCH_LIMIT_MIN = "teen_watch_limit_min"
    private const val KEY_REST_LIMIT_MIN = "teen_rest_limit_min"
    private const val KEY_ACCUMULATED_MS = "teen_accumulated_ms"
    private const val KEY_REST_START_MS = "teen_rest_start_ms"

    /** 时长选项：0 表示不限制，步进 10 分钟，最长 120 分钟。 */
    val TIME_OPTIONS_MIN: List<Int> = (0..120 step 10).toList()

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /** 当前播放段的起始 elapsedRealtime；null 表示未在播放。 */
    private var segmentStartElapsedMs: Long? = null

    /** 是否处于休息状态。供 PlayerActivity 观察以达上限立即退出。 */
    private val _restingFlow = MutableStateFlow(false)
    val restingFlow: StateFlow<Boolean> = _restingFlow.asStateFlow()

    @Volatile
    private var initialized = false

    /** Application 启动阶段同步刷新一次休息状态（与 AppSettingsDataStore.initCache 配合）。 */
    fun initOnStartup() {
        if (initialized) return
        initialized = true
        refreshRestingState()
    }

    /** 时间限制总开关：休息上限 > 0 才启用计时与拦截。 */
    fun isTimeLimitEnabled(): Boolean {
        return getRestLimitMin() > 0
    }

    /** 当前观看上限（毫秒），0 表示不限。 */
    private fun getWatchLimitMs(): Long {
        return getWatchLimitMin().toLong() * 60_000L
    }

    fun getWatchLimitMin(): Int {
        // 设置页用 putStringAsync 存（字符串），这里必须用同类型读取，否则强类型 DataStore 读不出。
        return appSettings.getCachedString(KEY_WATCH_LIMIT_MIN)?.trim()?.toIntOrNull() ?: 0
    }

    fun getRestLimitMin(): Int {
        return appSettings.getCachedString(KEY_REST_LIMIT_MIN)?.trim()?.toIntOrNull() ?: 0
    }

    fun setWatchLimitMin(min: Int) {
        appSettings.putIntAsync(KEY_WATCH_LIMIT_MIN, min.coerceIn(0, 120))
        // 改时长设置：清掉累计和休息戳，避免脏状态。
        resetForLimitChange()
    }

    fun setRestLimitMin(min: Int) {
        appSettings.putIntAsync(KEY_REST_LIMIT_MIN, min.coerceIn(0, 120))
        resetForLimitChange()
    }

    /** 持久化保存观看上限（设置页直接写 String 的兼容入口）。 */
    fun saveWatchLimitString(value: String) {
        appSettings.putStringAsync(KEY_WATCH_LIMIT_MIN, value)
        resetForLimitChange()
    }

    fun saveRestLimitString(value: String) {
        appSettings.putStringAsync(KEY_REST_LIMIT_MIN, value)
        resetForLimitChange()
    }

    /**
     * 播放开始（isPlaying 变 true）。
     * 若已在休息状态则不重新计时，避免休息期间后台播放偷跑时长。
     */
    fun onPlayStart() {
        if (!isTimeLimitEnabled()) return
        // 进入休息后不再累计，直到休息结束由 [refreshRestingState] 清零。
        if (_restingFlow.value) return
        if (segmentStartElapsedMs != null) return
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * 播放停止（isPlaying 变 false / 退出播放器）。
     * 结算本段实际播放时长，累加到持久化累计值；达上限则进入休息。
     */
    fun onPlayStop() {
        if (!isTimeLimitEnabled()) return
        val startMs = segmentStartElapsedMs ?: return
        segmentStartElapsedMs = null
        settleSegment(startMs)
    }

    /**
     * 播放中周期性结算（每 ~15 秒由 PlayerActivity 调用）。
     *
     * 设计为自给自足：不依赖 onPlayStart 是否被调用过——若 segmentStart 为空，
     * 说明播放监听器未触发（部分机型/某些播放路径下 onIsPlayingChanged 不可靠），
     * 这里自动用当前时刻初始化起点，保证只要定时器在跑，计时就一定工作。
     */
    fun tick() {
        if (!isTimeLimitEnabled()) return
        // 休息中（含到期清零）：丢弃当前计时段，等休息真正结束后从头计时。
        // 关键：必须清 segmentStart，否则休息结束后第一次结算会把"休息时长"误算进去立即触发。
        if (isResting()) {
            segmentStartElapsedMs = null
            return
        }
        // 关键修复：segmentStart 为空时自动初始化，避免空转导致永不计时
        if (segmentStartElapsedMs == null) {
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            return
        }
        settleSegment(segmentStartElapsedMs!!)
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /** 把 [fromElapsedMs] 到现在的实际播放时长累加到持久化累计值，达上限则进入休息。 */
    private fun settleSegment(fromElapsedMs: Long) {
        val delta = (SystemClock.elapsedRealtime() - fromElapsedMs).coerceAtLeast(0L)
        if (delta <= 0L) return
        val accumulated = appSettings.getCachedInt(KEY_ACCUMULATED_MS, 0) + delta.toInt()
        val limitMs = getWatchLimitMs()
        appSettings.putIntAsync(KEY_ACCUMULATED_MS, accumulated)
        if (limitMs > 0 && accumulated >= limitMs) {
            // 达上限：写休息开始戳、触发休息状态。
            appSettings.putLongAsync(KEY_REST_START_MS, System.currentTimeMillis())
            _restingFlow.value = true
            AppLog.i(TAG, "watch limit reached, enter rest. accumulated=$accumulated")
        }
    }

    /**
     * 是否处于休息状态。过期会自动清零累计与休息戳。
     * 同时是三个入口（视频/直播/TV直播）的统一查询点。
     */
    fun isResting(): Boolean {
        if (!isTimeLimitEnabled()) {
            return false
        }
        val restStart = appSettings.getCachedLong(KEY_REST_START_MS, 0L)
        if (restStart <= 0L) {
            return false
        }
        val restLimitMs = getRestLimitMin().toLong() * 60_000L
        val elapsed = System.currentTimeMillis() - restStart
        if (elapsed >= restLimitMs) {
            // 休息到期：清零累计、清休息戳、清计时段起点，恢复观看从头计时。
            appSettings.removeAsync(KEY_REST_START_MS)
            appSettings.putIntAsync(KEY_ACCUMULATED_MS, 0)
            segmentStartElapsedMs = null
            _restingFlow.value = false
            AppLog.i(TAG, "rest ended, reset accumulated. elapsed=$elapsed restLimitMs=$restLimitMs")
            return false
        }
        _restingFlow.value = true
        return true
    }

    /**
     * 入口拦截：休息中返回带剩余时间的提示文案，否则返回 null 放行。
     * 剩余分钟向上取整（不足 1 分钟按 1 分钟显示）。
     */
    fun consumeBlockReason(context: Context): String? {
        if (!isResting()) return null
        val restStart = appSettings.getCachedLong(KEY_REST_START_MS, 0L)
        val restLimitMs = getRestLimitMin().toLong() * 60_000L
        val remainingMs = (restLimitMs - (System.currentTimeMillis() - restStart)).coerceAtLeast(0L)
        // 向上取整：剩余 30 秒也显示 1 分钟
        val remainingMin = ((remainingMs + 60_000L - 1) / 60_000L).toInt().coerceAtLeast(1)
        return "请关闭电视注意休息，还需休息 $remainingMin 分钟"
    }

    /**
     * 用户修改时长设置时调用：清掉累计观看时长与休息戳，避免脏状态。
     * 例如把上限调大后，旧累计值可能已超新上限，会立刻误触发休息。
     */
    fun resetForLimitChange() {
        appSettings.putIntAsync(KEY_ACCUMULATED_MS, 0)
        appSettings.removeAsync(KEY_REST_START_MS)
        segmentStartElapsedMs = null
        _restingFlow.value = false
        AppLog.i(TAG, "reset for limit change")
    }

    /** App 启动时刷新一次休息状态（让 restingFlow 反映持久化中的真实值）。 */
    private fun refreshRestingState() {
        _restingFlow.value = isResting()
    }
}
