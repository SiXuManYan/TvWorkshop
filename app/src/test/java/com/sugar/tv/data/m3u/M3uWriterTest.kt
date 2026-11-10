package com.sugar.tv.data.m3u

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表导出回归：验证多线路回读、令牌原样保留和元数据不能注入额外播放指令。 */
class M3uWriterTest {

    /** 同名多线路保持顺序，UTF-8 名称、分组逗号和 URL 查询参数可被原解析器完整回读。 */
    @Test
    fun write_preservesNamesGroupsAndOrderedSources() {
        val entries = listOf(
            entry(name = "测试, HD", group = "World, News", url = "https://example.com/live.m3u8?token=a%2Bb&x=1"),
            entry(name = "测试, HD", group = "World, News", url = "https://example.com/backup.m3u8"),
            entry(name = "第二台", group = null, url = "https://example.com/two.m3u8"),
        )
        val writer = StringWriter()

        M3uWriter.write(entries = entries, writer = writer)

        assertTrue(writer.toString().startsWith("#EXTM3U\n"))
        assertEquals(entries, M3uParser.parse(text = writer.toString()))
    }

    /** 分组引号降为普通单引号，名称换行降为空格，不能拼接出额外的 EXTINF。 */
    @Test
    fun write_sanitizesMetadataWithoutAddingChannels() {
        val writer = StringWriter()
        M3uWriter.write(
            entries = listOf(
                entry(
                    name = "频道\n#EXTINF:-1,伪造",
                    group = "News \"HD\"\r\nGroup",
                    url = "https://example.com/real.m3u8",
                ),
            ),
            writer = writer,
        )

        val parsed = M3uParser.parse(text = writer.toString()).single()
        assertEquals("频道 #EXTINF:-1,伪造", parsed.name)
        assertEquals("News 'HD'  Group", parsed.group)
        assertEquals("https://example.com/real.m3u8", parsed.url)
    }

    /** 地址中的换行必须拒绝，不能被解释为另一条地址或元数据。 */
    @Test(expected = IllegalArgumentException::class)
    fun write_rejectsMultilineUrls() {
        M3uWriter.write(
            entries = listOf(entry(name = "非法", group = null, url = "https://example.com/one\nhttps://example.com/two")),
            writer = StringWriter(),
        )
    }

    /** 空列表只产生标准头，不伪造任何频道或地址。 */
    @Test
    fun write_emptyPlaylistHasOnlyHeader() {
        val writer = StringWriter()
        M3uWriter.write(entries = emptyList(), writer = writer)
        assertEquals("#EXTM3U\n", writer.toString())
    }

    /** 构造与导入表能力一致的条目，不虚构尚未持久化的 EPG 字段。 */
    private fun entry(name: String, group: String?, url: String): M3uEntry = M3uEntry(
        name = name,
        group = group,
        tvgId = null,
        tvgName = null,
        url = url,
    )
}
