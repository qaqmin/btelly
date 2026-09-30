package com.btelly.feature.home

import android.content.Context
import com.btelly.model.video.VideoModel

/**
 * 全站排行榜（rid=0，type=all）。
 *
 * B 站的 ranking/v2 一次返回整个榜单（不翻页），所以这里把 hasMore 恒定为 false，
 * 让基类的加载更多永远不会触发——避免它按"还有更多"去做无效的分页请求。
 */
class RankListViewModel(
    private val repository: RankFeedRepository,
    dislikeFeedback: RecommendDislikeFeedback,
    context: Context
) : BaseVideoFeedViewModel(context, dislikeFeedback) {

    companion object {
        private const val PAGE_SIZE = 100
    }

    override suspend fun fetchPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean,
        fromRefresh: Boolean
    ): Result<FetchedPage> {
        return repository.loadRanking(pageSize = PAGE_SIZE)
            .map { page ->
                FetchedPage(
                    items = filterForDisplay(page.items),
                    // 排行榜一次给全，没有下一页
                    hasMore = false
                )
            }
    }

    override fun errorMessage(throwable: Throwable): String {
        return throwable.message ?: "排行榜加载失败"
    }

    override suspend fun writeCache(items: List<VideoModel>) {
        repository.writeCache(repository.trimCacheItems(items))
    }
}
