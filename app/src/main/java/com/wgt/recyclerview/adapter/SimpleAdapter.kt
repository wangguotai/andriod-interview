package com.wgt.recyclerview.adapter

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wgt.recyclerview.util.ActivityHelper
import kotlin.math.roundToInt


class SimpleAdapter(private val mData: MutableList<String>) :
    RecyclerView.Adapter<SimpleAdapter.SimpleViewHolder>() {
    class SimpleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView as TextView
    }

    private val drawable: Drawable = ActivityHelper.generatorItemDrawable()

    /**
     * Is group header 每5个item我们给一个group header的标签
     *
     * @param position
     * @return
     */
    fun isGroupHeader(position: Int): Boolean = position % 5 == 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimpleViewHolder {
        // 获取View 并放置在ViewHolder中
        return SimpleViewHolder(TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ActivityHelper.dpToPx(80f).roundToInt()
            )
            gravity = Gravity.CENTER
            // 设置边框
            background = drawable
        })
    }

    override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {
        holder.apply {
            holder.textView.text = mData[position]
            textView.setOnClickListener {
                notifyItemRemoved(layoutPosition)
                mData.removeAt(layoutPosition)
//                holder.itemView.context.apply {
//                    startActivity(
//                        Intent(
//                            this, FragmentDemoActivity::class.java
//                        )
//                    )
//                }
            }
        }
    }

    override fun getItemCount(): Int {
        return mData.size
    }
}