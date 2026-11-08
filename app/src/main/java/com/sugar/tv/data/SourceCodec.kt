package com.sugar.tv.data

import androidx.annotation.Keep
import com.sugar.tv.data.model.ChannelSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 信号源列表与 JSON 字符串的互转工具。
 *
 * 导入频道持久化到 Room 时把 [ChannelSource] 列表序列化为本格式；
 * 编码采用 Android 自带的 org.json，字段名 `label` / `url` 与业务模型一致。
 */
@Keep
object SourceCodec {

    private const val KEY_LABEL = "label"
    private const val KEY_URL = "url"

    /** 把信号源列表序列化为 JSON 数组字符串；空列表序列化为 `[]`。 */
    fun encode(sources: List<ChannelSource>): String {
        val array = JSONArray()
        for (source in sources) {
            array.put(
                JSONObject().apply {
                    put(KEY_LABEL, source.label)
                    put(KEY_URL, source.url)
                },
            )
        }
        return array.toString()
    }

    /**
     * 把 JSON 数组字符串反序列化为信号源列表。
     *
     * 容忍单个元素的字段缺失或类型异常：跳过非字符串或非法 HTTP(S) 地址，不影响其他线路。
     *
     * @return 解析出的信号源列表；整体格式非法时返回空列表
     */
    fun decode(json: String?): List<ChannelSource> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    // optString 会把数字与 JSON null 转成文本，需先校验原始类型与地址结构。
                    val url = (item.opt(KEY_URL) as? String)?.trim() ?: continue
                    if (parseHttpUri(url) == null) continue
                    add(
                        ChannelSource(
                            label = (item.opt(KEY_LABEL) as? String)?.takeIf { it.isNotBlank() } ?: url,
                            url = url,
                        ),
                    )
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
