package com.btelly.feature.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.btelly.databinding.CellLiveFollowHeaderBinding

/**
 * 「我的关注」页的分组标题行（正在直播 (N) / 未开播 (M)）。
 * 单条目适配器，文本变化时直接刷新。
 */
class LiveFollowHeaderAdapter(private var text: String = "") :
    RecyclerView.Adapter<LiveFollowHeaderAdapter.Holder>() {

    fun update(newText: String) {
        if (newText == text) return
        text = newText
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = CellLiveFollowHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.textHeader.text = text
    }

    override fun getItemCount(): Int = 1

    class Holder(val binding: CellLiveFollowHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}
