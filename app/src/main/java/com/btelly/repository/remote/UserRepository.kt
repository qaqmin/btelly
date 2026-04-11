package com.btelly.repository.remote

import com.btelly.model.BaseResponse
import com.btelly.model.user.UserSpaceInfo
import com.btelly.model.user.UserDetailInfoModel
import com.btelly.model.user.UserStatModel
import com.btelly.model.recommend.RecommendListDataModel
import com.btelly.model.video.VideoModel
import com.btelly.network.api.ApiService
import com.btelly.network.session.NetworkSessionGateway
import com.btelly.network.response.BaseBaseResponse

class UserRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun getUserDetailInfo(): Result<BaseResponse<UserDetailInfoModel>> =
        runCatching {
            val response = apiService.getUserDetailInfo()
            sessionGateway.syncUserSession(response, source = "remoteUserRepository.getUserDetailInfo")
            response
        }

    suspend fun getUserStat(): Result<BaseResponse<UserStatModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getUserStat(),
                source = "remoteUserRepository.getUserStat"
            )
        }

    suspend fun getUserSpace(params: Map<String, String>): Result<BaseResponse<UserSpaceInfo>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getUserSpace(params),
                source = "remoteUserRepository.getUserSpace"
            )
        }

    suspend fun getUserVideos(@Suppress("UNUSED_PARAMETER") mid: Long, page: Int, pageSize: Int = 20): Result<BaseResponse<RecommendListDataModel<VideoModel>>> =
        runCatching {
            apiService.getRecommendList(
                freshIdx = page,
                ps = pageSize,
                freshType = page
            )
        }

    suspend fun modifyRelation(fid: Long, action: Int): Result<BaseBaseResponse> =
        runCatching {
            val params = mapOf(
                "fid" to fid.toString(),
                "act" to action.toString(),
                "csrf" to sessionGateway.getCsrfToken()
            )
            sessionGateway.syncAuthState(
                apiService.userRelationModify(params),
                source = "remoteUserRepository.modifyRelation"
            )
        }
}
