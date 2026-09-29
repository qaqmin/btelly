package com.btelly.feature.comment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.core.ui.base.BaseFragment
import com.btelly.databinding.FragmentCommentBinding
import com.btelly.model.comment.ReplyModel
import com.btelly.network.session.SessionStateRepository
import com.btelly.repository.comment.CommentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 视频评论区（TV 版）。
 *
 * 与手机端评论区的差别，以及为什么这么做：
 * - **排序**：默认热门（mode=3），不提供切到时间序的入口。TV 上一屏只放得下三五行，
 *   热门评论的"看头"密度远高于时间序；真要按时间看的人会用手机端。
 * - **二级回复摊平**：一级评论下内联前 2 条二级回复，点"展开 N 条回复"显示全部。
 *   不做嵌套 expandable list——D-pad 操作折叠树很容易迷路。
 * - **点赞**：遥控器 OK 键即点赞/取消。B 站这个接口是幂等的，重复点只是来回切。
 *
 * 尚未实现（有意留白，见交接说明）：发评论、发二级回复、跳转到某用户的全部回复。
 * 发评论需要 T9/全键盘文本输入，那是独立一块。
 */
class CommentFragment : BaseFragment<FragmentCommentBinding>() {

    companion object {
        private const val ARG_AID = "aid"
        private const val ARG_TITLE = "title"

        fun newInstance(aid: Long, title: String? = null): CommentFragment {
            return CommentFragment().apply {
                arguments = bundleOf(
                    ARG_AID to aid,
                    ARG_TITLE to title
                )
            }
        }
    }

    private val commentRepository: CommentRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()

    private var aid: Long = 0
    private var pageTitle: String = ""

    private lateinit var adapter: CommentAdapter
    private var loadJob: Job? = null
    private var requestSerial = 0
    private var nextCursor: Long = 0L
    private var hasMore = true
    private var isLoading = false

    override fun initArguments() {
        aid = arguments?.getLong(ARG_AID, 0L) ?: 0L
        pageTitle = arguments?.getString(ARG_TITLE).orEmpty()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCommentBinding {
        return FragmentCommentBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        textMainTitle?.text = getString(R.string.comment_title)
        adapter = CommentAdapter(
            onLikeClick = ::toggleLike,
            onExpandClick = ::expandSubReplies
        )
        binding.recyclerComment.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerComment.adapter = adapter
        binding.recyclerComment.clipToPadding = false
        binding.recyclerComment.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!isLoading && hasMore && lm.findLastVisibleItemPosition() >= lm.itemCount - 3) {
                    loadComments()
                }
            }
        })
        binding.commentEmptyState.emptyStateAction.setOnClickListener { loadComments(refresh = true) }
    }

    override fun initData() {
        loadComments(refresh = true)
    }

    override fun initObserver() {
        // 登录态变化后，评论接口能看到的内容和能否点赞都会变，刷新一次。
        // 注意：只在 STARTED 且可见时刷新，切 tab 隐藏期间不打扰。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionGateway.sessionState.collectLatest {
                    if (isVisible && adapter.itemCount >= 0) {
                        loadComments(refresh = true)
                    }
                }
            }
        }
    }

    private fun loadComments(refresh: Boolean = false) {
        if (aid <= 0L) {
            showEmpty(getString(R.string.comment_unavailable))
            return
        }
        if (isLoading && !refresh) return
        if (refresh) {
            nextCursor = 0L
            hasMore = true
            loadJob?.cancel()
        }
        if (!hasMore) return

        val requestId = ++requestSerial
        isLoading = true
        binding.progressBar.isVisible = refresh && adapter.itemCount == 0
        val requestCursor = nextCursor

        loadJob = lifecycleScope.launch {
            val result = try {
                commentRepository.getReplyList(aid = aid, nextCursor = requestCursor)
            } catch (e: CancellationException) {
                throw e
            }
            if (requestId != requestSerial) {
                AppLog.d("Comment", "drop stale result request=$requestId")
                return@launch
            }
            isLoading = false
            binding.progressBar.isVisible = false

            result.onSuccess { response ->
                if (!response.isSuccess) {
                    handleError(response.errorMessage)
                    return@onSuccess
                }
                val data = response.data
                val replies = data?.allReplies.orEmpty()
                nextCursor = data?.nextCursor ?: 0L
                hasMore = nextCursor > 0L && !(data?.cursor?.isEnd ?: true)

                if (refresh) {
                    if (replies.isEmpty()) {
                        adapter.submitReplies(emptyList())
                        showEmpty(getString(R.string.comment_empty))
                    } else {
                        hideEmpty()
                        adapter.submitReplies(replies)
                    }
                } else if (replies.isNotEmpty()) {
                    hideEmpty()
                    adapter.appendReplies(replies)
                }
                AppLog.d("Comment", "loaded cursor=$requestCursor got=${replies.size} next=$nextCursor")
            }.onFailure { e ->
                handleError(e.message ?: getString(R.string.net_error))
            }
        }
    }

    private fun handleError(message: String) {
        if (adapter.itemCount == 0) {
            showEmpty(message, withRetry = true)
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLike(reply: ReplyModel) {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val targetLiked = !reply.isLiked
        val replyId = reply.rpid
        lifecycleScope.launch {
            commentRepository.likeReply(aid = aid, rpid = replyId, action = targetLiked)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        // 本地立即反映新状态，不等下拉刷新
                        adapter.updateLikeState(replyId, targetLiked)
                        toast(
                            if (targetLiked) getString(R.string.comment_liked)
                            else getString(R.string.comment_like_cancelled)
                        )
                    } else {
                        toast(response.errorMessage)
                    }
                }
                .onFailure { toast(it.message ?: "操作失败") }
        }
    }

    private fun expandSubReplies(reply: ReplyModel) {
        val subs = adapter.currentTopReplies()
            .firstOrNull { it.rpid == reply.rpid }
            ?.replies
            .orEmpty()
        if (subs.isEmpty()) return
        // 摊平列表里这条一级评论原本只内联前几条，展开即改成全部
        adapter.setInlineSubReplyCount(reply.rpid, subs.size)
        toast(getString(R.string.comment_expanded))
    }

    private fun showEmpty(message: String, withRetry: Boolean = false) {
        binding.commentEmptyState.root.isVisible = true
        binding.commentEmptyState.emptyStateTitle.text = message
        binding.commentEmptyState.emptyStateAction.isVisible = withRetry
        binding.recyclerComment.isVisible = false
    }

    private fun hideEmpty() {
        binding.commentEmptyState.root.isVisible = false
        binding.recyclerComment.isVisible = true
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
