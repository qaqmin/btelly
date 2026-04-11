package com.btelly.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.btelly.databinding.CellEpisodeBinding
import com.btelly.model.video.VideoModel
import com.btelly.core.ui.base.BaseAdapter
import com.btelly.core.ui.image.ImageLoader
import com.btelly.core.common.format.NumberUtils
import java.util.Locale

class EpisodeListAdapter : BaseAdapter<VideoModel, EpisodeListAdapter.EpisodeViewHolder>() {

    private var onItemClickListener: ((View, VideoModel) -> Unit)? = null

    fun setOnItemClickListener(listener: (View, VideoModel) -> Unit) {
        onItemClickListener = listener
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = CellEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EpisodeViewHolder(binding) { view, item ->
            onItemClickListener?.invoke(view, item)
        }
    }

    override fun onBindContentViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    class EpisodeViewHolder(
        private val binding: CellEpisodeBinding,
        private val onItemClick: (View, VideoModel) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        private var currentItem: VideoModel? = null

        init {
            binding.root.setOnClickListener {
                currentItem?.let { onItemClick(binding.root, it) }
            }
        }

        fun bind(video: VideoModel, position: Int) {
            currentItem = video

            binding.textTitle.text = video.title
            binding.textPosition.text = String.format(Locale.getDefault(), "%02d", position + 1)

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = video.coverUrl
            )

            if (video.durationValue > 0) {
                binding.textBadge.text = NumberUtils.formatDuration(video.durationValue)
                binding.textBadge.visibility = View.VISIBLE
            } else {
                binding.textBadge.visibility = View.GONE
            }
        }
    }
}
