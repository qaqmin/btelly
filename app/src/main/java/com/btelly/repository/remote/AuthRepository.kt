package com.btelly.repository.remote

import com.btelly.model.BaseResponse
import com.btelly.model.user.ScanQrModel
import com.btelly.model.user.SignInResultModel
import com.btelly.model.user.SsoListModel
import com.btelly.network.api.ApiService
import com.btelly.network.session.NetworkSessionGateway

class AuthRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun getQrCode(): Result<BaseResponse<ScanQrModel>> =
        runCatching {
            apiService.getSignInQrCode()
        }

    suspend fun checkSignInResult(qrcodeKey: String, bRet: String): Result<BaseResponse<SignInResultModel>> =
        runCatching {
            apiService.checkSignInResult(qrcodeKey, bRet = bRet)
        }

    suspend fun getSsoList(csrf: String): Result<BaseResponse<SsoListModel>> =
        runCatching {
            apiService.getSsoList(csrf)
        }

    suspend fun setSso(url: String, bRet: String): Result<BaseResponse<Any>> =
        runCatching {
            apiService.setSso(url, bRet = bRet)
        }
}
