package com.btelly.network.api

import android.os.SystemClock
import com.btelly.model.live.LiveListWrapper
import com.btelly.model.video.VideoModel
import com.btelly.network.BiliClient
import com.btelly.core.common.log.AppLog
import org.json.JSONObject

object BiliApi {

    private const val TAG = "BiliApi"

    suspend fun recommend(freshIdx: Int, ps: Int): List<VideoModel> {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "x/web-interface/index/top/feed/rcmd",
            params = mapOf(
                "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
                "ps" to ps.toString(),
                "feed_version" to "V1",
                "fresh_type" to "3",
                "plat" to "1"
            )
        )
        AppLog.i(TAG, "recommend request url=$url")
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "recommend")
        val data = json.optJSONObject("data")
        if (data == null) {
            AppLog.w(TAG, "recommend data is null, raw=${json.toString().take(200)}")
            return emptyList()
        }
        val items = data.optJSONArray("items") ?: data.optJSONArray("item")
        if (items == null) {
            AppLog.w(TAG, "recommend items is null, data keys=${data.keys().asSequence().toList()}")
            return emptyList()
        }
        AppLog.i(TAG, "recommend items count=${items.length()}")
        val result = (0 until items.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(items.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "recommend parse item failed: ${it.message}") }
                .getOrNull()
        }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend total=${t2 - t0}ms parse=${t2 - t1}ms result=${result.size}")
        return result
    }

    suspend fun hotList(pn: Int, ps: Int): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/popular",
            params = mapOf("pn" to pn.toString(), "ps" to ps.toString())
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "hotList")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "hotList parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun ranking(rid: Int, type: String = "all"): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/ranking/v2",
            params = mapOf("rid" to rid.toString(), "type" to type)
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "ranking")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "ranking parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun related(aid: Long?, bvid: String?): List<VideoModel> {
        val params = mutableMapOf<String, String>()
        aid?.let { params["avid"] = it.toString() }
        bvid?.let { params["bvid"] = it }
        val url = BiliClient.buildUrl("x/web-interface/archive/related", params)
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "related")
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(data.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "related parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun liveHomeList(): LiveListWrapper {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList",
            params = mapOf("platform" to "web")
        )
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        BiliClient.checkResponse(json, "liveHomeList")
        val data = json.optJSONObject("data")
        val result = if (data != null) LiveListWrapper.fromJson(data) else LiveListWrapper()
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "liveHomeList net=${t1 - t0}ms parse=${t2 - t1}ms total=${t2 - t0}ms")
        return result
    }
}
