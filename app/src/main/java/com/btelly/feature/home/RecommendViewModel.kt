package com.btelly.feature.home

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btelly.core.common.content.ContentFilter
import com.btelly.core.common.log.AppLog
import com.btelly.model.video.VideoModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecommendViewModel(
    private val repository: RecommendFeedRepository,
    private val dislikeFeedback: RecommendDislikeFeedback,
    context: Context
) : ViewModel(), VideoFeedViewModel {

    companion object {
        private const val TAG = "RecommendVM"
        private const val FIRST_PAGE_SIZE = 24
        private const val NEXT_PAGE_SIZE = 24
    }

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(FeedUiState<VideoModel>())
    override val uiState: StateFlow<FeedUiState<VideoModel>> = _uiState.asStateFlow()

    private var currentPage = 0
    private var nextRecommendFetchRow = 1
    private var hasLoadedInitial = false
    private val seenVideoIds = mutableSetOf<String>()

    override fun loadInitial() {
        if (hasLoadedInitial) return
        hasLoadedInitial = true
        AppLog.i(TAG, "STARTUP T5 viewModel.loadInitial")
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            loadPage(page = 1, replace = true, fromInitial = true)
        }
    }

    override fun refresh() {
        viewModelScope.launch {
            loadPage(page = 1, replace = true, fromRefresh = true)
        }
    }

    override fun loadMore() {
        val state = _uiState.value
        if (state.loadingInitial || state.refreshing || state.appending || !state.hasMore) {
            return
        }
        val nextPage = (currentPage + 1).coerceAtLeast(1)
        viewModelScope.launch {
            loadPage(page = nextPage, replace = false)
        }
    }

    override fun consumeListChange() {
        val state = _uiState.value
        if (state.listChange != FeedListChange.NONE) {
            _uiState.value = state.copy(listChange = FeedListChange.NONE)
        }
    }

    private suspend fun loadPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean = false,
        fromRefresh: Boolean = false
    ) {
        val current = _uiState.value
        if (page == 1 && replace && fromInitial) {
            _uiState.value = current.copy(
                loadingInitial = true,
                refreshing = false,
                appending = false,
                errorMessage = null,
                listChange = FeedListChange.NONE
            )
            val sharedStart = SystemClock.elapsedRealtime()
            runCatching {
                repository.loadSharedFirstPage(pageSize = FIRST_PAGE_SIZE, reason = "viewModelInitial")
            }.onSuccess { firstPage ->
                AppLog.i(
                    TAG,
                    "STARTUP T6 sharedFirstPage hit elapsed=${SystemClock.elapsedRealtime() - sharedStart}ms items=${firstPage.items.size}"
                )
                seenVideoIds.clear()
                val filterStart = SystemClock.elapsedRealtime()
                val filteredItems = firstPage.items.filterForDisplay()
                AppLog.i(TAG, "STARTUP sharedFirstPage filterForInitial=${SystemClock.elapsedRealtime() - filterStart}ms")
                filteredItems.mapNotNullTo(seenVideoIds) { it.bvid.takeIf(String::isNotBlank) }
                currentPage = 1
                nextRecommendFetchRow = nextFetchRowAfter(firstPage)
                _uiState.value = FeedUiState(
                    items = filteredItems,
                    source = FeedSource.NETWORK,
                    listChange = FeedListChange.REPLACE,
                    hasMore = firstPage.hasMore
                )
                if (filteredItems.isNotEmpty()) {
                    repository.writeCache(repository.trimCacheItems(filteredItems))
                }
            }.onFailure { throwable ->
                _uiState.value = current.copy(
                    loadingInitial = false,
                    refreshing = false,
                    appending = false,
                    errorMessage = throwable.message ?: "推荐加载失败",
                    listChange = FeedListChange.NONE
                )
                AppLog.w(TAG, "STARTUP T6 sharedFirstPage failed: ${throwable.message}")
            }
            return
        }

        _uiState.value = current.copy(
            loadingInitial = fromInitial,
            refreshing = fromRefresh,
            appending = !replace,
            errorMessage = null,
            listChange = FeedListChange.NONE
        )

        val freshIdx = page.coerceAtLeast(1)
        val fetchRow = if (page == 1 && replace) {
            1
        } else {
            nextRecommendFetchRow.coerceAtLeast(1)
        }
        val pageSize = if (page == 1) FIRST_PAGE_SIZE else NEXT_PAGE_SIZE
        repository.loadNetworkPage(
            page = page,
            pageSize = pageSize,
            freshIdx = freshIdx,
            fetchRow = fetchRow
        ).onSuccess { pageResult ->
            val filterStart = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP T7 network page=$page freshIdx=$freshIdx fetchRow=$fetchRow source=${pageResult.source} raw=${pageResult.rawCount} ready items=${pageResult.items.size}")
            if (page == 1 && replace) {
                // 同步内存预加载首页，避免下次 loadInitial（切 tab 重建）回退到旧数据
                repository.updatePreloadedFirstPage(pageResult)
            }
            val filteredItems = pageResult.items.filterForDisplay()
            if (replace) {
                seenVideoIds.clear()
            }
            val dedupedItems = filteredItems.filter { it.bvid.isBlank() || it.bvid !in seenVideoIds }
            dedupedItems.mapNotNullTo(seenVideoIds) { it.bvid.takeIf(String::isNotBlank) }
            val mergedItems = if (replace) {
                dedupedItems
            } else {
                current.items + dedupedItems
            }
            currentPage = page
            nextRecommendFetchRow = nextFetchRowAfter(pageResult)
            _uiState.value = FeedUiState(
                items = mergedItems,
                source = FeedSource.NETWORK,
                listChange = if (replace) FeedListChange.REPLACE else FeedListChange.APPEND,
                hasMore = pageResult.hasMore
            )
            AppLog.i(TAG, "STARTUP network page=$page filterDedup=${SystemClock.elapsedRealtime() - filterStart}ms final=${mergedItems.size}")
            if (mergedItems.isNotEmpty()) {
                repository.writeCache(repository.trimCacheItems(mergedItems))
            }
        }.onFailure { throwable ->
            _uiState.value = current.copy(
                loadingInitial = false,
                refreshing = false,
                appending = false,
                errorMessage = throwable.message ?: "推荐加载失败",
                listChange = FeedListChange.NONE
            )
        }
    }

    private fun nextFetchRowAfter(page: RecommendFeedRepository.NetworkPage): Int {
        val step = page.rawCount.coerceAtLeast(page.items.size).coerceAtLeast(1)
        return page.requestFetchRow + step
    }

    private suspend fun List<VideoModel>.filterForDisplay(): List<VideoModel> {
        val original = this@filterForDisplay
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val kept = ContentFilter.filterVideos(appContext, original)
            // 闭环：被本地过滤掉的视频异步反馈 dislike，让 B 站算法减少同类推送
            if (kept.size < original.size) {
                val blockedKeys = kept.asSequence()
                    .mapNotNull { it.bvid.takeIf(String::isNotBlank) }
                    .toMutableSet()
                val blocked = original.filter { v -> v.bvid.isBlank() || v.bvid !in blockedKeys }
                if (blocked.isNotEmpty()) dislikeFeedback.feedbackBlocked(blocked)
            }
            kept
        }
    }
}
