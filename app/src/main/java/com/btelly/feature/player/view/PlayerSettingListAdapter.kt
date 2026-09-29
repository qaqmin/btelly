package com.btelly.feature.player.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.core.ui.focus.animateFocusScale

internal class PlayerSettingListAdapter(
    private val onClick: (PlayerSettingRow.Item) -> Unit
) : ListAdapter<PlayerSettingRow, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    var currentMenuKey: Int = 0
        private set

    fun submitRows(menuKey: Int, newRows: List<PlayerSettingRow>, onCommitted: Runnable? = null) {
        currentMenuKey = menuKey
        submitList(newRows, onCommitted)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PlayerSettingRow.Header -> VIEW_TYPE_HEADER
            is PlayerSettingRow.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_player_setting_head, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_player_setting, parent, false)
            ItemViewHolder(view, onClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is PlayerSettingRow.Header -> (holder as HeaderViewHolder).bind(row)
            is PlayerSettingRow.Item -> (holder as ItemViewHolder).bind(row)
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.textView)
        private val subTitleView: TextView = view.findViewById(R.id.subTextView)

        fun bind(row: PlayerSettingRow.Header) {
            titleView.text = row.title
            val hasSubTitle = !row.subTitle.isNullOrBlank()
            subTitleView.visibility = if (hasSubTitle) View.VISIBLE else View.GONE
            subTitleView.text = row.subTitle.orEmpty()
        }
    }

    private class ItemViewHolder(
        view: View,
        private val onClick: (PlayerSettingRow.Item) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val iconView: ImageView = view.findViewById(R.id.imageView)
        private val titleView: TextView = view.findViewById(R.id.text_view_primary)
        private val valueView: TextView = view.findViewById(R.id.text_view_sub)
        private val arrowView: ImageView = view.findViewById(R.id.icon_more)
        private val checkView: ImageView = view.findViewById(R.id.icon_check)
        private var currentItem: PlayerSettingRow.Item? = null

        init {
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener {
                currentItem?.let(onClick)
            }
            // 焦点态与 SettingsFragment 的设置页保持同一套：透明度分层 + 轻微弹簧放大。
            // 背景态（强调底 + 左侧强调指示条）由 cell_setting_background 的 state_focused
            // 直接驱动，代码里不再插手背景，避免和 drawable 抢状态。
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animateFocusScale(hasFocus)
                applyFocusedAlpha(hasFocus)
            }
        }

        /** 未聚焦压一档，聚焦位回到实色：扫视列表时选中行自己"跳出来"。 */
        private fun applyFocusedAlpha(focused: Boolean) {
            titleView.alpha = if (focused) 1f else 0.94f
            valueView.alpha = if (focused) 1f else 0.85f
            iconView.alpha = if (focused) 1f else 0.75f
            arrowView.alpha = if (focused) 1f else 0.6f
            // 勾是"当前选中的是这一项"，与焦点无关，保持醒目只做很小衰减
            checkView.alpha = if (focused) 1f else 0.92f
        }

        fun bind(item: PlayerSettingRow.Item) {
            currentItem = item
            itemView.isPressed = false
            itemView.isActivated = false
            itemView.isSelected = false
            itemView.jumpDrawablesToCurrentState()
            titleView.text = item.title
            val hasValue = item.value.isNotBlank()
            valueView.visibility = if (hasValue) View.VISIBLE else View.GONE
            valueView.text = item.value

            val iconRes = item.iconRes
            if (iconRes != null) {
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(iconRes)
            } else {
                iconView.visibility = View.INVISIBLE
            }

            checkView.visibility = if (item.checked) View.VISIBLE else View.GONE
            arrowView.visibility = if (!item.checked && item.showArrow) View.VISIBLE else View.GONE

            // ViewHolder 复用后焦点态可能已经变了，bind 结束必须按当前真实焦点重刷一次
            itemView.animateFocusScale(itemView.isFocused)
            applyFocusedAlpha(itemView.isFocused)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<PlayerSettingRow>() {
        override fun areItemsTheSame(oldItem: PlayerSettingRow, newItem: PlayerSettingRow): Boolean {
            return when {
                oldItem is PlayerSettingRow.Header && newItem is PlayerSettingRow.Header ->
                    oldItem.title == newItem.title && oldItem.subTitle == newItem.subTitle

                oldItem is PlayerSettingRow.Item && newItem is PlayerSettingRow.Item ->
                    oldItem.id == newItem.id

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: PlayerSettingRow, newItem: PlayerSettingRow): Boolean {
            return oldItem == newItem
        }
    }
}
