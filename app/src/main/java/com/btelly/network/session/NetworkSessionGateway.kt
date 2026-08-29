package com.btelly.network.session

import com.btelly.core.common.log.AppLog
import com.btelly.model.BaseResponse
import com.btelly.model.user.UserDetailInfoModel
import com.btelly.network.NetworkManager
import com.btelly.network.response.Base2Response
import com.btelly.network.response.BaseBaseResponse
import com.btelly.network.security.RiskControlCooldownManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

interface NetworkSessionGateway {
    fun getCsrfToken(): String

    fun isLoggedIn(): Boolean

    /** 会话单一状态源：登录态变化自动推送，UI 不再轮询 */
    val sessionState: StateFlow<SessionState>

    fun currentSessionState(): SessionState

    fun getUserInfo(): UserDetailInfoModel?

    fun getWbiKeys(): Pair<String, String>

    fun areWbiKeysStale(): Boolean

    suspend fun ensureWbiKeys()

    suspend fun forceCookieRefresh()

    suspend fun tryRecoverExpiredSession(): Boolean

    fun clearUserSession(reason: String)

    fun handleAuthFailureCode(code: Int, source: String)

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean

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

    fun isCsrfError(code: Int, message: String?): Boolean

    @Deprecated("Use classifyActionError instead", ReplaceWith("classifyActionError(code, message)"))
    fun handleResponseAuthError(code: Int, message: String?): Boolean

    // ========== 新增：Context-aware 方法 ==========

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext
    ): UserDetailInfoModel?

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext
    ): BaseResponse<T>

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext
    ): BaseBaseResponse

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext
    ): Base2Response<T>

    /** 返回 csrf token，为空则返回 null（表示 session 不完整） */
    fun requireCsrfToken(): String?

    /** 统一风控检测 */
    fun isRiskControl(code: Int, message: String? = null): Boolean

    /** 统一可重试错误判断（风控 + 频率限制，含关键词匹配） */
    fun isRetryableError(code: Int, message: String? = null): Boolean

    /** 统一错误分类，给 UI 层使用 */
    fun classifyActionError(code: Int, message: String?): ActionError

    /** 风控冷却管理器 */
    fun getCooldownManager(): RiskControlCooldownManager

    /** 统一风控重试，自动处理冷却、预热、重试 */
    suspend fun <T> executeWithRiskControlRetry(
        key: String,
        source: String,
        maxRetries: Int = 1,
        block: suspend (attempt: Int) -> BaseResponse<T>
    ): BaseResponse<T>

    /**
     * 通用风控重试辅助，适用于 BaseBaseResponse / Base2Response 等非 BaseResponse 类型。
     * 调用方提供 code/message 提取器，方法内部处理冷却、预热和重试。
     */
    suspend fun <T : Any> retryOnRiskControl(
        key: String,
        source: String,
        getCode: (T) -> Int,
        getMessage: (T) -> String?,
        getIsSuccess: (T) -> Boolean,
        maxRetries: Int = 1,
        block: suspend () -> T
    ): T

    // ========== 错误分类 ==========

    sealed interface ActionError {
        data class SessionExpired(val message: String) : ActionError
        data class CsrfMismatch(val message: String) : ActionError
        data class CsrfMissing(val message: String) : ActionError
        data class RiskControl(val message: String) : ActionError
        data class FrequencyLimit(val message: String) : ActionError
        data class Other(val message: String) : ActionError
    }
}

class NetworkManagerSessionGateway : NetworkSessionGateway {

    private val riskControlCodes = setOf(-352, -351)
    private val frequencyLimitCodes = setOf(-412)
    private val riskControlKeywords = listOf("风控", "拦截", "风险", "神秘力量", "risk", "blocked")
    private val frequencyLimitKeywords = listOf("频繁", "过快", "稍后", "too many", "rate limit")
    private val cooldownManager = RiskControlCooldownManager()

    override fun getCsrfToken(): String = NetworkManager.getCsrfToken()

    override fun isLoggedIn(): Boolean = NetworkManager.isLoggedIn()

    override val sessionState: StateFlow<SessionState>
        get() = NetworkManager.sessionState

    override fun currentSessionState(): SessionState = NetworkManager.currentSessionState()

    override fun getUserInfo(): UserDetailInfoModel? = NetworkManager.getUserInfo()

    override fun getWbiKeys(): Pair<String, String> = NetworkManager.getWbiKeys()

    override fun areWbiKeysStale(): Boolean = NetworkManager.areWbiKeysStale()

    override suspend fun ensureWbiKeys() = NetworkManager.ensureWbiKeys()

    override suspend fun forceCookieRefresh() = NetworkManager.forceCookieRefresh()

    override suspend fun tryRecoverExpiredSession(): Boolean {
        return NetworkManager.tryRecoverExpiredSession()
    }

    override fun clearUserSession(reason: String) {
        NetworkManager.clearUserSession(reason = reason)
    }

    override fun handleAuthFailureCode(code: Int, source: String) {
        NetworkManager.handleAuthFailureCode(code, source)
    }

