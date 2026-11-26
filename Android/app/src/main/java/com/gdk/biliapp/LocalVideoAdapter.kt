package com.gdk.biliapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地下载列表适配器：点击播放，长按删除 */
class LocalVideoAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<LocalVideoAdapter.VH>() {

    private val items = ArrayList<File>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun submit(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_video, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        holder.name.text = file.nameWithoutExtension
        holder.meta.text = "${fmtSize(file.length())} · ${dateFmt.format(Date(file.lastModified()))}"
        holder.itemView.setOnClickListener { onClick(file) }
        holder.itemView.setOnLongClickListener {
            onLongClick(file)
            true
        }
        holder.play.setOnClickListener { onClick(file) }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val play: TextView = view.findViewById(R.id.btnPlayLocal)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
