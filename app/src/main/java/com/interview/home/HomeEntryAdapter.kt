package com.interview.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 首页功能入口列表适配器。整卡点击即跳转 [HomeEntry.activityClass]。
 */
class HomeEntryAdapter(
    private var entries: List<HomeEntry>,
    private val onClick: (HomeEntry) -> Unit,
) : RecyclerView.Adapter<HomeEntryAdapter.VH>() {

    fun submit(newEntries: List<HomeEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_entry, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title
        holder.subtitle.text = entry.subtitle
        holder.tag.text = entry.tag
        holder.tag.visibility = if (entry.tag.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = entries.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.home_entry_title)
        val subtitle: TextView = itemView.findViewById(R.id.home_entry_subtitle)
        val tag: TextView = itemView.findViewById(R.id.home_entry_tag)
    }
}
