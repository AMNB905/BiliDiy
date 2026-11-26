package com.gdk.biliapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 首页「继续观看」横排卡片 */
class HistoryAdapter(
    private val onClick: (History.Item) -> Unit,
    private val onChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = ArrayList<History.Item>()

    fun submit(list: List<History.Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.cover.tag = item.key
        ImageLoader.load(holder.cover, item.cover)
        holder.title.text = item.title
        val percent = if (item.dur > 0) (item.pos * 100 / item.dur).coerceIn(0, 100).toInt() else 0
        holder.pos.text = when {
            percent > 0 -> "看到 ${Ui.fmtDur(item.pos / 1000)} · $percent%"
            else -> "看到 ${Ui.fmtDur(item.pos / 1000)}"
        }
        holder.progress.progress = percent
        holder.progress.visibility = if (percent > 0) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            History.remove(holder.itemView.context, item.key)
            submit(History.list(holder.itemView.context))
            onChanged?.invoke()
            Ui.toast(holder.itemView.context, "已从历史中移除")
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val pos: TextView = v.findViewById(R.id.tvPos)
        val progress: ProgressBar = v.findViewById(R.id.pbProgress)
    }
}
