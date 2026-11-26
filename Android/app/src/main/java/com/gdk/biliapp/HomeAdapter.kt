package com.gdk.biliapp

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页列表：把「继续观看」作为列表的第一项，跟着内容一起滚走，
 * 不再固定在顶部挤压内容区；搜索时整块隐藏，回到热门时再出现。
 */
class HomeAdapter(
    private val onVideo: (JSONObject) -> Unit,
    private val onPgc: (JSONObject) -> Unit,
    private val onHistory: (History.Item) -> Unit,
    private val onClearHistory: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class ContentMode { VIDEO, PGC }

    private val content = ArrayList<JSONObject>()
    private var mode = ContentMode.VIDEO
    private var history: List<History.Item> = emptyList()
    private var showHistory = true
    private var titleText = "热门推荐"
    private var titleKicker = "TRENDING"
    private var titleSub = "此刻，大家都在看。"

    private fun hasHeader(): Boolean = showHistory && history.isNotEmpty()

    private fun titlePosition(): Int = if (hasHeader()) 1 else 0

    private fun contentOffset(): Int = titlePosition() + 1

    /** 占满两列的位置：历史横排 + 分区标题 */
    fun isFullSpan(position: Int): Boolean = position < contentOffset()

    val contentCount: Int get() = content.size

    @SuppressLint("NotifyDataSetChanged")
    fun setTitle(text: String, subtitle: String = "此刻，大家都在看。", kicker: String = "TRENDING") {
        titleText = text
        titleSub = subtitle
        titleKicker = kicker
        notifyItemChanged(titlePosition())
    }

    fun setContentMode(m: ContentMode) {
        if (mode == m) return
        mode = m
        content.clear()
        notifyDataSetChanged()
    }

    fun setHistoryVisible(visible: Boolean) {
        if (showHistory == visible) return
        val before = hasHeader()
        showHistory = visible
        val after = hasHeader()
        when {
            !before && after -> notifyItemInserted(0)
            before && !after -> notifyItemRemoved(0)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitHistory(items: List<History.Item>) {
        val before = hasHeader()
        history = items
        val after = hasHeader()
        when {
            before && after -> notifyItemChanged(0)
            !before && after -> notifyItemInserted(0)
            before && !after -> notifyItemRemoved(0)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun reset(arr: JSONArray?) {
        content.clear()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { content.add(it) }
        notifyDataSetChanged()
    }

    fun append(arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val start = content.size
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { content.add(it) }
        if (content.size > start) {
            notifyItemRangeInserted(contentOffset() + start, content.size - start)
        }
    }

    override fun getItemCount(): Int = contentOffset() + content.size

    override fun getItemViewType(position: Int): Int = when {
        hasHeader() && position == 0 -> TYPE_HEADER
        position == titlePosition() -> TYPE_TITLE
        mode == ContentMode.VIDEO -> TYPE_VIDEO
        else -> TYPE_PGC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                inflater.inflate(R.layout.item_history_section, parent, false),
                onHistory,
                onClearHistory,
                onChanged = { submitHistory(History.list(parent.context)) }
            )
            TYPE_TITLE -> TitleVH(inflater.inflate(R.layout.item_home_title, parent, false))
            TYPE_PGC -> PgcVH(inflater.inflate(R.layout.item_pgc, parent, false))
            else -> VideoVH(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> holder.bind(history)
            is TitleVH -> holder.bind(titleKicker, titleText, titleSub)
            is VideoVH -> bindVideo(holder, content[position - contentOffset()])
            is PgcVH -> bindPgc(holder, content[position - contentOffset()])
        }
    }

    private fun bindVideo(h: VideoVH, o: JSONObject) {
        val isCourse = o.optString("type") == "ketang"
        h.title.text = Ui.stripEm(o.optString("title", ""))
        val author = o.optString("author", fieldText(o, "owner"))
        val play = o.optLong("play", o.optLong("view", 0))
        val course = if (isCourse) {
            val count = o.optString("episode_count_text", "课程")
            " · $count"
        } else ""
        h.meta.text = if (author.isNotEmpty()) {
            "$author · ${Ui.fmtCount(play)}播放$course"
        } else {
            "${Ui.fmtCount(play)}播放$course"
        }

        val dur = o.optString("duration", "")
        h.duration.text = if (isCourse) "课程" else dur
        h.duration.visibility = if (isCourse || dur.isNotEmpty()) View.VISIBLE else View.GONE

        h.box.post {
            val lp = h.box.layoutParams
            lp.height = h.box.width * 9 / 16
            h.box.layoutParams = lp
        }
        ImageLoader.load(h.cover, o.optString("pic", ""))
        Ui.pressScale(h.itemView)
        h.itemView.setOnClickListener { onVideo(o) }
    }

    private fun bindPgc(h: PgcVH, o: JSONObject) {
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
        Ui.pressScale(h.itemView)
        h.itemView.setOnClickListener { onPgc(o) }
    }

    private class HeaderVH(
        v: View,
        onHistory: (History.Item) -> Unit,
        onClear: () -> Unit,
        onChanged: () -> Unit
    ) : RecyclerView.ViewHolder(v) {
        private val count: TextView = v.findViewById(R.id.tvHistoryCount)
        private val inner: HistoryAdapter
        private val list: RecyclerView = v.findViewById(R.id.rvHistoryInline)

        init {
            inner = HistoryAdapter(onHistory, onChanged)
            list.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
            list.adapter = inner
            v.findViewById<TextView>(R.id.btnHistoryClear).setOnClickListener { onClear() }
        }

        fun bind(items: List<History.Item>) {
            inner.submit(items)
            count.text = "${items.size} 条记录"
        }
    }

    private class TitleVH(v: View) : RecyclerView.ViewHolder(v) {
        private val kicker: TextView = v.findViewById(R.id.tvSectionKicker)
        private val title: TextView = v.findViewById(R.id.tvSectionTitle)
        private val subtitle: TextView = v.findViewById(R.id.tvSectionSub)

        fun bind(kickerText: String, titleText: String, subText: String) {
            kicker.text = kickerText
            title.text = titleText
            subtitle.text = subText
        }
    }

    private class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.boxCover)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val duration: TextView = v.findViewById(R.id.tvDuration)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    private class PgcVH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.boxPoster)
        val cover: ImageView = v.findViewById(R.id.ivPoster)
        val score: TextView = v.findViewById(R.id.tvScore)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_VIDEO = 1
        private const val TYPE_PGC = 2
        private const val TYPE_TITLE = 3
    }
}
