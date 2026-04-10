package com.btelly.repository.remote

import com.btelly.model.BaseResponse
import com.btelly.model.user.UserSpaceInfo
import com.btelly.model.user.UserDetailInfoModel
import com.btelly.model.user.UserStatModel
import com.btelly.model.recommend.RecommendListDataModel
import com.btelly.model.video.VideoModel
import com.btelly.network.NetworkManager
import com.btelly.network.api.ApiService
import com.btelly.network.response.BaseBaseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository(private val apiService: ApiService = NetworkManager.apiService) {

    suspend fun getUserDetailInfo(): Result<BaseResponse<UserDetailInfoModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = apiService.getUserDetailInfo()
            NetworkManager.syncUserSession(response, source = "remoteUserRepository.getUserDetailInfo")
            response
        }
    }

    suspend fun getUserStat(): Result<BaseResponse<UserStatModel>> = withContext(Dispatchers.IO) {
        runCatching {
            NetworkManager.syncAuthState(
                apiService.getUserStat(),
                source = "remoteUserRepository.getUserStat"
            )
        }
    }

    suspend fun getUserSpace(params: Map<String, String>): Result<BaseResponse<UserSpaceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            NetworkManager.syncAuthState(
                apiService.getUserSpace(params),
                source = "remoteUserRepository.getUserSpace"
            )
        }
    }

    suspend fun getUserVideos(@Suppress("UNUSED_PARAMETER") mid: Long, page: Int, pageSize: Int = 20): Result<BaseResponse<RecommendListDataModel<VideoModel>>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.getRecommendList(
                freshIdx = page,
                ps = pageSize,
                freshType = page
            )
        }
    }

    suspend fun modifyRelation(fid: Long, action: Int): Result<BaseBaseResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val params = mapOf(
                "fid" to fid.toString(),
                "act" to action.toString(),
                "csrf" to NetworkManager.getCsrfToken()
            )
            NetworkManager.syncAuthState(
                apiService.userRelationModify(params),
                source = "remoteUserRepository.modifyRelation"
            )
        }
    }
}
