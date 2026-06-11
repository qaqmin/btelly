package com.btelly.feature.player.douyin

import com.btelly.core.common.settings.AppSettingsDataStore
import com.btelly.model.video.VideoModel
import com.btelly.network.api.ApiService

class DouyinModeManager(
    private val appSettings: AppSettingsDataStore,
    private val apiService: ApiService
) {

    companion object {
        const val KEY_DOUYIN_MODE = "douyin_mode"
        const val INITIAL_LOAD_COUNT = 10
        const val APPEND_THRESHOLD = 8
        const val MAX_LIST_SIZE = 20
    }

    private val recommendList = mutableListOf<VideoModel>()
    private var currentIndex: Int = -1
    private var sourceAid: Long = 0L
    private var initialized: Boolean = false
    private var appending: Boolean = false
    private var freshIdx: Int = 0

    /** 读取设置存储，判断抖音模式是否已启用 */
    fun isEnabled(): Boolean {
        return appSettings.getCachedString(KEY_DOUYIN_MODE) == "开"
    }

    /** 检查给定视频是否符合抖音模式条件 */
    fun isApplicable(video: VideoModel?): Boolean {
        if (video == null) return false
        if (!isEnabled()) return false
        if (video.isPgc) return false
        if (video.isLive) return false
        return true
    }

    /** 初始化推荐列表，调用首页推荐 API */
    suspend fun initialize(sourceVideo: VideoModel) {
        if (initialized && sourceAid == sourceVideo.aid) return
        reset()
        sourceAid = sourceVideo.aid
        val items = fetchRecommend(freshIdx, INITIAL_LOAD_COUNT)
        if (items.isEmpty()) return
        recommendList.addAll(items)
        freshIdx++
        currentIndex = 0
        initialized = true
    }

    /** 返回下一个推荐视频，到达末尾返回 null */
    fun next(): VideoModel? {
        if (!initialized || recommendList.isEmpty()) return null
        if (currentIndex >= recommendList.lastIndex) return null
        currentIndex++
        return recommendList[currentIndex]
    }

    /** 预览下一个视频（不移动索引），用于预加载 */
    fun peekNext(): VideoModel? {
        if (!initialized || recommendList.isEmpty()) return null
        val nextIndex = currentIndex + 1
        if (nextIndex > recommendList.lastIndex) return null
        return recommendList[nextIndex]
    }

    /** 返回上一个推荐视频，到达开头返回 null */
    fun previous(): VideoModel? {
        if (!initialized || recommendList.isEmpty()) return null
        if (currentIndex <= 0) return null
        currentIndex--
        return recommendList[currentIndex]
    }

    /** 列表是否已初始化且非空 */
    fun hasList(): Boolean = initialized && recommendList.isNotEmpty()

    /** 当前索引是否达到追加阈值 */
    fun shouldAppend(): Boolean {
        return initialized && !appending && currentIndex >= APPEND_THRESHOLD && recommendList.size < MAX_LIST_SIZE
    }

    /** 重置所有状态 */
    fun reset() {
        recommendList.clear()
        currentIndex = -1
        sourceAid = 0L
        initialized = false
        appending = false
        freshIdx = 0
    }

    /** 追加更多推荐视频，调用首页推荐 API 获取下一页 */
    suspend fun appendMore() {
        if (!initialized || appending) return
        if (recommendList.size >= MAX_LIST_SIZE) return
        appending = true
        try {
            val more = fetchRecommend(freshIdx, INITIAL_LOAD_COUNT)
            if (more.isNotEmpty()) {
                val toAdd = more.take(MAX_LIST_SIZE - recommendList.size)
                recommendList.addAll(toAdd)
                freshIdx++
            }
        } finally {
            appending = false
        }
    }

    private suspend fun fetchRecommend(idx: Int, count: Int): List<VideoModel> {
        return try {
            val response = apiService.getRecommendList(
                freshIdx = idx,
                ps = count
            )
            if (response.isSuccess) {
                response.data?.items?.filter {
                    it.hasPlaybackIdentity && !it.isLive && !it.isPgc
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
