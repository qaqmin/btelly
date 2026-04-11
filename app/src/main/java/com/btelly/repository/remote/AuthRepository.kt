package com.btelly.repository.remote

import com.btelly.model.BaseResponse
import com.btelly.model.user.ScanQrModel
import com.btelly.model.user.SignInResultModel
import com.btelly.network.api.ApiService
import com.btelly.network.session.NetworkSessionGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun getQrCode(): Result<BaseResponse<ScanQrModel>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.getSignInQrCode()
        }
    }

    suspend fun checkSignInResult(qrcodeKey: String): Result<BaseResponse<SignInResultModel>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.checkSignInResult(qrcodeKey)
        }
    }

    fun getCsrfToken(): String = sessionGateway.getCsrfToken()

    fun isLoggedIn(): Boolean = sessionGateway.isLoggedIn()
}
