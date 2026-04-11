package com.btelly.repository

import com.btelly.model.BaseResponse
import com.btelly.model.common.CheckGiveCoinModel
import com.btelly.model.common.GiveCoinResultModel
import com.btelly.model.common.TripleActionResultModel
import com.btelly.model.recommend.RecommendListDataModel
import com.btelly.model.video.VideoModel
import com.btelly.model.video.detail.VideoDetailModel
import com.btelly.network.NetworkManager
import com.btelly.repository.remote.VideoRepository as NetworkVideoRepository

class VideoRepository(
    private val delegate: NetworkVideoRepository
) {

    suspend fun getRecommendList(
        freshIdx: Int,
        pageSize: Int
    ): BaseResponse<RecommendListDataModel<VideoModel>> {
        return delegate.getRecommendList(freshIdx, pageSize).getOrThrow()
    }

    suspend fun getHotList(page: Int, pageSize: Int): BaseResponse<List<VideoModel>> {
        return delegate.getHotList(page, pageSize).getOrThrow()
    }

    suspend fun getRanking(rid: Int): BaseResponse<List<VideoModel>> {
        return delegate.getRanking(rid).getOrThrow()
    }

    suspend fun getVideoDetail(avid: Long?, bvid: String?): BaseResponse<VideoDetailModel> {
        return delegate.getVideoDetail(avid, bvid).getOrThrow()
    }

    suspend fun like(avid: Long?, bvid: String?, like: Int): BaseResponse<Int> {
        val csrf = NetworkManager.getCsrfToken()
        return delegate.like(avid, bvid, like, csrf).getOrThrow()
    }

    suspend fun hasLike(avid: Long?, bvid: String?): BaseResponse<Int> {
        return delegate.hasLike(avid, bvid).getOrThrow()
    }

    suspend fun giveCoin(
        avid: Long?,
        bvid: String?,
        multiply: Int,
        selectLike: Int = 0
    ): BaseResponse<GiveCoinResultModel> {
        val csrf = NetworkManager.getCsrfToken()
        return delegate.giveCoin(avid, bvid, multiply, selectLike, csrf).getOrThrow()
    }

    suspend fun hasGiveCoin(avid: Long?, bvid: String?): BaseResponse<CheckGiveCoinModel> {
        return delegate.hasGiveCoin(avid, bvid).getOrThrow()
    }

    suspend fun tripleAction(avid: Long?, bvid: String?): BaseResponse<TripleActionResultModel> {
        val csrf = NetworkManager.getCsrfToken()
        return delegate.tripleAction(avid, bvid, csrf).getOrThrow()
    }
}
