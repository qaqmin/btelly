@file:Suppress("SpellCheckingInspection")

package com.btelly.core.common.update

import com.btelly.core.common.format.NumberUtils
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.btelly.BuildConfig
import com.btelly.core.common.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

object ApkUpdater {

    /** 更新检测通道：GitHub 主渠道 + Codeberg 镜像渠道，双路并发、最新版优先。 */
    enum class UpdateChannel(val label: String) {
        GITHUB("GitHub"),
        CODEBERG("Codeberg"),
    }

    private const val REPO_OWNER = "min09577"
    private const val REPO_NAME = "btelly"
    private val CHANNEL_RELEASE_API: Map<UpdateChannel, String> = mapOf(
        UpdateChannel.GITHUB to
            "https://api.github.com/repos/qaqmin09577/btelly/releases/latest",
        UpdateChannel.CODEBERG to
            "https://codeberg.org/api/v1/repos/$REPO_OWNER/$REPO_NAME/releases/latest",
    )

    private const val COOLDOWN_MS = 5_000L
    private const val TAG = "ApkUpdater"
    private const val UPDATE_DIR_NAME = "update"

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .dns(IPv4OnlyDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private object IPv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            if (ipv4.isNotEmpty()) return ipv4
            throw UnknownHostException("No IPv4 address for $hostname")
        }
    }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val left = (lastStartedAtMs + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String,
        val channel: UpdateChannel,
    )

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${NumberUtils.formatBytes(downloadedBytes)} / ${NumberUtils.formatBytes(totalBytes)}")
                    } else {
                        append(NumberUtils.formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${NumberUtils.formatBytes(bytesPerSecond)}/s）")
                }
        }

        data object Done : Progress()

        data class Retrying(val attempt: Int, val maxAttempts: Int) : Progress()
    }

    suspend fun fetchLatestRelease(): ReleaseInfo {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestReleaseOnce()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay((400L * attempt).milliseconds)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest release failed")
        }
    }

    /**
     * 双通道并发检测：GitHub 与 Codeberg 同时查询，返回版本较新的一方；
     * 版本相同（或都不可解析）时主渠道 GitHub 优先。单通道失败不影响另一通道，
     * 全部失败才抛错，由外层重试兜底。
     */
    private fun fetchLatestReleaseOnce(): ReleaseInfo {
        val errors = mutableListOf<Throwable>()
        val releases = UpdateChannel.entries.mapNotNull { channel ->
            try {
                fetchChannelReleaseOnce(channel)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errors += t
                null
            }
        }
        if (releases.isEmpty()) {
            throw errors.firstOrNull() ?: IllegalStateException("no update channel responded")
        }
        return releases.reduce { current, candidate ->
            if (compareRelease(current, candidate) >= 0) current else candidate
        }
    }

    /** 版本新者胜；版本不可解析或相同时，信道枚举序在前的（GITHUB）胜出。 */
    private fun compareRelease(a: ReleaseInfo, b: ReleaseInfo): Int {
        val va = parseVersion(a.versionName)
        val vb = parseVersion(b.versionName)
        if (va != null && vb != null) {
            val byVersion = compareVersion(va, vb)
            if (byVersion != 0) return byVersion
        }
        return b.channel.ordinal.compareTo(a.channel.ordinal)
    }

    private fun fetchChannelReleaseOnce(channel: UpdateChannel): ReleaseInfo {
        val req = Request.Builder()
            .url(CHANNEL_RELEASE_API.getValue(channel))
            .header("Accept", "application/json")
            // GitHub API 强制要求 User-Agent，Codeberg（Forgejo）同样接受
            .header("User-Agent", "BTelly-Updater")
            .get()
            .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val json = JSONObject(body.string())

            val tagName = json.optString("tag_name", "").trim().removePrefix("v")
            check(tagName.isNotBlank()) { "Release tag_name 为空" }
            check(parseVersion(tagName) != null) { "版本号格式不正确：$tagName" }

            val releaseNotes = json.optString("body", "").trim()

            val assets = json.optJSONArray("assets") ?: error("没有找到附件")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            check(apkUrl.isNotBlank()) { "Release 中没有找到 APK 文件" }

            return ReleaseInfo(
                versionName = tagName,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
                channel = channel,
            )
        }
    }

    fun isRemoteNewer(
        remoteVersionName: String,
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName)
            ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    /**
     * 启动时清理上一次会话遗留的安装包。下载→拉起安装器都在同一次会话内完成，
     * 没有任何跨进程复用残留包的路径，残留在磁盘上只会白占缓存空间（几十 MB），
     * 因此启动时整目录删除即可；下次下载会在同目录重建，不影响更新流程。
     */
    fun cleanupStaleDownload(context: Context) {
        Thread {
            val dir = File(context.applicationContext.cacheDir, UPDATE_DIR_NAME)
            if (!dir.exists()) return@Thread
            val deleted = dir.deleteRecursively()
            AppLog.i(TAG, "cleanupStaleDownload dir=$dir deleted=$deleted")
        }.apply { isDaemon = true }.start()
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        var lastError: Throwable? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (attempt == 1) onProgress(Progress.Connecting)
            try {
                return downloadApkToCacheOnce(context, url, onProgress)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                if (attempt < maxAttempts) {
                    onProgress(Progress.Retrying(attempt, maxAttempts))
                    delay((1_500L * attempt).milliseconds)
                }
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private suspend fun downloadApkToCacheOnce(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        val dir = File(context.cacheDir, UPDATE_DIR_NAME).apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond =
                                    (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(
                                    Progress.Downloading(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = bytesPerSecond,
                                    )
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly = cleaned.takeWhile { ch -> ch.isDigit() || ch == '.' }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
