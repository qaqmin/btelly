package com.btelly.feature.search

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.graphics.Outline
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.databinding.CellLiveRoomBinding
import com.btelly.databinding.CellMovieBinding
import com.btelly.databinding.CellUserBinding
import com.btelly.model.search.SearchItemModel
import com.btelly.model.search.SearchType
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.common.format.NumberUtils
import com.btelly.core.common.log.VideoCardPerfLogger
import com.btelly.core.common.time.TimeUtils
import com.btelly.core.ui.focus.VideoCardFocusHelper
import com.btelly.core.ui.focus.tv.TvFocusableAdapter
import com.btelly.core.ui.video.VideoCardViews
import com.btelly.core.ui.video.VideoLightCardFactory
import com.btelly.model.video.Owner
import com.btelly.model.video.VideoModel
import com.btelly.ui.adapter.VideoAdapter
import com.btelly.ui.dialog.VideoCardMenuDialog

data class SearchResultEntry(
    val pageType: SearchType,
    val item: SearchItemModel
)

class SearchItemAdapter(
    private val searchType: SearchType,
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null,
    private val onItemFocused: ((View, Int) -> Unit)? = null,
    private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    private val onItemsChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), TvFocusableAdapter {

    private val portraitDetectedUrls = mutableSetOf<String>()
    private val items = mutableListOf<SearchItemModel>()

    fun setItems(list: List<SearchItemModel>) {
        if (items.isEmpty() && list.isNotEmpty()) {
            items.addAll(list)
            notifyItemRangeInserted(0, list.size)
            return
        }
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun focusableItemCount(): Int = itemCount

    override fun getItemCount(): Int = items.size

    override fun stableKeyAt(position: Int): String? {
        return items.getOrNull(position)?.let(::searchItemKey)
    }

    override fun findPositionByStableKey(key: String): Int {
        return items.indexOfFirst { searchItemKey(it) == key }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
    }

    private fun removeBlockedItems(blockedName: String) {
        val filtered = items.filter {
            val authorName = it.author.ifBlank { it.uname }
            !authorName.equals(blockedName, ignoreCase = true)
        }
        if (filtered.size == items.size) return
        setItems(filtered)
        onItemsChanged?.invoke()
    }

    private fun RecyclerView.ViewHolder.showCardMenu() {
        val position = bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val item = items[position]
        val video = VideoModel(
            aid = item.aid,
            bvid = item.bvid,
            title = item.decodedTitle,
            pic = item.pic.ifBlank { item.cover },
            goto = if (searchType == SearchType.LiveRoom || item.roomId > 0L) "live" else "av",
            roomId = item.roomId,
            isLive = searchType == SearchType.LiveRoom || item.roomId > 0L,
            owner = Owner(
                mid = item.mid.toLongOrNull() ?: 0L,
                name = item.author.ifBlank { item.uname }
            )
        )
        VideoCardMenuDialog(
            context = itemView.context,
            video = video,
            onDislikeVideo = {
                val key = searchItemKey(item)
                val filtered = items.filter { searchItemKey(it) != key }
                if (filtered.size != items.size) {
                    setItems(filtered)
                    onItemsChanged?.invoke()
                }
            },
            onDislikeUp = { upName -> removeBlockedItems(upName) }
        ).show()
    }

    override fun getItemViewType(position: Int): Int = when (searchType) {
        SearchType.Video -> VIEW_TYPE_VIDEO
        SearchType.LiveRoom -> VIEW_TYPE_LIVE
        SearchType.Animation,
        SearchType.FilmAndTv -> VIEW_TYPE_SERIES
        SearchType.User -> VIEW_TYPE_USER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                VideoCardPerfLogger.measureInflate("SearchItemAdapter.light") {
                    VideoLightCardFactory.create(parent, source = "SearchItemAdapter.light")
                }
            )

            VIEW_TYPE_LIVE -> LiveViewHolder(
                CellLiveRoomBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_USER -> UserViewHolder(
                CellUserBinding.inflate(inflater, parent, false)
            )

            else -> SeriesViewHolder(
                CellMovieBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is VideoViewHolder -> holder.bind(item)
            is LiveViewHolder -> holder.bind(item)
            is SeriesViewHolder -> holder.bind(item)
            is UserViewHolder -> holder.bind(item)
        }
    }

    private inner class VideoViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        private var currentItem: SearchItemModel? = null

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.progressBar.clipToOutline = true
            views.progressBar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
            bindInteraction(views.root)
        }

        fun bind(item: SearchItemModel) {
            currentItem = item
            views.textView.text = item.decodedTitle
            val ownerName = item.author.ifBlank { item.uname }
            val publishText = item.pubDate.takeIf { it > 0L }?.let(TimeUtils::formatRelativeTime).orEmpty()
            val ownerLine = buildString {
                if (ownerName.isNotBlank()) {
                    append(ownerName)
                }
                if (publishText.isNotBlank()) {
                    if (isNotEmpty()) {
                        append(" · ")
                    }
                    append(publishText)
                }
            }

            val playCount = item.play.takeIf { it > 0L } ?: item.online

            val coverUrl = item.pic.ifBlank { item.cover }
            val cachedPortrait = coverUrl in portraitDetectedUrls
            val needPortraitDetect = item.dimension?.isPortrait != true && !cachedPortrait
            if (item.dimension?.isPortrait == true) {
                views.ownerRow.bind(
                    ownerText = ownerLine,
                    showAvatar = false,
                    badgeText = "竖屏"
                )
            } else {
                views.ownerRow.bind(
                    ownerText = ownerLine,
                    showAvatar = ownerName.isNotBlank()
                )
            }
            views.progressBar.visibility = View.GONE
            views.iconHistoryDevice?.visibility = View.GONE
            views.textHistoryViewTime?.visibility = View.GONE
            views.coverMetaOverlay.bind(
                playCountText = if (playCount > 0L) NumberUtils.formatCount(playCount) else "",
                showPlayCount = playCount > 0L,
                danmakuText = if (item.danmaku > 0L) NumberUtils.formatCount(item.danmaku) else "",
                showDanmakuCount = item.danmaku > 0L,
                durationText = item.duration,
                showChargeBadge = false,
                showInteractionBadge = false
            )

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = coverUrl,
                deferUntilPreDraw = true,
                onPortraitDetected = if (needPortraitDetect) { isPortrait ->
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION
                        && currentItem === item && isPortrait
                    ) {
                        portraitDetectedUrls.add(coverUrl)
                        views.ownerRow.bind(
                            ownerText = ownerLine,
                            showAvatar = false,
                            badgeText = "竖屏"
                        )
                    }
                } else null
            )
        }
    }

    private inner class LiveViewHolder(
        private val binding: CellLiveRoomBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
            binding.imageView.clipToOutline = true
            binding.imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                }
            }
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.decodedTitle
            binding.textPlayCount.text = NumberUtils.formatCount(item.online)

            val ownerName = item.uname.ifBlank { item.author }
            val hasOwner = ownerName.isNotBlank()
            binding.imageAvatar.visibility = if (hasOwner) View.VISIBLE else View.GONE
            binding.textViewOwner.visibility = if (hasOwner) View.VISIBLE else View.GONE
            if (hasOwner) {
                binding.textViewOwner.text = ownerName
            }

            ImageLoader.load(
                imageView = binding.imageView,
                url = normalizeUrl(item.cover.ifBlank { item.pic }),
                placeholder = R.color.thirdBackgroundColor
            )
        }
    }

    private inner class SeriesViewHolder(
        private val binding: CellMovieBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.decodedTitle
            binding.textSub.text = item.indexShow.ifBlank { item.desc }
            binding.textBadge.visibility = if (item.type.contains("media")) View.VISIBLE else View.GONE
            binding.textBadge.text = item.type.removePrefix("media_").ifBlank { "PGC" }

            ImageLoader.load(
                imageView = binding.imageView,
                url = normalizeUrl(item.cover.ifBlank { item.pic }),
                placeholder = R.color.thirdBackgroundColor
            )
        }
    }

    private inner class UserViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.uname
            binding.textSub.text = item.usign

            ImageLoader.loadCircle(
                imageView = binding.imageView,
                url = normalizeUrl(item.upic),
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
            binding.imageView.setBadge(
                officialVerifyType = item.officialVerify?.type ?: -1
            )
        }
    }

    private fun RecyclerView.ViewHolder.bindInteraction(
        view: View
    ) {
        val handler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var longPressTriggered = false

        val keyListener = View.OnKeyListener { targetView, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            longPressTriggered = false
                            longPressRunnable = Runnable {
                                longPressTriggered = true
                                showCardMenu()
                            }
                            handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                }
                false
            } else {
                onItemDpad?.invoke(targetView, keyCode, event) == true
            }
        }

        view.setOnClickListener {
            if (longPressTriggered) {
                longPressTriggered = false
                return@setOnClickListener
            }
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemFocused?.invoke(view, position)
                onItemClick(SearchResultEntry(searchType, items[position]))
            }
        }
        view.setOnFocusChangeListener { targetView, hasFocus ->
            val position = bindingAdapterPosition
            if (hasFocus && position != RecyclerView.NO_POSITION) {
                onItemFocused?.invoke(targetView, position)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressRunnable = Runnable {
                        longPressTriggered = true
                        showCardMenu()
                    }
                    handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }
            }
            false
        }
        VideoCardFocusHelper.bindSidebarExit(
            view,
            onTopEdgeUp = {
                onTopEdgeUp?.invoke(view) == true
            },
            handleListDpadDown = false,
            chainedListener = keyListener
        )
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun searchItemKey(item: SearchItemModel): String {
        return when {
            item.aid > 0L -> "aid:${item.aid}"
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.id > 0L -> "id:${item.id}"
            else -> "title:${item.title}"
        }
    }

    private companion object {
        const val VIEW_TYPE_VIDEO = 0x530100
        const val VIEW_TYPE_LIVE = 0x530101
        const val VIEW_TYPE_SERIES = 0x530102
        const val VIEW_TYPE_USER = 0x530103
    }
}
