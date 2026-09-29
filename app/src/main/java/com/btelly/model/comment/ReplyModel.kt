package com.btelly.model.comment

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * 一条评论（含二级回复）。
 *
 * B 站 `x/v2/reply/wbi/main` 的 `replies[]` 结构：一级评论自带 `replies[]`（热门二级回复，
 * 通常最多 3 条）和 `count`（二级回复总数；`root`/`parent` 为 0 表示这是一级评论）。
 * 二级评论的 `root` 指向一级评论 rpid，`parent` 指向其上一级。
 */
data class ReplyModel(
    @SerializedName("rpid")
    val rpid: Long = 0,
    @SerializedName("oid")
    val oid: Long = 0,
    @SerializedName("type")
    val type: Int = 1,
    @SerializedName("mid")
    val mid: Long = 0,
    @SerializedName("root")
    val root: Long = 0,
    @SerializedName("parent")
    val parent: Long = 0,
    @SerializedName("like")
    val like: Long = 0,
    /** 0=未操作，1=已点赞，2=已点踩 */
    @SerializedName("action")
    val action: Int = 0,
    @SerializedName("count")
    val count: Int = 0,
    @SerializedName("ctime")
    val ctime: Long = 0,
    @SerializedName("content")
    val content: ReplyContent? = null,
    @SerializedName("member")
    val member: ReplyMember? = null,
    @SerializedName("replies")
    val replies: List<ReplyModel>? = null,
    @SerializedName("reply_control")
    val replyControl: ReplyControl? = null
) : Serializable {

    /** 二级回复（root != 0 或 replies 非空且 parent != 0）— UI 上缩进展示 */
    val isSubReply: Boolean
        get() = root > 0L || parent > 0L

    val message: String
        get() = content?.message.orEmpty()

    val displayName: String
        get() = member?.uname.orEmpty().ifEmpty { "用户$mid" }

    val avatarUrl: String
        get() = member?.avatar.orEmpty()

    val isLiked: Boolean
        get() = action == 1

    /** 有没有二级回复可以展开。B 站只在内联返回前几条，count 可能是全部二级回复数 */
    val hasSubRepliesToExpand: Boolean
        get() = (replies.isNullOrEmpty() && count > (replies?.size ?: 0))
}

data class ReplyContent(
    @SerializedName("message")
    val message: String = "",
    @SerializedName("plat")
    val plat: Int = 0
) : Serializable

data class ReplyMember(
    @SerializedName("mid")
    val mid: Long = 0,
    @SerializedName("uname")
    val uname: String = "",
    @SerializedName("avatar")
    val avatar: String = ""
) : Serializable

data class ReplyControl(
    @SerializedName("sub_reply_entry_text")
    val subReplyEntryText: String = "",
    @SerializedName("sub_reply_title_text")
    val subReplyTitleText: String = ""
) : Serializable

/**
 * 评论列表响应。`cursor.next` 是翻页游标（0 表示没有下一页）。
 * 顶层已把 top_replies（置顶评论）与 replies 合并，调用方不用关心来源。
 */
data class ReplyWrapper(
    @SerializedName("cursor")
    val cursor: ReplyCursor? = null,
    @SerializedName("replies")
    val replies: List<ReplyModel>? = null,
    @SerializedName("top_replies")
    val topReplies: List<ReplyModel>? = null
) : Serializable {

    /** 合并置顶与普通评论；置顶在前（B 站 web 端也是这个顺序） */
    val allReplies: List<ReplyModel>
        get() = (topReplies.orEmpty() + replies.orEmpty()).filter { it.rpid > 0L }

    val nextCursor: Long
        get() = cursor?.next ?: 0L

    val totalCount: Long
        get() = cursor?.allCount ?: 0L
}

data class ReplyCursor(
    @SerializedName("is_begin")
    val isBegin: Boolean = true,
    @SerializedName("prev")
    val prev: Long = 0,
    @SerializedName("next")
    val next: Long = 0,
    @SerializedName("is_end")
    val isEnd: Boolean = true,
    @SerializedName("all_count")
    val allCount: Long = 0
) : Serializable
