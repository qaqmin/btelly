package com.btelly.feature.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btelly.core.common.log.AppLog
import com.btelly.databinding.CellDebugLogBinding
import java.text.SimpleDateFormat
import java.util.Locale

class DebugLogAdapter : ListAdapter<AppLog.LogEntry, DebugLogAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION

    companion object {
        private const val PAYLOAD_FOCUS = "payload_focus"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppLog.LogEntry>() {
            override fun areItemsTheSame(oldItem: AppLog.LogEntry, newItem: AppLog.LogEntry): Boolean {
                return oldItem.timestamp == newItem.timestamp && oldItem.tag == newItem.tag
            }

            override fun areContentsTheSame(oldItem: AppLog.LogEntry, newItem: AppLog.LogEntry): Boolean {
                return oldItem == newItem
            }
        }

        private val LEVEL_COLORS = mapOf(
            AppLog.LogEntry.LEVEL_VERBOSE to Color.GRAY,
            AppLog.LogEntry.LEVEL_DEBUG to Color.GRAY,
            AppLog.LogEntry.LEVEL_INFO to Color.CYAN,
            AppLog.LogEntry.LEVEL_WARN to Color.YELLOW,
            AppLog.LogEntry.LEVEL_ERROR to Color.RED
        )

        private val LEVEL_LABELS = mapOf(
            AppLog.LogEntry.LEVEL_VERBOSE to "V",
            AppLog.LogEntry.LEVEL_DEBUG to "D",
            AppLog.LogEntry.LEVEL_INFO to "I",
            AppLog.LogEntry.LEVEL_WARN to "W",
            AppLog.LogEntry.LEVEL_ERROR to "E"
        )
    }

    fun setData(items: List<AppLog.LogEntry>) {
        focusedPosition = RecyclerView.NO_POSITION
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellDebugLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindFocusState(position == focusedPosition)
        }
    }

    inner class ViewHolder(
        private val binding: CellDebugLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                if (hasFocus) {
                    val old = focusedPosition
                    focusedPosition = position
                    if (old != RecyclerView.NO_POSITION && old != position) {
                        notifyItemChanged(old, PAYLOAD_FOCUS)
                    }
                    notifyItemChanged(position, PAYLOAD_FOCUS)
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    notifyItemChanged(position, PAYLOAD_FOCUS)
                }
            }
        }

        fun bind(entry: AppLog.LogEntry, position: Int) {
            binding.tvTime.text = TIME_FORMAT.format(entry.timestamp)
            binding.tvLevel.text = LEVEL_LABELS[entry.level] ?: "?"
            binding.tvLevel.setTextColor(LEVEL_COLORS[entry.level] ?: Color.GRAY)
            binding.tvTag.text = entry.tag
            binding.tvMessage.text = entry.message
            bindFocusState(position == focusedPosition)
        }

        fun bindFocusState(isFocused: Boolean) {
            binding.root.animate().cancel()
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .setDuration(120L)
                .start()
            if (isFocused) {
                binding.root.setBackgroundResource(com.btelly.R.drawable.cell_background)
            } else {
                binding.root.setBackgroundResource(com.btelly.R.drawable.cell_setting_background)
            }
        }
    }
}