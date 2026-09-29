package com.btelly.repository.comment

import com.btelly.model.BaseResponse
import com.btelly.model.comment.ReplyWrapper
import com.btelly.network.api.ApiService
import com.btelly.network.WbiGenerator
import com.btelly.network.security.NetworkSecurityGateway
import com.btelly.network.session.NetworkSessionGateway

/**
 * 评论区数据源。
 *
 * 列表接口 `x/v2/reply/wbi/main` 带 WBI 签名（不签名会直接被风控挡掉返回 -352），
 * 点赞接口 `x/v2/reply/action` 是表单 + csrf，不需要 WBI。
 */
class CommentRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private companion object {
        const val SOURCE = "comment"
        /** 视频评论 type=1 */
        const val REPLY_TYPE_VIDEO = 1
        /** 热门排序。默认用这个：TV 一屏只放得下三五行，热门的"看头"密度更高 */
        const val MODE_HOT = 3
        /** 时间排序。保留常量但不默认使用——入口没有做成可切的 */
        const val MODE_TIME = 2
        /** 一页 20 条：TV 一屏放不下 20 条，但翻页游标由接口决定，条数只影响一次请求的量 */
        const val PAGE_SIZE = 20
    }

    /**
     * @param mode 3=热门排序，2=时间排序
     * @param nextCursor 翻页游标，首页传 0
     */
    suspend fun getReplyList(
        aid: Long,
        mode: Int = MODE_HOT,
        nextCursor: Long = 0L
    ): Result<BaseResponse<ReplyWrapper>> = runCatching {
        sessionGateway.executeWithRiskControlRetry(
            key = "reply_${aid}_$mode",
            source = "$SOURCE.getReplyList"
        ) {
            val wbiKeys = sessionGateway.getWbiKeys()
            val signedParams = WbiGenerator.generateWbiParams(
                linkedMapOf(
                    "oid" to aid.toString(),
                    "type" to REPLY_TYPE_VIDEO.toString(),
                    "mode" to mode.toString(),
                    "next" to nextCursor.toString(),
                    "ps" to PAGE_SIZE.toString()
                ),
                wbiKeys.first,
                wbiKeys.second
            )
            sessionGateway.syncAuthState(
                apiService.getReplyList(signedParams),
                source = "$SOURCE.getReplyList"
            )
        }
    }

    /** @param action 1=点赞，0=取消点赞 */
    suspend fun likeReply(aid: Long, rpid: Long, action: Boolean): Result<BaseResponse<Any>> =
        runCatching {
            val csrf = sessionGateway.requireCsrfToken()
                ?: return Result.success(BaseResponse(code = -111, message = "csrf token is blank"))
            securityGateway.ensureHealthyForPlay()
            sessionGateway.executeWithRiskControlRetry(
                key = "reply_like_$rpid",
                source = "$SOURCE.likeReply"
            ) {
                sessionGateway.syncAuthState(
                    apiService.likeReply(
                        oid = aid,
                        type = REPLY_TYPE_VIDEO,
                        rpid = rpid,
                        action = if (action) 1 else 0,
                        csrf = csrf
                    ),
                    source = "$SOURCE.likeReply"
                )
            }
        }
}
