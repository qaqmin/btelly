package com.btelly.repository

import com.btelly.repository.remote.AuthRepository as NetworkAuthRepository

class AuthRepository(
    private val delegate: NetworkAuthRepository = NetworkAuthRepository()
) {
    suspend fun getQrCode() = delegate.getQrCode()

    suspend fun checkSignInResult(qrcodeKey: String) = delegate.checkSignInResult(qrcodeKey)
}
