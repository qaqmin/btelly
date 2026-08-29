package com.btelly.feature.home

import android.content.Context
import com.btelly.model.video.VideoModel

class HotViewModel(
    private val repository: HotFeedRepository,
    dislikeFeedback: RecommendDislikeFeedback,
    context: Context
) : BaseVideoFeedViewModel(context, dislikeFeedback) {

    companion object {
        private const val PAGE_SIZE = 24
    }

    override suspend fun fetchPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean,
        fromRefresh: Boolean
    ): Result<FetchedPage> {
        return repository.loadNetworkPage(page = page, pageSize = PAGE_SIZE)
            .map { result ->
                FetchedPage(
                    items = filterForDisplay(result.items),
                    hasMore = result.hasMore
                )
            }
    }

    override fun errorMessage(throwable: Throwable): String {
        return throwable.message ?: "热门加载失败"
    }

    override suspend fun writeCache(items: List<VideoModel>) {
        repository.writeCache(repository.trimCacheItems(items))
    }
}
