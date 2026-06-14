package com.btelly.feature.player

import com.google.gson.Gson
import com.btelly.core.common.log.AppLog
import com.btelly.network.WbiGenerator
import com.btelly.network.api.ApiService
import com.btelly.network.session.NetworkSessionGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 播放心跳与起播上报的协作对象。
 *
 * 从 VideoPlayerViewModel 拆分而来，仅负责 heartbeat / click-h5 上报逻辑。
 * 所有播放上下文（aid/cid/位置/时长/画质等）通过 [HeartbeatContext] 回调读取，
 * 不持有可变播放状态。生命周期跟随 ViewModel 注入的 [scope]。
 */
internal class PlaybackHeartbeatReporter(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val scope: CoroutineScope,
    private val context: HeartbeatContext
) {

    /** 心跳读取的播放上下文，由 ViewModel 实现。 */
    interface HeartbeatContext {
        val currentAid: Long?
        val currentCid: Long
        val currentBvid: String?
        val pendingSeekPositionMs: Long
        val currentPositionMs: Long
        val durationMs: Long
        val playInfoDurationMs: Long
        val qualityId: Int
    }

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        private const val WEB_LOCATION_PLAYER = "1315873"
        private const val PLAYER_SPMID = "333.788.0.0"
        private const val DEFAULT_FROM_SPMID = "333.1007.tianma.1-3-3.click"
        private const val WEB_PLAYER_VERSION = "4.9.78"
        /** 起播上报的 play_type 标识。 */
        const val PLAY_TYPE_START = 1
    }

    private val gson = Gson()

    private var sessionStartTimestampMs: Long = 0L
    private var lastReportedHeartbeatPositionSec: Long = -1L
    private var playbackReportSession: String = ""
    private var playbackStartReported: Boolean = false

    fun reportPlaybackHeartbeat(playType: Int = 0) {
        val aid = context.currentAid
        val cid = context.currentCid
        val rawPositionMs = context.currentPositionMs.coerceAtLeast(0L)
        val positionMs = rawPositionMs.takeIf { it > 0L } ?: context.pendingSeekPositionMs.coerceAtLeast(0L)
        val positionSec = positionMs / 1000L
        val csrf = sessionGateway.getCsrfToken()
        if (aid == null || aid <= 0L) {
            return
        }
        if (cid <= 0L || csrf.isBlank()) {
            return
        }
        if (positionSec <= 0L && playType != PLAY_TYPE_START) {
            return
        }
        if (positionSec == lastReportedHeartbeatPositionSec) {
            return
        }
        lastReportedHeartbeatPositionSec = positionSec
        val startTimestampSec = ((sessionStartTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()) / 1000L)
            .coerceAtLeast(1L)
        val realtimeSec = ((System.currentTimeMillis() / 1000L) - startTimestampSec).coerceAtLeast(0L)
        val durationSec = ((context.durationMs.takeIf { it > 0L } ?: context.playInfoDurationMs) / 1000L)
            .coerceAtLeast(positionSec)
        val userInfo = sessionGateway.getUserInfo()
        val mid = userInfo?.mid?.takeIf { it > 0L }
        val quality = context.qualityId
        val session = ensurePlaybackReportSession()

        scope.launch {
            reportPlaybackStartIfNeeded(
                aid = aid,
                cid = cid,
                mid = mid,
                csrf = csrf,
                startTimestampSec = startTimestampSec,
                session = session
            )

            val params = linkedMapOf(
                "start_ts" to startTimestampSec.toString(),
                "aid" to aid.toString(),
                "cid" to cid.toString(),
                "played_time" to positionSec.toString(),
                "realtime" to realtimeSec.toString(),
                "real_played_time" to positionSec.toString(),
                "type" to "3",
                "sub_type" to "0",
                "dt" to "2",
                "play_type" to playType.toString(),
                "refer_url" to buildPlaybackReferUrl(),
                "quality" to quality.toString(),
                "is_auto_qn" to "1",
                "video_duration" to durationSec.toString(),
                "last_play_progress_time" to positionSec.toString(),
                "max_play_progress_time" to positionSec.toString(),
                "outer" to "0",
                "statistics" to buildWebStatistics(),
                "mobi_app" to "web",
                "device" to "web",
                "platform" to "web",
                "cur_language_vt" to "{}",
                "perfer_type" to "{}",
                "play_mode" to if (playType == PLAY_TYPE_START) "1" else "8",
                "spmid" to PLAYER_SPMID,
                "from_spmid" to DEFAULT_FROM_SPMID,
                "session" to session,
                "track_id" to "",
                "extra" to buildPlaybackExtra(),
                "csrf" to csrf
            )
            mid?.let { params["mid"] = it.toString() }
            val queryParams = buildHeartbeatWbiParams(
                aid = aid,
                mid = mid,
                startTimestampSec = startTimestampSec,
                realtimeSec = realtimeSec,
                playedSec = positionSec,
                durationSec = durationSec
            )
            var attempt = 0
            while (attempt < 2) {
                val result = runCatching {
                    sessionGateway.syncAuthState(
                        apiService.playVideoHeartbeatSigned(queryParams, params),
                        source = "player.playVideoHeartbeat"
                    )
                }
                if (result.isSuccess) break
                attempt++
                if (attempt < 2) {
                    delay(2000L)
                } else {
                    AppLog.e(TAG, "reportPlaybackHeartbeat failed after retries: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
                }
            }
        }
    }

    private suspend fun reportPlaybackStartIfNeeded(
        aid: Long,
        cid: Long,
        mid: Long?,
        csrf: String,
        startTimestampSec: Long,
        session: String
    ) {
        if (playbackStartReported || csrf.isBlank()) return
        playbackStartReported = true
        val nowSec = System.currentTimeMillis() / 1000L
        val queryParams = buildClickH5WbiParams(
            aid = aid,
            startTimestampSec = startTimestampSec,
            reportTimestampSec = nowSec
        )
        val params = linkedMapOf(
            "aid" to aid.toString(),
            "cid" to cid.toString(),
            "part" to "1",
            "lv" to (sessionGateway.getUserInfo()?.levelInfo?.currentLevel ?: 0).toString(),
            "ftime" to startTimestampSec.toString(),
            "stime" to nowSec.toString(),
            "type" to "3",
            "sub_type" to "0",
            "refer_url" to buildPlaybackReferUrl(),
            "outer" to "0",
            "statistics" to buildWebStatistics(),
            "mobi_app" to "web",
            "device" to "web",
            "platform" to "web",
            "cur_language" to "",
            "perfer_type" to "",
            "play_mode" to "1",
            "spmid" to PLAYER_SPMID,
            "from_spmid" to DEFAULT_FROM_SPMID,
            "session" to session,
            "track_id" to "",
            "extra" to buildPlaybackExtra(includePlayerVersion = false),
            "csrf" to csrf
        )
        mid?.let { params["mid"] = it.toString() }
        runCatching {
            sessionGateway.syncAuthState(
                apiService.reportVideoClickH5(queryParams, params),
                source = "player.reportVideoClickH5"
            )
        }.onFailure {
            playbackStartReported = false
            AppLog.w(TAG, "reportPlaybackStart failed: ${it.message}")
        }
    }

    private suspend fun buildHeartbeatWbiParams(
        aid: Long,
        mid: Long?,
        startTimestampSec: Long,
        realtimeSec: Long,
        playedSec: Long,
        durationSec: Long
    ): Map<String, String> {
        if (sessionGateway.areWbiKeysStale()) {
            runCatching { sessionGateway.ensureWbiKeys() }
                .onFailure { AppLog.w(TAG, "heartbeat ensureWbiKeys failed: ${it.message}") }
        }
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        val params = linkedMapOf(
            "w_start_ts" to startTimestampSec.toString(),
            "w_aid" to aid.toString(),
            "w_dt" to "2",
            "w_realtime" to realtimeSec.toString(),
            "w_played_time" to playedSec.toString(),
            "w_real_played_time" to playedSec.toString(),
            "w_video_duration" to durationSec.toString(),
            "w_last_play_progress_time" to playedSec.toString(),
            "web_location" to WEB_LOCATION_PLAYER
        )
        mid?.let { params["w_mid"] = it.toString() }
        return WbiGenerator.generateWbiParams(params, imgKey, subKey)
    }

    private suspend fun buildClickH5WbiParams(
        aid: Long,
        startTimestampSec: Long,
        reportTimestampSec: Long
    ): Map<String, String> {
        if (sessionGateway.areWbiKeysStale()) {
            runCatching { sessionGateway.ensureWbiKeys() }
                .onFailure { AppLog.w(TAG, "clickH5 ensureWbiKeys failed: ${it.message}") }
        }
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return WbiGenerator.generateWbiParams(
            linkedMapOf(
                "w_aid" to aid.toString(),
                "w_part" to "1",
                "w_ftime" to startTimestampSec.toString(),
                "w_stime" to reportTimestampSec.toString(),
                "w_type" to "3",
                "web_location" to WEB_LOCATION_PLAYER
            ),
            imgKey,
            subKey
        )
    }

    private fun ensurePlaybackReportSession(): String {
        if (playbackReportSession.isBlank()) {
            playbackReportSession = UUID.randomUUID().toString().replace("-", "")
        }
        return playbackReportSession
    }

    /** 开始一次新的播放会话上报（对应 applyPreparedPlayback 非 replaceInPlace 分支）。 */
    fun beginNewReportSession() {
        sessionStartTimestampMs = System.currentTimeMillis()
        lastReportedHeartbeatPositionSec = -1L
        playbackReportSession = UUID.randomUUID().toString().replace("-", "")
        playbackStartReported = false
    }

    /** 清空上报会话状态（对应切集 reset）。 */
    fun clear() {
        sessionStartTimestampMs = 0L
        lastReportedHeartbeatPositionSec = -1L
        playbackReportSession = ""
        playbackStartReported = false
    }

    private fun buildPlaybackReferUrl(): String {
        val bvid = context.currentBvid?.takeIf { it.isNotBlank() }
        return if (bvid != null) {
            "https://www.bilibili.com/video/$bvid/"
        } else {
            "https://www.bilibili.com/"
        }
    }

    private fun buildWebStatistics(): String {
        return """{"appId":100,"platform":5,"abtest":"","version":""}"""
    }

    private fun buildPlaybackExtra(includePlayerVersion: Boolean = true): String {
        val values = linkedMapOf<String, Any>(
            "play_method" to 2,
            "play_volume" to 1,
            "auto_play" to 0
        )
        if (includePlayerVersion) {
            values["player_version"] = WEB_PLAYER_VERSION
        }
        return gson.toJson(values)
    }
}
