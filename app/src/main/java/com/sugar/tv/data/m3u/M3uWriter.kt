package com.sugar.tv.data.m3u

import java.io.Writer

/** 将频道线路写成 UTF-8 m3u 文本；字符编码和流的关闭由仓库负责。 */
object M3uWriter {

    /** 每条线路独立声明 EXTINF，保持导入顺序、完整 URL 和同名多线路的可回读性。 */
    fun write(entries: List<M3uEntry>, writer: Writer) {
        writer.write("#EXTM3U\n")
        entries.forEach { entry ->
            // 元数据中的换行不能变成新的播放指令；地址必须保持原样且只占一行。
            require('\r' !in entry.url && '\n' !in entry.url) { "播放地址不能包含换行" }
            writer.write("#EXTINF:-1")
            entry.group?.takeIf { it.isNotBlank() }?.let { group ->
                writer.write(" group-title=\"${singleLine(group).replace('\"', '\'')}\"")
            }
            writer.write(",${singleLine(entry.name)}\n${entry.url}\n")
        }
    }

    /** 只规整行分隔符，保留频道名中的逗号、引号与 Unicode 字符。 */
    private fun singleLine(value: String): String = value.replace('\r', ' ').replace('\n', ' ')
}
