package com.sugar.tv.data.m3u

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 订阅解析回归：覆盖地址校验、流式入口、元数据配对与既有频道 id 的兼容性。 */
class M3uParserTest {

    /** 无主机、越界端口和非 HTTP(S) 地址不得以可播放频道入库。 */
    @Test
    fun parse_skipsMalformedHttpAddresses() {
        val text = """
            #EXTM3U
            #EXTINF:-1,无主机
            http:missing-host
            #EXTINF:-1,空主机
            https:///live.m3u8
            #EXTINF:-1,端口越界
            https://example.com:65536/live.m3u8
            #EXTINF:-1,其他协议
            file:///live.m3u8
            #EXTINF:-1,有效频道
            https://example.com/live.m3u8?token=abc
        """.trimIndent()

        val entries = M3uParser.parse(text = text)

        assertEquals(listOf("有效频道"), entries.map { it.name })
        assertEquals("https://example.com/live.m3u8?token=abc", entries.single().url)
    }

    /** HTTP scheme 大小写与 IPv6 主机均为合法输入。 */
    @Test
    fun parse_acceptsUppercaseSchemeAndIpv6() {
        val entries = M3uParser.parse(
            text = """
                #EXTINF:-1,IPv6 频道
                HTTP://[2001:db8::1]:8080/live.m3u8
            """.trimIndent(),
        )

        assertEquals("HTTP://[2001:db8::1]:8080/live.m3u8", entries.single().url)
    }

    /** 流式入口与文本入口应一致处理 BOM、CRLF 和属性值中的逗号。 */
    @Test
    fun parse_readerPreservesQuotedCommasAndMetadata() {
        val text = "\uFEFF#EXTM3U\r\n" +
            "#EXTINF:-1 tvg-id=\"news\" group-title=\"World, News\",News, Live\r\n" +
            "https://example.com/news.m3u8\r\n"
        val fromReader = BufferedReader(StringReader(text)).use { M3uParser.parse(reader = it) }

        assertEquals(M3uParser.parse(text = text), fromReader)
        assertEquals("News, Live", fromReader.single().name)
        assertEquals("World, News", fromReader.single().group)
        assertEquals("news", fromReader.single().tvgId)
    }

    /** 连续 EXTINF 共用地址时应保留每个频道，优化不得改变原有配对规则。 */
    @Test
    fun parse_pairsConsecutiveMetadataWithOneUrl() {
        val entries = M3uParser.parse(
            text = """
                #EXTINF:-1,频道一
                #EXTINF:-1,频道二
                https://example.com/shared.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("频道一", "频道二"), entries.map { it.name })
        assertEquals(1, entries.map { it.url }.distinct().size)
    }

    /** 多行裸 URL 正文应逐条解析，并按路径推导名称。 */
    @Test
    fun parse_acceptsMultipleBareUrls() {
        val entries = M3uParser.parse(
            text = "https://example.com/first.m3u8\nhttps://example.com/second.m3u8",
        )

        assertEquals(listOf("first", "second"), entries.map { it.name })
    }

    /** 空列表及只有无效地址的正文均不得产生候选频道。 */
    @Test
    fun parse_returnsEmptyForInvalidContent() {
        assertTrue(M3uParser.parse(text = "#EXTM3U\n#EXTINF:-1,无效\nhttps://").isEmpty())
    }

    /** 摘要编码优化后仍须命中历史格式，避免重复导入时生成新身份。 */
    @Test
    fun importedChannelId_preservesStoredIdFormat() {
        assertEquals(
            "m3u:36a3f510da8e",
            M3uParser.importedChannelId(name = "测试频道", firstUrl = "https://example.com/live.m3u8"),
        )
        assertNotEquals(
            M3uParser.importedChannelId(name = "测试频道", firstUrl = "https://example.com/live.m3u8"),
            M3uParser.importedChannelId(name = "测试频道", firstUrl = "https://example.com/backup.m3u8"),
        )
    }
}
