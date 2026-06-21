package com.btelly.core.emote

import android.content.Context

/**
 * 表情 token → URL 映射仓库 stub（no-op）。
 *
 * 上游实现 弹幕引擎引用 [ReplyEmotePanelRepository] 把 `[doge]` 这类 token 解析成表情图 URL。
 * 当前阶段不迁移真实表情功能：version() 返回 0 会让引擎判定"表情映射未就绪"，
 * 走纯文本绘制路径；urlForToken 返回 null 让表情 token 原样显示成文字。
 *
 * 后续要支持表情时，在这里实现真正的 token→URL 映射加载逻辑即可。
 */
object ReplyEmotePanelRepository {
    fun warmup(context: Context) {}
    fun version(): Int = 0
    fun urlForToken(token: String): String? = null
}
