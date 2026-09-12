package com.btelly.feature.cctv

import com.btelly.core.common.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CctvProgramRepository(
    private val okHttpClient: OkHttpClient
) {

    fun fetchNowPrograms(channels: List<CctvChannel>): Map<String, String> {
        val channelIds = channels.joinToString(",") { it.id }
        if (channelIds.isBlank()) return emptyMap()
        val day = DAY_FORMAT.format(Date())
        val url = "https://api.cntv.cn/epg/getEpgInfoByChannelNew" +
            "?c=$channelIds&serviceId=tvcctv&d=$day&t=jsonp&cb=$CALLBACK_NAME"
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://tv.cctv.com/epg/index.shtml")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.e(TAG, "nowEpgHttpError code=${response.code}")
                    return@use emptyMap()
                }
                parseNowPrograms(response.body?.string().orEmpty())
            }
        }.getOrElse { error ->
            AppLog.e(TAG, "nowEpgFetchError ${error.message}")
            emptyMap()
        }
    }

    private fun parseNowPrograms(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val payload = unwrapJsonp(json).trim()
        if (!payload.startsWith("{")) return result
        val data = JSONObject(payload).optJSONObject("data") ?: return result
        val keys = data.keys()
        while (keys.hasNext()) {
            val channelId = keys.next()
            val item = data.optJSONObject(channelId) ?: continue
            val title = item.optString("isLive").trim()
            if (title.isNotBlank()) {
                result[channelId] = title
            }
        }
        return result
    }

    private fun unwrapJsonp(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("$CALLBACK_NAME(") || !trimmed.endsWith(");")) {
            return trimmed
        }
        return trimmed
            .removePrefix("$CALLBACK_NAME(")
            .removeSuffix(");")
    }

    private companion object {
        private const val TAG = "CctvProgramRepository"
        private const val CALLBACK_NAME = "setItem1"
        private val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV; B视机) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
    }
}
