package com.btelly.feature.cctv

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.core.common.log.VideoCardPerfLogger
import com.btelly.core.ui.focus.VideoCardFocusHelper
import com.btelly.core.ui.focus.tv.TvFocusableAdapter
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.ui.video.VideoCardViews
import com.btelly.core.ui.video.VideoLightCardFactory
import com.btelly.ui.adapter.VideoAdapter

class CctvChannelAdapter(
    private val onItemClick: (Int) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<CctvChannelAdapter.ViewHolder>(), TvFocusableAdapter {

    private val items = CctvChannels.list()
    private var nowPrograms: Map<String, String> = emptyMap()

    init {
        setHasStableIds(true)
    }

    override fun focusableItemCount(): Int = items.size

    override fun stableKeyAt(position: Int): String? = items.getOrNull(position)?.id

    override fun findPositionByStableKey(key: String): Int =
        items.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.number?.toLong() ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("CctvChannelAdapter.light") {
            VideoLightCardFactory.create(parent, source = "CctvChannelAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun submitNowPrograms(programs: Map<String, String>) {
        nowPrograms = programs
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        init {
            views.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            views.imageView.setBackgroundResource(R.drawable.cctv_channel_logo_background)
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = onTopEdgeUp
            )
        }

        fun bind(channel: CctvChannel) {
            views.textLayer.setTitle(channel.title, lines = 1)
            views.textLayer.setOwner(
                ownerText = nowPrograms[channel.id]?.takeIf { it.isNotBlank() } ?: channel.description,
                showAvatar = false,
                show = true
            )
            views.textLayer.clearHistoryTrailing()
            views.progressBar.visibility = View.GONE
            views.coverMetaOverlay.visibility = View.GONE
            views.imageView.clearColorFilter()
            views.imageView.setPadding(
                views.imageView.resources.getDimensionPixelSize(R.dimen.px60),
                views.imageView.resources.getDimensionPixelSize(R.dimen.px40),
                views.imageView.resources.getDimensionPixelSize(R.dimen.px60),
                views.imageView.resources.getDimensionPixelSize(R.dimen.px40)
            )
            ImageLoader.load(
                imageView = views.imageView,
                url = channel.logoUrl,
                placeholder = R.drawable.ic_tv,
                error = R.drawable.ic_tv
            )
        }
    }
}
