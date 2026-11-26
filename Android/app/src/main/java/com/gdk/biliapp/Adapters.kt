package com.gdk.biliapp

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/** 从 JSON 里取"可能是字符串、可能是数组"的展示字段 */
internal fun fieldText(o: JSONObject, key: String): String {
    val v = o.opt(key) ?: return ""
    return when (v) {
        is String -> v
        is JSONArray -> {
            val parts = ArrayList<String>()
            for (i in 0 until v.length()) {
                val s = when (val item = v.opt(i)) {
                    is JSONObject -> item.optString("name", "").ifEmpty { item.optString("text", "") }
                    is String -> item
                    else -> ""
                }
                if (s.isNotEmpty()) parts.add(s)
            }
            parts.joinToString("/")
        }
        else -> ""
    }
}

/** 投稿视频卡片（2 列网格） */
class VideoCardAdapter(
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<VideoCardAdapter.VH>() {

    private val items = ArrayList<JSONObject>()

    @SuppressLint("NotifyDataSetChanged")
    fun reset(arr: JSONArray?) {
        items.clear()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        notifyDataSetChanged()
    }

    fun append(arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val start = items.size
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        if (items.size > start) notifyItemRangeInserted(start, items.size - start)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.boxCover)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val duration: TextView = v.findViewById(R.id.tvDuration)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val o = items[position]
        h.title.text = Ui.stripEm(o.optString("title", ""))

        val author = o.optString("author", fieldText(o, "owner"))
        val play = o.optLong("play", o.optLong("view", 0))
        h.meta.text = if (author.isNotEmpty()) "$author · ${Ui.fmtCount(play)}" else "${Ui.fmtCount(play)}播放"

        val dur = o.optString("duration", "")
        h.duration.text = dur
        h.duration.visibility = if (dur.isNotEmpty()) View.VISIBLE else View.GONE

        h.box.post {
            val lp = h.box.layoutParams
            lp.height = h.box.width * 9 / 16
            h.box.layoutParams = lp
        }
        ImageLoader.load(h.cover, o.optString("pic", ""))
        h.itemView.setOnClickListener { onClick(o) }
    }
}

/** 番剧 / 影视卡片（2 列竖版海报） */
class PgcCardAdapter(
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<PgcCardAdapter.VH>() {

    private val items = ArrayList<JSONObject>()

    @SuppressLint("NotifyDataSetChanged")
    fun reset(arr: JSONArray?) {
        items.clear()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        notifyDataSetChanged()
    }

    fun append(arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val start = items.size
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        if (items.size > start) notifyItemRangeInserted(start, items.size - start)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.boxPoster)
        val cover: ImageView = v.findViewById(R.id.ivPoster)
        val score: TextView = v.findViewById(R.id.tvScore)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pgc, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val o = items[position]
        h.title.text = Ui.stripEm(o.optString("title", ""))

        val parts = ArrayList<String>()
        val areas = fieldText(o, "areas")
        if (areas.isNotEmpty()) parts.add(areas)
        val styles = Ui.stripEm(fieldText(o, "styles"))
        if (styles.isNotEmpty()) parts.add(styles)
        val show = o.optString("index_show", "")
        if (show.isNotEmpty()) parts.add(show)
        if (parts.isEmpty()) o.optString("season_type_name", "").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        h.meta.text = parts.joinToString(" · ")

        val score = o.optJSONObject("media_score")?.optDouble("score", 0.0) ?: 0.0
        if (score > 0) {
            h.score.text = "%.1f".format(score)
            h.score.visibility = View.VISIBLE
        } else {
            h.score.visibility = View.GONE
        }

        h.box.post {
            val lp = h.box.layoutParams
            lp.height = h.box.width * 3 / 2
            h.box.layoutParams = lp
        }
        ImageLoader.load(h.cover, o.optString("cover", ""))
        h.itemView.setOnClickListener { onClick(o) }
    }
}
