package com.btelly.repository.comment

import com.btelly.model.BaseResponse
import com.btelly.network.api.ApiService
import com.btelly.network.security.NetworkSecurityGateway
import com.btelly.network.session.NetworkSessionGateway

/**
 * 发送视频弹幕。
 *
 * 两件事必须自己做，B 站不管：
 * 1. **最短发送间隔**：同一账号连发会被风控（轻则静默丢弃，重则禁言）。这里在
 *    repository 层做进程级节流，不依赖 UI 记得禁用按钮。
 * 2. **rnd 随机数**：接口要求一个时间戳量级的随机数，用作去重键。
 */
class DanmakuPostRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private companion object {
        const val SOURCE = "danmakuPost"
        /** type=1 是视频弹幕（直播是 3，漫画是 4...） */
        const val DM_TYPE_VIDEO = 1

        /** 滚动弹幕 */
        const val MODE_ROLL = 1
        /** 底部弹幕 */
        const val MODE_BOTTOM = 4
        /** 顶部弹幕 */
        const val MODE_TOP = 5

        /**
         * 同一账号两次发送的最小间隔。B 站没有公开的限制数值，实测连发三五条之内
         * 一般没事，但批量脚本式连发会被判风控。取 3 秒：既能连发几条，也不会
         * 因为误触被挡住。
         */
        const val MIN_SEND_INTERVAL_MS = 3_000L
    }

    @Volatile
    private var lastSentAtMs = 0L

    /** 距离下一条可发送还剩多少毫秒；<=0 表示现在就能发 */
    fun remainingCooldownMs(): Long {
        val elapsed = System.currentTimeMillis() - lastSentAtMs
        return if (elapsed >= MIN_SEND_INTERVAL_MS) 0L else MIN_SEND_INTERVAL_MS - elapsed
    }

    suspend fun postDanmaku(
        aid: Long,
        text: String,
        mode: Int = MODE_ROLL
    ): Result<BaseResponse<Any>> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Result.success(BaseResponse(code = -1, message = "弹幕内容为空"))
        }
        if (trimmed.length > MAX_DANMAKU_LENGTH) {
            return Result.success(
                BaseResponse(code = -1, message = "弹幕最长 $MAX_DANMAKU_LENGTH 字")
            )
        }
        val cooldown = remainingCooldownMs()
        if (cooldown > 0L) {
            return Result.success(
                BaseResponse(
                    code = -1,
                    message = "发送太频繁，请 ${(cooldown + 999) / 1000} 秒后再试"
                )
            )
        }

        var result: Result<BaseResponse<Any>> = Result.success(
            BaseResponse(code = -1, message = "未登录")
        )
        runCatching {
            val csrf = sessionGateway.requireCsrfToken()
            if (csrf.isNullOrBlank()) {
                return Result.success(BaseResponse(code = -111, message = "csrf token is blank"))
            }
            securityGateway.ensureHealthyForPlay()
            sessionGateway.executeWithRiskControlRetry(
                key = "dm_post_$aid",
                source = SOURCE
            ) {
                sessionGateway.syncAuthState(
                    apiService.postDanmaku(
                        type = DM_TYPE_VIDEO,
                        oid = aid,
                        msg = trimmed,
                        aid = aid,
                        mode = mode,
                        rnd = System.currentTimeMillis(),
                        csrf = csrf
                    ),
                    source = SOURCE
                )
            }.also { response ->
                // 只在真正发出去（或服务端明确收下）时开始计时，失败不占用冷却窗口
                if (response.isSuccess) {
                    lastSentAtMs = System.currentTimeMillis()
                }
                result = Result.success(response)
            }
        }.onFailure { e ->
            result = Result.failure(e)
        }
        return result
    }

    companion object {
        /** B 站视频弹幕长度上限 100 字，这里保守取 80（TV 输入不便，长了也难看） */
        const val MAX_DANMAKU_LENGTH = 80
    }
}
