package com.btelly.feature.marmot

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.btelly.feature.marmot.domain.MarmotModels.DataWrapper
import com.btelly.feature.marmot.domain.MarmotModels.Live
import com.btelly.feature.marmot.domain.MarmotModels.Vod
import java.io.File

/**
 * 频道数据管理与切台导航（对标参考 utao `UpdateService.initTvData` + `liveNext` + `FileUtil.readExt`）。
 *
 * - [load] 读取 tv-web 频道表（tv2.json），构建 index/tag/url 三个导航 Map。
 * - [liveNext] 四向环形切台（up/down/left/right）。
 * - [readFileWithFallback] 读取 tv-web 资源：优先 filesDir/tv-web（云端更新后），无则 assets/tv-web（内置种子）。
 *
 * 必须在 IO 线程调用 [load]。
 */
object MarmotLiveData {
    private const val TAG = "MarmotLiveData"
    private const val TV_JSON_PATH = "tv-web/js/cctv/tv2.json"
    private val gson = Gson()

    /** key("tagIndex_detailIndex") → Vod */
    private val indexVodMap = HashMap<String, Vod>()
    /** tagIndex → 该分组最大 detailIndex */
    private val tagMaxMap = HashMap<Int, Int>()
    /** url → key */
    private val urlKeyMap = HashMap<String, String>()
    /** 全部分组 */
    private val lives = ArrayList<Live>()

    @Volatile
    private var loaded = false

    /**
     * 加载 tv.json 频道表并构建导航 Map。线程安全，重复调用只加载一次。
     * @return true 加载成功。
     */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loaded) return true
        return try {
            val json = readFileWithFallback(context, TV_JSON_PATH)
            if (json.isBlank()) {
                Log.e(TAG, "频道表内容为空: $TV_JSON_PATH")
                return false
            }
            val type = object : TypeToken<DataWrapper<Live>>() {}.type
            val wrapper: DataWrapper<Live> = gson.fromJson(json, type)
            val list = wrapper.data
            if (list.isNullOrEmpty()) {
                Log.e(TAG, "频道表 data 为空")
                return false
            }
            indexVodMap.clear(); tagMaxMap.clear(); urlKeyMap.clear(); lives.clear()
            var i = 0
            for (life in list) {
                var j = 0
                for (vod in life.vods) {
                    vod.tagIndex = i
                    vod.detailIndex = j
                    val key = "${i}_$j"
                    vod.key = key
                    indexVodMap[key] = vod
                    urlKeyMap[vod.url] = key
                    j++
                }
                tagMaxMap[i] = j - 1
                lives.add(life)
                i++
            }
            loaded = true
            Log.i(TAG, "已加载 ${lives.size} 个分组，${indexVodMap.size} 个频道")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "加载频道表失败", t)
            false
        }
    }

    /** 全部分组（含 vods）。加载前调用返回空列表。 */
    fun getLives(): List<Live> = lives

    /** 按 key 取频道。 */
    fun getByKey(key: String): Vod? = indexVodMap[key]

    /** 按 url 取频道。 */
    fun getByUrl(url: String): Vod? = urlKeyMap[url]?.let { indexVodMap[it] }

    /**
     * 计算下一频道 key（对应参考 `UpdateService.liveNext`）。
     * @param tagIndexNow 当前分组索引
     * @param detailIndexNow 当前频道索引
     * @param direction up/down/left/right
     */
    fun liveNext(tagIndexNow: Int, detailIndexNow: Int, direction: String): String {
        return when (direction) {
            "up" -> {
                if (detailIndexNow == 0) "${tagIndexNow}_${tagMaxMap[tagIndexNow] ?: 0}"
                else "${tagIndexNow}_${detailIndexNow - 1}"
            }
            "down" -> {
                val max = tagMaxMap[tagIndexNow] ?: 0
                if (detailIndexNow == max) "${tagIndexNow}_0"
                else "${tagIndexNow}_${detailIndexNow + 1}"
            }
            "left" -> {
                if (tagIndexNow == 0) "${(tagMaxMap.size - 1)}_0"
                else "${tagIndexNow - 1}_0"
            }
            "right" -> {
                if (tagIndexNow == tagMaxMap.size - 1) "0_0"
                else "${tagIndexNow + 1}_0"
            }
            else -> "0_0"
        }
    }

    /**
     * 读取 tv-web 资源：优先 filesDir/tv-web（云端更新后的），无则 assets/tv-web（内置种子）。
     * 对应参考 `FileUtil.readExt` 的优先级（filesDir → assets 兜底）。
     */
    fun readFileWithFallback(context: Context, path: String): String {
        // 1. filesDir/<path>（云端更新写入的位置）
        val localFile = File(context.filesDir, path)
        if (localFile.exists() && localFile.length() > 0) {
            return runCatching { localFile.readText() }.getOrDefault("")
        }
        // 2. assets/<path>（内置种子）
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) { "" }
    }

    /** 读取 tv-web 资源为 InputStream（用于 shouldInterceptRequest 返回二进制资源）。 */
    fun readStreamWithFallback(context: Context, path: String): java.io.InputStream? {
        val localFile = File(context.filesDir, path)
        if (localFile.exists() && localFile.length() > 0) {
            return runCatching { java.io.FileInputStream(localFile) }.getOrNull()
        }
        return try {
            context.assets.open(path)
        } catch (t: Throwable) { null }
    }
}
