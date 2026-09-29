package com.btelly.feature.comment

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.core.ui.image.ImageLoader
import com.btelly.model.comment.ReplyModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论列表适配器。
 *
 * 数据源是「一级评论」列表 [topReplies]；二级回复在展示时摊平成相邻几行，靠
 * [CommentRow.isSubReply] 在 bind 时换缩进和弱化样式。摊平而不是嵌套展开的原因：
 * TV 一屏只放得下三五行，expandable list 的收起/展开在 D-pad 上很容易迷路。
 */
internal class CommentAdapter(
    private val onLikeClick: (ReplyModel) -> Unit,
    private val onExpandClick: (ReplyModel) -> Unit
) : ListAdapter<CommentAdapter.CommentRow, RecyclerView.ViewHolder>(DiffCallback) {

    internal data class CommentRow(
        val reply: ReplyModel,
        val isSubReply: Boolean
    )

    private var topReplies: List<ReplyModel> = emptyList()

    /** 一级评论 rpid → 该条评论的全部二级回复（展开时用） */
    private val subReplyPool = mutableMapOf<Long, List<ReplyModel>>()

    /** 一级评论 rpid → 内联显示几条二级回复；默认只显示前 [DEFAULT_INLINE_SUB_REPLIES] 条 */
    private val inlineSubCount = mutableMapOf<Long, Int>()

    internal fun currentTopReplies(): List<ReplyModel> = topReplies

    internal fun submitReplies(replies: List<ReplyModel>) {
        topReplies = replies
        subReplyPool.clear()
        inlineSubCount.clear()
        replies.forEach { reply ->
            if (!reply.replies.isNullOrEmpty()) subReplyPool[reply.rpid] = reply.replies
        }
        submitList(buildRows())
    }

    internal fun appendReplies(replies: List<ReplyModel>) {
        topReplies = topReplies + replies
        replies.forEach { reply ->
            if (!reply.replies.isNullOrEmpty()) subReplyPool[reply.rpid] = reply.replies
        }
        submitList(buildRows())
    }

    /** 展开某条一级评论的全部二级回复 */
    internal fun setInlineSubReplyCount(rpid: Long, count: Int) {
        inlineSubCount[rpid] = count
        submitList(buildRows())
    }

    /** 点赞结果本地回填：不必等下一次下拉刷新 */
    internal fun updateLikeState(rpid: Long, liked: Boolean) {
        fun update(list: List<ReplyModel>): List<ReplyModel> = list.map { reply ->
            when {
                reply.rpid == rpid -> reply.copy(action = if (liked) 1 else 0)
                !reply.replies.isNullOrEmpty() -> reply.copy(replies = update(reply.replies))
                else -> reply
            }
        }
        topReplies = update(topReplies)
        // 二级回复的 rpid 也可能命中，pool 一并更新
        subReplyPool.keys.toList().forEach { key ->
            subReplyPool[key] = update(subReplyPool.getValue(key))
        }
        submitList(buildRows())
    }

    private fun buildRows(): List<CommentRow> {
        val rows = mutableListOf<CommentRow>()
        for (reply in topReplies) {
            rows += CommentRow(reply, isSubReply = false)
            val inline = subReplyPool[reply.rpid].orEmpty()
                .filter { it.rpid > 0L && it.rpid != reply.rpid }
                .take(inlineSubCount[reply.rpid] ?: DEFAULT_INLINE_SUB_REPLIES)
            for (sub in inline) {
                rows += CommentRow(sub, isSubReply = true)
            }
        }
        return rows
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.cell_comment, parent, false)
        return CommentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as CommentViewHolder).bind(getItem(position))
    }

    private inner class CommentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val clickView: View = view.findViewById(R.id.click_view)
        private val avatar: ImageView = view.findViewById(R.id.image_avatar)
        private val name: TextView = view.findViewById(R.id.text_name)
        private val likeLayout: View = view.findViewById(R.id.layout_like)
        private val likeIcon: ImageView = view.findViewById(R.id.image_like)
        private val likeCount: TextView = view.findViewById(R.id.text_like)
        private val message: TextView = view.findViewById(R.id.text_message)
        private val expand: TextView = view.findViewById(R.id.text_expand)

        init {
            // 整行点击 = 点赞；有"展开"入口的一级评论，展开按钮自己处理
            clickView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val row = getItem(position)
                if (!row.isSubReply && row.reply.hasSubRepliesToExpand) {
                    onExpandClick(row.reply)
                } else {
                    onLikeClick(row.reply)
                }
            }
            // 点赞图标也接受点击（手指/触摸板场景）
            likeLayout.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLikeClick(getItem(position).reply)
                }
            }
            expand.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onExpandClick(getItem(position).reply)
                }
            }
            // D-pad 中心键：焦点在整行上时框架不一定会派发 click，这里显式兜一层
            clickView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        clickView.performClick()
                        true
                    }
                    else -> false
                }
            }
        }

        fun bind(row: CommentRow) {
            val reply = row.reply
            // TV 上时间不占独立一行，跟在名字后面省一行高度
            name.text = if (reply.ctime > 0L) {
                "${reply.displayName} · ${formatTime(reply.ctime)}"
            } else {
                reply.displayName
            }
            message.text = reply.message
            ImageLoader.load(imageView = avatar, url = reply.avatarUrl)

            likeCount.text = formatCount(reply.like)
            likeCount.visibility = if (reply.like > 0L) View.VISIBLE else View.GONE
            likeIcon.visibility = likeCount.visibility
            val likeTint = likeIcon.context.getColor(
                if (reply.isLiked) R.color.tv_accent else R.color.tv_text_secondary
            )
            likeIcon.setColorFilter(likeTint)
            likeCount.setTextColor(likeTint)

            if (!row.isSubReply && reply.hasSubRepliesToExpand) {
                expand.visibility = View.VISIBLE
                expand.text = expand.context.getString(
                    R.string.comment_expand_replies_count,
                    reply.count
                )
            } else {
                expand.visibility = View.GONE
            }

            // 二级回复缩进 + 正文略弱化，避免和一级评论糊成一层
            val indent = if (row.isSubReply) {
                itemView.resources.getDimensionPixelSize(R.dimen.space_16)
            } else {
                0
            }
            (clickView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = indent
                clickView.layoutParams = params
            }
            message.alpha = if (row.isSubReply) 0.85f else 1f
        }
    }

    private fun formatTime(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("MM-dd", Locale.getDefault())
        return sdf.format(Date(epochSeconds * 1000L))
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 10_000L -> String.format(Locale.getDefault(), "%.1f万", count / 10_000f)
            else -> count.toString()
        }
    }

    private companion object {
        /** 一级评论下默认内联显示几条二级回复；点"展开"后显示全部 */
        const val DEFAULT_INLINE_SUB_REPLIES = 2
    }

    private object DiffCallback : DiffUtil.ItemCallback<CommentRow>() {
        override fun areItemsTheSame(oldItem: CommentRow, newItem: CommentRow): Boolean =
            oldItem.reply.rpid == newItem.reply.rpid

        override fun areContentsTheSame(oldItem: CommentRow, newItem: CommentRow): Boolean =
            oldItem == newItem
    }
}
