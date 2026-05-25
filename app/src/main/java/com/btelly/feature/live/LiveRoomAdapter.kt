package com.btelly.feature.live

import android.annotation.SuppressLint
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.btelly.core.common.log.VideoCardPerfLogger
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.common.format.NumberUtils
import com.btelly.core.ui.focus.VideoCardFocusHelper
import com.btelly.core.ui.focus.tv.TvFocusableAdapter
import com.btelly.core.ui.video.VideoCardViews
import com.btelly.core.ui.video.VideoLightCardFactory
import com.btelly.model.live.LiveRoomItem
import com.btelly.model.video.Owner
import com.btelly.model.video.VideoModel
import com.btelly.ui.adapter.VideoAdapter
import com.btelly.ui.dialog.VideoCardMenuDialog

class LiveRoomAdapter(
    private val onItemClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<LiveRoomAdapter.ViewHolder>(), TvFocusableAdapter {

    private val items = ArrayList<LiveRoomItem>()

    val currentList: List<LiveRoomItem>
        get() = items

    init {
        setHasStableIds(true)
    }

    override fun focusableItemCount(): Int = items.size

    override fun stableKeyAt(position: Int): String? = items.getOrNull(position)?.roomId?.toString()

    override fun findPositionByStableKey(key: String): Int =
        items.indexOfFirst { it.roomId.toString() == key }
            .takeIf { it >= 0 } ?: RecyclerView.NO_POSITION

    fun setData(list: List<LiveRoomItem>, onCommitted: (() -> Unit)? = null) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        onCommitted?.invoke()
    }

    fun addData(list: List<LiveRoomItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.roomId ?: RecyclerView.NO_ID

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_LIVE_ROOM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("LiveRoomAdapter.light") {
            VideoLightCardFactory.create(parent, source = "LiveRoomAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    private fun removeItemsByAnchorName(blockedName: String) {
        val filtered = items.filter { !it.uname.equals(blockedName, ignoreCase = true) }
        if (filtered.size == items.size) return
        setData(filtered)
    }

    private fun removeRoom(roomId: Long) {
        val index = items.indexOfFirst { it.roomId == roomId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        private var currentItem: LiveRoomItem? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private val longPressThreshold = 5_000L
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPressTimer()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPressTimer()
                    }
                }
            }
            false
        }

        private fun startLongPressTimer() {
            cancelLongPressTimer()
            longPressTriggered = false
            longPressRunnable = Runnable {
                val item = currentItem ?: return@Runnable
                longPressTriggered = true
                VideoCardMenuDialog(
                    context = itemView.context,
                    video = item.toFeedbackVideoModel(),
                    onDislikeVideo = {
                        removeRoom(item.roomId)
                    },
                    onDislikeUp = { upName ->
                        removeItemsByAnchorName(upName)
                    }
                ).show()
            }
            handler.postDelayed(longPressRunnable!!, longPressThreshold)
        }

        private fun cancelLongPressTimer() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.progressBar.clipToOutline = true
            views.progressBar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
            views.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items.getOrNull(position)?.let(onItemClick)
                }
            }
            @SuppressLint("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPressTimer()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressTimer()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = onTopEdgeUp,
                chainedListener = keyListener
            )
        }

        fun bind(item: LiveRoomItem) {
            currentItem = item
            views.textLayer.setTitle(item.title, lines = 1)
            views.textLayer.setOwner(
                ownerText = item.uname,
                showAvatar = item.uname.isNotBlank(),
                show = item.uname.isNotBlank()
            )
            views.textLayer.clearHistoryTrailing()
            views.progressBar.visibility = View.GONE
            views.coverMetaOverlay.bind(
                playCountText = NumberUtils.formatCount(item.online.toLong()),
                showPlayCount = true,
                showDanmakuCount = false,
                durationText = "",
                showChargeBadge = false,
                showInteractionBadge = false
            )

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = item.cover,
                deferUntilPreDraw = true
            )
        }
    }

    private fun LiveRoomItem.toFeedbackVideoModel(): VideoModel {
        return VideoModel(
            title = title,
            cover = cover,
            goto = "live",
            roomId = roomId,
            owner = Owner(
                mid = uid,
                name = uname,
                face = face
            )
        )
    }

    companion object {
        private const val VIEW_TYPE_LIVE_ROOM = 0x4C5200
    }
}
