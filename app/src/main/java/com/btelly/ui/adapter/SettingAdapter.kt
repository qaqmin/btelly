package com.btelly.ui.adapter

import com.btelly.core.ui.focus.animateFocusScale
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btelly.core.ui.base.UiCarSpacing
import com.btelly.databinding.CellSettingBinding
import android.widget.CompoundButton
import com.btelly.model.SettingModel
import com.btelly.model.SettingType

class SettingAdapter(
    private val onItemClick: ((position: Int, item: SettingModel) -> Unit)? = null
) : ListAdapter<SettingModel, SettingAdapter.SettingViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION

    companion object {
        private const val PAYLOAD_FOCUS = "payload_focus"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SettingModel>() {
            override fun areItemsTheSame(oldItem: SettingModel, newItem: SettingModel): Boolean {
                return oldItem.title == newItem.title
            }

            override fun areContentsTheSame(oldItem: SettingModel, newItem: SettingModel): Boolean {
                return oldItem.title == newItem.title && oldItem.info == newItem.info
            }
        }
    }

    fun setData(newItems: List<SettingModel>) {
        focusedPosition = RecyclerView.NO_POSITION
        submitList(newItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingViewHolder {
        val binding = CellSettingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // 车机模式间距铺开：设置行触控高度与内外间距乘 boost（电视模式零改动）
        UiCarSpacing.apply(binding.root)
        return SettingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(
        holder: SettingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindContent(getItem(position))
            holder.bindFocusState(position == focusedPosition, position)
        }
    }

    inner class SettingViewHolder(
        private val binding: CellSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 焦点弹簧溢出需要父级不裁剪（否则 1.04 倍放大被 cell 根裁回原尺寸，实测无效）
            binding.root.clipChildren = false
            binding.root.clipToPadding = false
            binding.clickView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(position, currentList[position])
                }
            }
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                // 焦点回调可能发生在 RecyclerView 布局/滚动中（fling 回收 View 时 clearFocus
                // 触发），同步 notifyItemChanged 会抛 IllegalStateException，必须 post 到下一帧。
                fun notifyFocusChanged(pos: Int) {
                    if (pos == RecyclerView.NO_POSITION) return
                    itemView.post {
                        if (pos != RecyclerView.NO_POSITION && pos < itemCount) {
                            notifyItemChanged(pos, PAYLOAD_FOCUS)
                        }
                    }
                }
                if (hasFocus) {
                    val oldFocused = focusedPosition
                    focusedPosition = position
                    if (oldFocused != RecyclerView.NO_POSITION && oldFocused != position) {
                        notifyFocusChanged(oldFocused)
                    }
                    notifyFocusChanged(position)
                    return@setOnFocusChangeListener
                }
                if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    notifyFocusChanged(position)
                }
            }
        }

        fun bind(item: SettingModel, position: Int) {
            bindContent(item)
            bindFocusState(bindingAdapterPosition == focusedPosition, position)
        }

        fun bindContent(item: SettingModel) {
            binding.tvTitle.text = item.title
            binding.tvInfo.text = item.info
            applyType(item)
        }

        /**
         * 按控件类型分叉渲染。核心目的：让用户扫一眼就知道这行是什么控件、点下去会发生什么。
         * 旧实现所有行都是「标题 + 值 + 箭头」，四类控件长得一样。
         */
        private fun applyType(item: SettingModel) {
            when (item.type) {
                SettingType.TOGGLE -> {
                    // 开关：真实控件，不显示箭头（不跳转）。
                    // 状态从 info 派生而非 item.checked：SettingsFragment.toggleSetting()
                    // 一直以来只改 info 再 notifyItemChanged，沿用同一数据源就不必改任何
                    // 既有交互逻辑。info 不是"开/关"时才退回 checked 字段。
                    binding.switchToggle.visibility = android.view.View.VISIBLE
                    binding.tvInfo.visibility = android.view.View.GONE
                    binding.tvAction.visibility = android.view.View.GONE
                    binding.iconArrow.visibility = android.view.View.GONE
                    binding.iconType.visibility = android.view.View.GONE
                    // 先摘监听再赋值：ViewHolder 复用时，isChecked 的赋值回调会把上一行状态带过来
                    binding.switchToggle.setOnCheckedChangeListener(null)
                    val isOn = if (item.info == "开" || item.info == "关") item.info == "开" else item.checked
                    binding.switchToggle.isChecked = isOn
                    // 标题同步反映状态，开关与文字互为冗余（遥控器远距离看清开关有困难）
                    binding.tvTitle.contentDescription = "${item.title}，${if (isOn) "已开启" else "已关闭"}"
                }
                SettingType.ACTION -> {
                    // 操作：强调色动作按钮，点即执行
                    binding.tvAction.visibility = android.view.View.VISIBLE
                    binding.switchToggle.visibility = android.view.View.GONE
                    binding.tvInfo.visibility = android.view.View.GONE
                    binding.iconArrow.visibility = android.view.View.GONE
                    binding.iconType.visibility =
                        if (item.iconRes != 0) android.view.View.VISIBLE else android.view.View.GONE
                    if (item.iconRes != 0) binding.iconType.setImageResource(item.iconRes)
                    binding.tvAction.text = item.info.ifEmpty { "执行" }
                }
                SettingType.INFO -> {
                    // 只读：弱化，无箭头无 chip
                    binding.tvInfo.visibility = android.view.View.VISIBLE
                    binding.switchToggle.visibility = android.view.View.GONE
                    binding.tvAction.visibility = android.view.View.GONE
                    binding.iconArrow.visibility = android.view.View.GONE
                    binding.iconType.visibility =
                        if (item.iconRes != 0) android.view.View.VISIBLE else android.view.View.GONE
                    if (item.iconRes != 0) binding.iconType.setImageResource(item.iconRes)
                }
                SettingType.CHOICE, SettingType.NAV -> {
                    // 选择/跳转：当前值 chip + 箭头
                    binding.tvInfo.visibility = android.view.View.VISIBLE
                    binding.iconArrow.visibility = android.view.View.VISIBLE
                    binding.switchToggle.visibility = android.view.View.GONE
                    binding.tvAction.visibility = android.view.View.GONE
                    binding.iconType.visibility =
                        if (item.iconRes != 0) android.view.View.VISIBLE else android.view.View.GONE
                    if (item.iconRes != 0) binding.iconType.setImageResource(item.iconRes)
                }
                SettingType.SLIDER -> {
                    // 滑杆：当前值 + 箭头提示可调
                    binding.tvInfo.visibility = android.view.View.VISIBLE
                    binding.iconArrow.visibility = android.view.View.VISIBLE
                    binding.switchToggle.visibility = android.view.View.GONE
                    binding.tvAction.visibility = android.view.View.GONE
                    binding.iconType.visibility =
                        if (item.iconRes != 0) android.view.View.VISIBLE else android.view.View.GONE
                    if (item.iconRes != 0) binding.iconType.setImageResource(item.iconRes)
                }
            }
        }

        private fun bindStripe(position: Int) {
            if (position % 2 == 0) {
                binding.clickView.setBackgroundResource(com.btelly.R.drawable.cell_setting_background)
            } else {
                binding.clickView.background = null
            }
        }

        fun bindFocusState(isFocused: Boolean, position: Int) {
            binding.iconArrow.alpha = if (isFocused) 1f else 0.6f
            binding.tvInfo.alpha = if (isFocused) 1f else 0.8f
            binding.tvAction.alpha = if (isFocused) 1f else 0.85f
            if (isFocused) {
                binding.clickView.setBackgroundResource(com.btelly.R.drawable.cell_background)
            } else {
                bindStripe(position)
            }
            binding.clickView.animateFocusScale(isFocused)
        }
    }
}