    override suspend fun prewarmWebSession(forceUaRefresh: Boolean): Boolean {
        return NetworkManager.prewarmWebSession(forceUaRefresh)
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

    override fun isCsrfError(code: Int, message: String?): Boolean {
        if (code == -101 || code == -111) return true
        return message.orEmpty().contains("csrf", ignoreCase = true)
    }

    @Deprecated("Use classifyActionError instead", ReplaceWith("classifyActionError(code, message)"))
    override fun handleResponseAuthError(code: Int, message: String?): Boolean {
        return classifyActionError(code, message) is NetworkSessionGateway.ActionError.SessionExpired
    }

    // ========== Context-aware 实现 ==========

    override fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext
    ): UserDetailInfoModel? {
        return NetworkManager.syncUserSession(response, source, context)
    }

    override fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext
    ): BaseResponse<T> {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext
    ): BaseBaseResponse {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext
    ): Base2Response<T> {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun requireCsrfToken(): String? {
        val csrf = NetworkManager.getCsrfToken()
        if (csrf.isBlank()) {
            AppLog.w("SessionGateway", "requireCsrfToken: csrf token is blank")
        }
        return csrf.takeIf { it.isNotBlank() }
    }

    override fun isRiskControl(code: Int, message: String?): Boolean {
        if (code in riskControlCodes) return true
        val msg = message.orEmpty()
        return riskControlKeywords.any { msg.contains(it, ignoreCase = true) }
    }

    override fun isRetryableError(code: Int, message: String?): Boolean {
        if (code in riskControlCodes) return true
        if (code in frequencyLimitCodes) return true
        val msg = message.orEmpty()
        if (riskControlKeywords.any { msg.contains(it, ignoreCase = true) }) return true
        if (frequencyLimitKeywords.any { msg.contains(it, ignoreCase = true) }) return true
        return false
    }

    override fun getCooldownManager(): RiskControlCooldownManager = cooldownManager

    override suspend fun <T> executeWithRiskControlRetry(
        key: String,
        source: String,
        maxRetries: Int,
        block: suspend (attempt: Int) -> BaseResponse<T>
    ): BaseResponse<T> {
        val response = block(0)
        if (!isRetryableError(response.code, response.message) || maxRetries <= 0) {
            if (response.isSuccess) cooldownManager.recordSuccess(key)
            return response
        }
        cooldownManager.recordFailure(key, response.code)
        val cooldownMs = cooldownManager.checkCooldown(key)
        AppLog.w("RiskRetry", "$source hit retryable error: code=${response.code}, key=$key, cooldown=${cooldownMs}ms")
        if (cooldownMs > 0) delay(cooldownMs)
        prewarmWebSession(forceUaRefresh = true)
        val retryResponse = block(1)
        if (retryResponse.isSuccess) cooldownManager.recordSuccess(key)
        else cooldownManager.recordFailure(key, retryResponse.code)
        return retryResponse
    }

    override suspend fun <T : Any> retryOnRiskControl(
        key: String,
        source: String,
        getCode: (T) -> Int,
        getMessage: (T) -> String?,
        getIsSuccess: (T) -> Boolean,
        maxRetries: Int,
        block: suspend () -> T
    ): T {
        val response = block()
        val code = getCode(response)
        if (!isRetryableError(code, getMessage(response)) || maxRetries <= 0) {
            if (getIsSuccess(response)) cooldownManager.recordSuccess(key)
            return response
        }
        cooldownManager.recordFailure(key, code)
        val cooldownMs = cooldownManager.checkCooldown(key)
        AppLog.w("RiskRetry", "$source hit retryable error: code=$code, key=$key, cooldown=${cooldownMs}ms")
        if (cooldownMs > 0) delay(cooldownMs)
        prewarmWebSession(forceUaRefresh = true)
        val retryResponse = block()
        if (getIsSuccess(retryResponse)) cooldownManager.recordSuccess(key)
        else cooldownManager.recordFailure(key, getCode(retryResponse))
        return retryResponse
    }

    override fun classifyActionError(code: Int, message: String?): NetworkSessionGateway.ActionError {
        val msg = message ?: ""
        if (code == -111 || (msg.contains("csrf", ignoreCase = true) && code != -101)) {
            return NetworkSessionGateway.ActionError.CsrfMismatch(msg.ifEmpty { "csrf 校验失败" })
        }
        if (code == -101) {
            return NetworkSessionGateway.ActionError.SessionExpired(msg.ifEmpty { "登录已过期" })
        }
        val isFreqByKeyword = frequencyLimitKeywords.any { msg.contains(it, ignoreCase = true) }
        if (code in frequencyLimitCodes || isFreqByKeyword) {
            return NetworkSessionGateway.ActionError.FrequencyLimit(msg.ifEmpty { "操作过于频繁，请稍后再试" })
        }
        if (isRiskControl(code, message)) {
            return NetworkSessionGateway.ActionError.RiskControl(msg.ifEmpty { "账号被风控" })
        }
        return NetworkSessionGateway.ActionError.Other(msg.ifEmpty { "操作失败" })
    }

}
