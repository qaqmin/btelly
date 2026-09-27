package com.btelly.core.common.log

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.btelly.BuildConfig
import com.btelly.core.common.BrandGene
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object AppLog {

    private const val PREFS_NAME = "app_log_prefs"
    private const val KEY_ENABLED = "log_enabled"
    private const val CRASH_LOG_FILE = "debug.txt"

    // @Volatile：开关在主线程翻转，action/cache 等后台线程依赖其可见性做热路径门控。
    @Volatile
    var isEnabled = false
        private set

    private lateinit var prefs: SharedPreferences
    private lateinit var crashLogFile: File

    data class LogEntry(
        val timestamp: Long,
        val level: Int,
        val tag: String,
        val message: String
    ) {
        companion object {
            const val LEVEL_VERBOSE = 0
            const val LEVEL_DEBUG = 1
            const val LEVEL_INFO = 2
            const val LEVEL_WARN = 3
            const val LEVEL_ERROR = 4
        }
    }

    object LogBuffer {
        private const val MAX_SIZE = 2000
        private val buffer = ArrayDeque<LogEntry>()

        fun add(entry: LogEntry) {
            synchronized(buffer) {
                buffer.addLast(entry)
                while (buffer.size > MAX_SIZE) {
                    buffer.removeFirst()
                }
            }
        }

        fun getLogs(): List<LogEntry> {
            synchronized(buffer) {
                return buffer.toList()
            }
        }

        fun clear() {
            synchronized(buffer) {
                buffer.clear()
            }
        }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_ENABLED, false)
        crashLogFile = File(context.filesDir, CRASH_LOG_FILE)
        installCrashHandler()
        // 基因哨兵：运行期日志横幅（logcat 可见，反编译可查），不随改名/重构消失。
        // 注意：release 的 proguard 规则剥掉了 Log.v/d/i，这里必须用未声明的 Log.w 才能活过 R8。
        Log.w(
            "BrandGene",
            "${BrandGene.SIGNATURE} | ${BrandGene.WATERMARK} | ${BrandGene.BUILD_FIELD_TAG}"
        )
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    fun getCrashLog(): String? {
        if (!::crashLogFile.isInitialized) return null
        if (!crashLogFile.exists()) return null
        return runCatching { crashLogFile.readText() }.getOrNull()
    }

    fun clearCrashLog() {
        if (!::crashLogFile.isInitialized) return
        runCatching { crashLogFile.delete() }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val time = timeFormat.format(System.currentTimeMillis())
                val stackTrace = Log.getStackTraceString(throwable)
                val content = buildString {
                    append("=== CRASH ===\n")
                    append("Time: $time\n")
                    append("Thread: ${thread.name}\n")
                    append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                    append("Stack:\n")
                    append(stackTrace)
                    append("\n")
                }
                // 写入 UTF-8 BOM，避免 Windows 记事本等编辑器无 BOM 时误判为 GBK 导致中文乱码
                crashLogFile.outputStream().buffered().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }
            defaultHandler.uncaughtException(thread, throwable)
        }
    }

    private fun logToBuffer(level: Int, tag: String, message: String) {
        LogBuffer.add(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    fun d(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_DEBUG, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.d(tag, message) }
    }

    /**
     * 诊断专用：release 下也强制进 logcat（给 adb logcat 取证用）。
     * proguard-rules.pro 的 -assumenosideeffects 剥掉了 Log.v/d/i，只有 Log.w/e 能活过 R8，
     * 因此这里必须走 Log.w（同 BrandGene 哨兵的做法），否则 release 包 adb logcat 什么都看不到。
     *
     * 双通道、双门控：
     * - logcat 输出**不受 isEnabled 门控**——该开关只能从「设置→通用→日志记录」翻，
     *   远程取证时根本够不着它，而这条路的全部意义就是让 adb logcat 拿到时间戳证据。
     * - 缓冲区（调试日志界面）则照常受 isEnabled 门控——否则用户在设置里关掉「日志记录」，
     *   调试日志界面仍被 CtrlBar 条目刷满，与开关语义相反，还会挤掉用户自己的条目
     *   （LogBuffer 上限 2000 条）。
     * 调用方仅限控制栏显隐这类低频关键路径，量很小。
     */
    fun dLogcat(tag: String, message: String) {
        if (isEnabled) {
            logToBuffer(LogEntry.LEVEL_DEBUG, tag, message)
        }
        runCatching { Log.w(tag, message) }
    }

    fun i(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_INFO, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.i(tag, message) }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_ERROR, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.e(tag, message, throwable) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_WARN, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.w(tag, message, throwable) }
    }

    fun v(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_VERBOSE, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.v(tag, message) }
    }
}
