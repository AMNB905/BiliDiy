package com.gdk.biliapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** 本地下载列表入口：只展示 App 自己下载完成的 mp4 */
class LocalVideosActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var header: View
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvPath: TextView

    private val adapter = LocalVideoAdapter(
        onClick = { file -> openPlayer(file) },
        onLongClick = { file -> confirmDelete(file) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_local_videos)

        pageRoot = findViewById(R.id.pageRoot)
        header = findViewById(R.id.header)
        rv = findViewById(R.id.rvLocal)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCount = findViewById(R.id.tvCount)
        tvPath = findViewById(R.id.tvPath)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        tvPath.text = "保存在 " + VideoDownloader.outputDir(this).absolutePath

        applyInsets()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val files = VideoDownloader.listDownloads(this)
        adapter.submit(files)
        tvCount.text = if (files.isEmpty()) "0 部影片" else "${files.size} 部影片"
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openPlayer(file: File) {
        if (!file.exists()) {
            Ui.toast(this, "文件不存在，可能已被删除")
            refresh()
            return
        }
        startActivity(
            Intent(this, LocalPlayerActivity::class.java)
                .putExtra("path", file.absolutePath)
                .putExtra("title", file.nameWithoutExtension)
        )
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除本地影片")
            .setMessage("确定删除「${file.nameWithoutExtension}」？\n删除后需要重新下载。")
            .setPositiveButton("删除") { _, _ ->
                if (file.delete()) {
                    Ui.toast(this, "已删除")
                    refresh()
                } else {
                    Ui.toast(this, "删除失败，请稍后再试")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val h = Ui.dp(this, 10)
            header.setPadding(Ui.dp(this, 16), bars.top + h, Ui.dp(this, 16), Ui.dp(this, 4))
            rv.setPadding(
                Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), Ui.dp(this, 24) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }
}
