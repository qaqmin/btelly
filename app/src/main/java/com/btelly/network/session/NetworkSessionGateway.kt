package com.btelly.network.session

import com.btelly.model.BaseResponse
import com.btelly.model.user.UserDetailInfoModel
import com.btelly.network.NetworkManager
import com.btelly.network.response.Base2Response
import com.btelly.network.response.BaseBaseResponse

interface NetworkSessionGateway {
    fun getCsrfToken(): String

    fun isLoggedIn(): Boolean

    fun getUserInfo(): UserDetailInfoModel?

    fun getWbiKeys(): Pair<String, String>

    fun areWbiKeysStale(): Boolean

    fun clearUserSession(reason: String)

    fun handleAuthFailureCode(code: Int, source: String)

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String
    ): UserDetailInfoModel?

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String
    ): BaseResponse<T>

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String
    ): BaseBaseResponse

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String
    ): Base2Response<T>
}

class NetworkManagerSessionGateway : NetworkSessionGateway {
    override fun getCsrfToken(): String = NetworkManager.getCsrfToken()

    override fun isLoggedIn(): Boolean = NetworkManager.isLoggedIn()

    override fun getUserInfo(): UserDetailInfoModel? = NetworkManager.getUserInfo()

    override fun getWbiKeys(): Pair<String, String> = NetworkManager.getWbiKeys()

    override fun areWbiKeysStale(): Boolean = NetworkManager.areWbiKeysStale()

    override fun clearUserSession(reason: String) {
        NetworkManager.clearUserSession(reason = reason)
    }

    override fun handleAuthFailureCode(code: Int, source: String) {
        NetworkManager.handleAuthFailureCode(code, source)
    }

    override fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String
    ): UserDetailInfoModel? {
        return NetworkManager.syncUserSession(response, source)
    }

    override fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String
    ): BaseResponse<T> {
        return NetworkManager.syncAuthState(response, source)
    }

    override fun syncAuthState(
        response: BaseBaseResponse,
        source: String
    ): BaseBaseResponse {
        return NetworkManager.syncAuthState(response, source)
    }

    override fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String
    ): Base2Response<T> {
        return NetworkManager.syncAuthState(response, source)
    }
}
