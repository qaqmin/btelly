package com.btelly.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Outline
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.model.video.HistoryVideoModel
import com.btelly.core.ui.base.BaseVideoAdapter
import com.btelly.core.ui.base.BaseVideoViewHolder
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.common.format.NumberUtils
import com.btelly.core.common.log.VideoCardPerfLogger
import com.btelly.core.common.time.TimeUtils
import com.btelly.core.ui.focus.VideoCardFocusHelper
import com.btelly.core.ui.video.VideoCardViews
import com.btelly.core.ui.video.VideoLightCardFactory
import com.btelly.ui.dialog.VideoCardMenuDialog

class HistoryVideoAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    onTopEdgeUp: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null,
    private val onHistoryRecordDeleted: ((HistoryVideoModel) -> Unit)? = null,
    private val onItemDisliked: ((HistoryVideoModel) -> Unit)? = null,
    private val onUpDisliked: ((String) -> Unit)? = null
) : BaseVideoAdapter<HistoryVideoModel, HistoryVideoAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
        setShowLoadMore(false)
        this.onTopEdgeUp = onTopEdgeUp
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
    }

    override fun itemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    override fun areContentsSame(old: HistoryVideoModel, new: HistoryVideoModel): Boolean = old == new

    override fun setData(
        newItems: List<HistoryVideoModel>,
        onCommitted: (() -> Unit)?
    ) {
        val savedPosition = focusedPosition
        setDataDeduplicated(newItems) {
            if (savedPosition != RecyclerView.NO_POSITION && savedPosition < items.size) {
                focusedPosition = savedPosition
            }
            onCommitted?.invoke()
        }
    }

    fun focusedItemPosition(): Int = focusedPosition

    fun findPositionByKey(key: String): Int = findPositionByStableKey(key)

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("HistoryVideoAdapter.light") {
            VideoLightCardFactory.create(parent, source = "HistoryVideoAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item, position == focusedPosition)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusedPosition = RecyclerView.NO_POSITION
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        applyFocusState(holder.itemView, false)
        super.onViewRecycled(holder)
    }

    private fun applyFocusState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<View>(R.id.textView)?.isSelected = focused
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : BaseVideoViewHolder(views.root) {

        private var currentItem: HistoryVideoModel? = null

        private val keyListener = View.OnKeyListener { view, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPress()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPress()
                    }
                }
                false
            } else {
                onItemDpad?.invoke(view, keyCode, event) == true
            }
        }

        override fun showCardMenu() {
            cancelLongPress()
            val item = currentItem ?: return
            val video = item.toVideoModel()
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { onItemDisliked?.invoke(item) },
                onDislikeUp = { upName -> onUpDisliked?.invoke(upName) },
                onHistoryRecordDeleted = {
                    onHistoryRecordDeleted?.invoke(item)
                }
            ).show()
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
                    onItemFocused?.invoke(position)
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChanged(previous)
                    }
                    applyFocusState(views.root, true)
                    onItemFocusedWithView?.invoke(views.root, position)
                }
                currentItem?.let(onItemClick)
            }
            @SuppressLint("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            views.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChanged(previous)
                    }
                    applyFocusState(view, true)
                    onItemFocusedWithView?.invoke(view, position)
                } else {
                    applyFocusState(views.root, false)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = onTopEdgeUp,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            views.textLayer.setOwner(ownerText = "", showAvatar = false, show = false)
            views.textLayer.clearHistoryTrailing()
            views.coverMetaOverlay.bind(
                showPlayCount = false,
                showDanmakuCount = false,
                durationText = "",
                showChargeBadge = false,
                showInteractionBadge = false
            )
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item

            views.root.isSelected = isFocused
            views.textLayer.isSelected = isFocused
            views.textLayer.setTitle(item.title.ifBlank { item.showTitle }, lines = 2)
            val ownerName = item.displayAuthorName
            views.textLayer.setOwner(
                ownerText = ownerName,
                showAvatar = ownerName.isNotBlank()
            )

            views.progressBar.visibility = View.VISIBLE
            val durationValue = item.duration.coerceAtLeast(0L)
            val progressValue = item.progress.coerceAtLeast(0L).coerceAtMost(durationValue)
            views.progressBar.max = durationValue.toInt()
            views.progressBar.progress = progressValue.toInt()

            val durationText = when {
                item.history?.business == "live" && item.badge.isNotBlank() -> item.badge
                durationValue > 0L -> "${NumberUtils.formatDuration(progressValue)}/${NumberUtils.formatDuration(durationValue)}"
                item.tagName.isNotBlank() -> item.tagName
                else -> ""
            }
            views.textLayer.setHistoryTrailing(
                timeText = TimeUtils.formatHistoryViewTime(item.viewAt),
                deviceDrawableRes = HistoryDeviceIcon.resolve(item.history?.dt ?: 0)?.drawableRes ?: 0
            )
            views.coverMetaOverlay.bind(
                showPlayCount = false,
                showDanmakuCount = false,
                durationText = durationText,
                showChargeBadge = item.isChargingExclusive,
                showInteractionBadge = item.isSteinsGate
            )

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = item.cover.ifBlank { item.covers?.firstOrNull().orEmpty() },
                deferUntilPreDraw = true
            )
        }

    }
}
