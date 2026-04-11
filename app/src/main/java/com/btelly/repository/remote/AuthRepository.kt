package com.btelly.repository.remote

import com.btelly.model.BaseResponse
import com.btelly.model.user.ScanQrModel
import com.btelly.model.user.SignInResultModel
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

    suspend fun checkSignInResult(qrcodeKey: String): Result<BaseResponse<SignInResultModel>> =
        runCatching {
            apiService.checkSignInResult(qrcodeKey)
        }

    fun getCsrfToken(): String = sessionGateway.getCsrfToken()

    fun isLoggedIn(): Boolean = sessionGateway.isLoggedIn()
}
