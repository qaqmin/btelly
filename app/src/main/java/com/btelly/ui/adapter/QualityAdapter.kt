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
 */
class QualityAdapter(
    private val items: List<HzItem>,
    private val onClick: (HzItem) -> Unit
) : RecyclerView.Adapter<QualityAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_marmot_quality, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.name
        // 当前画质高亮（isCurrent 由页面上报，state_selected 对应 bg_menu_button 高亮项）
        holder.text.isSelected = (item.isCurrent == true)
        holder.text.setOnClickListener { onClick(item) }
        holder.text.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1.0f else 0.6f
        }
    }

    override fun getItemCount(): Int = items.size
}
