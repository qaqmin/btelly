package com.btelly.feature.marmot

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.btelly.feature.marmot.domain.CloudConfig
import com.btelly.feature.marmot.domain.CloudRes
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 云端资源更新（对标参考 utao `UpdateService.checkOnlineVersion` + `FileUtil.unzipFile`）。
 *
 * 流程：
 * 1. [getConfig] 从 `api.vonchange.com/utao/config/update.json` 拉云端配置（含 res 版本/url）
 * 2. [checkAndUpdateRes] 对比本地 `filesDir/tv-web/update.json` 的 res.version，新版下载 tv-web.zip 解压到 filesDir/tv-web
 * 3. 之后 [MarmotLiveData]/[MarmotJsBridge] 读取时优先 filesDir/tv-web，无则 assets/tv-web
 *
 * 用独立 OkHttpClient（不注入 B 站 cookie/buvid header，避免污染 vonchange 服务器请求）。
 * 必须在 IO 线程调用。
 */
object MarmotCloudUpdate {
    private const val TAG = "MarmotCloudUpdate"
    private const val UPDATE_URL = "http://api.vonchange.com/utao/config/update.json"
    private const val TV_WEB_DIR = "tv-web"
    private const val UPDATE_JSON_NAME = "update.json"
    private val gson = Gson()

    /** 独立 client：不带 B 站 cookie/buvid，纯净请求 vonchange。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 拉云端配置。返回 null 表示失败。 */
    fun getConfig(): CloudConfig? {
        return try {
            val resp = client.newCall(Request.Builder().url(UPDATE_URL).build()).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, CloudConfig::class.java)
        } catch (t: Throwable) {
            Log.w(TAG, "getConfig 失败: ${t.message}")
            null
        }
    }

    /**
     * 检查并更新前端资源（对应参考 `checkOnlineVersion`）。
     * 云端 res.version > 本地 → 下载 zip 解压到 filesDir/tv-web。
     * @return true 表示有更新并已下载。
     */
    fun checkAndUpdateRes(context: Context): Boolean {
        val config = getConfig() ?: return false
        val resNew = config.res ?: return false
        if (!resNew.update) {
            Log.i(TAG, "云端 res.update=false，无需更新")
            return false
        }
        // 读本地 update.json 版本（filesDir/tv-web/update.json）
        val localVersion = readLocalVersion(context)
        if (resNew.version <= localVersion) {
            Log.i(TAG, "本地版本 $localVersion 已最新，云端 ${resNew.version}")
            return false
        }
        Log.i(TAG, "版本更新：$localVersion → ${resNew.version}，下载 ${resNew.url}")
        return downloadAndUnzip(context, resNew.url, resNew.skipFirst ?: false, resNew.version)
    }

    /** 读本地 update.json 的 res.version。无则 0。 */
    private fun readLocalVersion(context: Context): Int {
        val localJson = runCatching {
            File(context.filesDir, "$TV_WEB_DIR/$UPDATE_JSON_NAME").readText()
        }.getOrDefault("")
        if (localJson.isBlank()) return 0
        return try {
            gson.fromJson(localJson, CloudConfig::class.java)?.res?.version ?: 0
        } catch (t: Throwable) { 0 }
    }

    /**
     * 下载 tv-web.zip 并解压到 filesDir/tv-web（对应参考 `FileUtil.unzipFile`）。
     * @param skipFirst true 时跳过 zip 内顶层目录前缀（参考 `Res.skipFirst`）。
     */
    private fun downloadAndUnzip(context: Context, url: String, skipFirst: Boolean, version: Int): Boolean {
        return try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) return false
            val targetDir = File(context.filesDir, TV_WEB_DIR)
            if (!targetDir.exists()) targetDir.mkdirs()
            resp.body?.byteStream()?.use { input ->
                ZipInputStream(input).use { zis ->
                    // skipFirst 时取首个条目的顶层目录前缀，解压时剥除（参考 FileUtil.unzipFile）
                    var firstName: String? = null
                    if (skipFirst) {
                        val first = zis.nextEntry
                        if (first != null) {
                            val idx = first.name.indexOf("/")
                            firstName = if (idx > 0) first.name.substring(0, idx + 1) else first.name
                            zis.closeEntry()
                        }
                    }
                    var entry = zis.nextEntry
                    while (entry != null) {
                        var name = entry.name
                        // 剥除顶层目录前缀
                        if (firstName != null && name.startsWith(firstName)) {
                            name = name.substring(firstName.length)
                        }
                        if (name.isBlank()) { zis.closeEntry(); entry = zis.nextEntry; continue }
                        val outFile = File(targetDir, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buf = ByteArray(8192); var n: Int
                                while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                            }
                        }
                        zis.closeEntry(); entry = zis.nextEntry
                    }
                }
            }
            // 写入新版本号到 update.json
            val updateJson = gson.toJson(CloudConfig(res = CloudRes(version = version)))
            File(targetDir, UPDATE_JSON_NAME).writeText(updateJson)
            Log.i(TAG, "tv-web 更新完成，版本 $version")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "下载解压 tv-web.zip 失败: ${t.message}", t)
            false
        }
    }
}
