package com.gdk.biliapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用户中心的播放记录列表（竖排，不设条数上限） */
class HistoryListAdapter(
    private val onClick: (History.Item) -> Unit,
    private val onDelete: (History.Item) -> Unit
) : RecyclerView.Adapter<HistoryListAdapter.VH>() {

    private val items = ArrayList<History.Item>()
    private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    fun submit(list: List<History.Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        ImageLoader.load(holder.cover, item.cover)
        holder.title.text = item.title
        val percent = if (item.dur > 0) (item.pos * 100 / item.dur).coerceIn(0, 100).toInt() else 0
        holder.progress.progress = percent
        holder.progress.visibility = if (percent > 0) View.VISIBLE else View.GONE
        if (percent > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = "$percent%"
        } else {
            holder.badge.visibility = View.GONE
        }
        holder.pos.text = buildString {
            val cat = when (item.cat) {
                "anime" -> "番剧"
                "movie" -> "电影"
                else -> "视频"
            }
            append(cat).append(" · 看到 ").append(Ui.fmtDur(item.pos / 1000))
            if (item.dur > 0) append(" / ").append(Ui.fmtDur(item.dur / 1000))
        }
        holder.time.text = fmt.format(Date(item.at))
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onDelete(item)
            true
        }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val badge: TextView = v.findViewById(R.id.tvBadge)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val pos: TextView = v.findViewById(R.id.tvPos)
        val time: TextView = v.findViewById(R.id.tvTime)
        val delete: TextView = v.findViewById(R.id.btnDelete)
        val progress: ProgressBar = v.findViewById(R.id.pbProgress)
    }
}
