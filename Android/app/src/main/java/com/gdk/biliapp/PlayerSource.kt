package com.gdk.biliapp

import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * 清单文件走 file://，媒体流走 https 并注入 Referer/UA 以通过 B 站防盗链。
 */
class DualDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = DualDataSource()
}

class DualDataSource : BaseDataSource(true) {

    private var inner: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val ds: DataSource = if (dataSpec.uri.scheme == "file") {
            FileDataSource()
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(Bili.UA)
                .setDefaultRequestProperties(mapOf("Referer" to Bili.currentReferer))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000)
                .createDataSource()
        }
        inner = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        inner?.read(buffer, offset, length) ?: throw IllegalStateException("not open")

    override fun getUri() = inner?.uri

    override fun close() {
        inner?.close()
        inner = null
    }
}
