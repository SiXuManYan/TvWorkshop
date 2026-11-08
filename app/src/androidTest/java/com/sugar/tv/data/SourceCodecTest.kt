package com.sugar.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sugar.tv.data.model.ChannelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用 Android 的 org.json 实现检查历史持久化格式和坏元素隔离。 */
@RunWith(AndroidJUnit4::class)
class SourceCodecTest {

    /** JSON null、数字与无主机地址应跳过，单个坏元素不能污染其他线路。 */
    @Test
    fun decode_skipsInvalidTypesAndAddresses() {
        val sources = SourceCodec.decode(
            json = """
                [
                  {"label":"空值","url":null},
                  {"label":"数字","url":123},
                  {"label":"无主机","url":"http:invalid"},
                  {"label":"主源","url":"https://example.com/live.m3u8?token=abc"},
                  {"label":123,"url":"https://example.com/backup.m3u8"}
                ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ChannelSource(label = "主源", url = "https://example.com/live.m3u8?token=abc"),
                ChannelSource(
                    label = "https://example.com/backup.m3u8",
                    url = "https://example.com/backup.m3u8",
                ),
            ),
            sources,
        )
    }

    /** 整体 JSON 格式损坏时返回空列表，仓库据此丢弃该频道。 */
    @Test
    fun decode_returnsEmptyForMalformedJson() {
        assertTrue(SourceCodec.decode(json = "[{broken]").isEmpty())
    }
}
