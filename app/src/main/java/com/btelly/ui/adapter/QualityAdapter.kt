package com.btelly.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.feature.marmot.domain.HzItem

/**
 * 画质选择列表适配器（横向，D-pad 友好）。
 *
 * 数据来源：页面 JS 通过 `_api.message("videoQuality", data)` 上报的 [HzItem] 列表。
 * 点击项执行 [HzItem.action] 切换画质。
 *
 * 当前画质高亮：对标 utao `ExitMenuBuilder.findCurrentHzIndex` —— 由 [currentSelectedIndex]
 * 指定（调用方根据 [HzItem.isCurrent] 或用户上次选择计算），而非依赖 item 自身标记，
 * 因为部分云端 JS 上报时根本不带 isCurrent 字段。
 */
class QualityAdapter(
    private val items: List<HzItem>,
    private val onClick: (HzItem) -> Unit
) : RecyclerView.Adapter<QualityAdapter.VH>() {

    /** 当前画质的索引（高亮显示）。-1 表示无高亮。对标 utao selectedHzIndex。 */
    var currentSelectedIndex: Int = -1

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_marmot_quality, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.name
        // 当前画质高亮（对标 utao selectedHzIndex 项画 ●）
        val isCurrent = position == currentSelectedIndex
        holder.text.isSelected = isCurrent
        holder.text.alpha = if (isCurrent) 1.0f else 0.85f
        holder.text.setOnClickListener { onClick(item) }
        holder.text.setOnFocusChangeListener { v, hasFocus ->
            // 焦点态由 bg_menu_button 的 state_focused 处理；这里只做透明度辅助
            v.alpha = if (hasFocus || position == currentSelectedIndex) 1.0f else 0.85f
        }
    }

    override fun getItemCount(): Int = items.size
}
