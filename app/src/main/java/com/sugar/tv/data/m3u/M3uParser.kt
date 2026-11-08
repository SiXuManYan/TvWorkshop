package com.sugar.tv.data.m3u

import androidx.annotation.Keep
import com.sugar.tv.data.model.ChannelCategory
import java.io.BufferedReader
import java.net.URI
import java.security.MessageDigest

/**
 * 单条 m3u 条目的解析中间结果。
 *
 * 一个条目对应「一条 #EXTINF（或裸 URL）＋ 一个播放地址」；同名条目的多路信号源
 * 由仓库层合并。
 *
 * @property name 频道名称，取自 #EXTINF 逗号后的文本；裸 URL 条目由 URL 推导
 * @property group 分组名（group-title 属性）；裸 URL 条目为 `null`
 * @property tvgId EPG 标识（tvg-id 属性）；缺失时为 `null`
 * @property tvgName EPG 频道名（tvg-name 属性）；缺失时为 `null`
 * @property url 播放地址
 */
@Keep
data class M3uEntry(
    val name: String,
    val group: String?,
    val tvgId: String?,
    val tvgName: String?,
    val url: String,
)

/**
 * m3u / m3u8 订阅文本解析器。
 *
 * 解析规则：
 * - `#EXTINF` 行读取 `tvg-id` / `tvg-name` / `group-title` 属性与首个未包裹在引号内
 *   的逗号之后的频道名；紧随其后的首个非注释行为该条目的 URL。
 * - 连续多条 #EXTINF 后才出现 URL 时，把该 URL 分配给每条待配对的 #EXTINF。
 * - 未被 #EXTINF 声明的裸 URL 行按「无元数据条目」容错解析，名称从 URL 推导。
 * - 分类映射见 [categorize]。
 *
 * 本对象为纯解析逻辑，不含 IO；调用方需在 Default / IO 调度器上执行。
 */
object M3uParser {

    private const val EXTM3U_HEADER = "#EXTM3U"
    private const val EXTINF_PREFIX = "#EXTINF"
    private const val ATTRIBUTE_REGEX = """([a-zA-Z0-9_-]+)="([^"]*)""""

    // 分类关键词：央视系（含 CNTV 旧域名/CGTN 台标命名）优先判定。
    private val CCTV_KEYWORDS = listOf("央视", "CCTV", "CNTV", "CGTN")

    /**
     * 解析 m3u 文本并返回全部条目。
     *
     * @param text 订阅文本（可含 BOM 与 `\r\n` 换行）
     * @return 解析出的条目列表；无任何可播放地址时返回空列表
     */
    fun parse(text: String): List<M3uEntry> = parseLines(text.lineSequence())

    /**
     * 流式解析 m3u：逐行消费 [reader]，不在内存中持有整份订阅文本。
     *
     * 避免额外持有完整原文；解析结果仍全量保存在内存中，峰值内存未实测。
     * 调用方负责关闭 [reader]（通常以 `use { }` 包裹）。
     *
     * @param reader 订阅文本读取器（编码由调用方决定，UTF-8 兜底）
     * @return 解析出的条目列表；无任何可播放地址时返回空列表
     */
    fun parse(reader: BufferedReader): List<M3uEntry> = parseLines(reader.lineSequence())

    /**
     * 解析共用实现：按行消费，维护「待配对 URL 的 EXTINF 队列」。
     */
    private fun parseLines(lines: Sequence<String>): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        // 待配对 URL 的 EXTINF 队列：兼容「连续多条 EXTINF 共用一条 URL」的列表。
        val pending = mutableListOf<ExtinfAttributes>()

        lines.forEach { rawLine ->
            val line = rawLine.trim().trimStart('\uFEFF')
            when {
                line.isEmpty() || line.startsWith("#") && !line.startsWith(EXTINF_PREFIX) -> Unit
                line.startsWith(EXTINF_PREFIX) -> {
                    parseExtinf(line)?.let { pending.add(it) }
                }
                else -> {
                    // 首个非注释、非空行：视为 URL。
                    if (isPlayableUrl(line)) {
                        if (pending.isEmpty()) {
                            deriveNameFromUrl(line)?.let { name ->
                                entries.add(M3uEntry(name, null, null, null, line))
                            }
                        } else {
                            pending.forEach { attrs ->
                                entries.add(
                                    M3uEntry(
                                        name = attrs.name,
                                        group = attrs.group,
                                        tvgId = attrs.tvgId,
                                        tvgName = attrs.tvgName,
                                        url = line,
                                    ),
                                )
                            }
                        }
                    }
                    pending.clear()
                }
            }
        }
        return entries
    }

    /**
     * 按频道名称与分组推导业务分类。
     *
     * 优先级：
     * 1. group 或名称含「央视 / CCTV / CNTV / CGTN」（不区分大小写）→ [ChannelCategory.CCTV]
     * 2. group 或名称含「卫视」→ [ChannelCategory.SATELLITE]
     * 3. 其余含中文（CJK 统一表意文字）→ [ChannelCategory.CUSTOM]
     * 4. 纯英文 / 外文 → [ChannelCategory.FOREIGN]
     */
    fun categorize(name: String, group: String?): ChannelCategory {
        val haystack = buildString { append(name); append(' '); append(group.orEmpty()) }
        val upper = haystack.uppercase()
        if (CCTV_KEYWORDS.any { upper.contains(it.uppercase()) }) return ChannelCategory.CCTV
        if (haystack.contains("卫视")) return ChannelCategory.SATELLITE
        if (haystack.any { it.code in 0x4E00..0x9FFF }) return ChannelCategory.CUSTOM
        return ChannelCategory.FOREIGN
    }

    /**
     * 生成导入频道 id：`m3u:` ＋「名称与首个 URL」的 SHA-256 前 12 位十六进制。
     *
     * 同名同源的重复导入得到相同 id，从而在持久化层天然去重。
     */
    fun importedChannelId(name: String, firstUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$name\n$firstUrl".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
        return "m3u:" + hex.take(12)
    }

    /** 判断文本行是否为可解析的 http(s) 播放地址。 */
    private fun isPlayableUrl(line: String): Boolean {
        val scheme = runCatching { URI(line).scheme }.getOrNull() ?: return false
        return scheme.equals("http", ignoreCase = true) ||
            scheme.equals("https", ignoreCase = true)
    }

    /**
     * 解析 #EXTINF 行的属性与频道名。
     *
     * 频道名取「首个不被双引号包裹的逗号」之后的内容，兼容名称本身含逗号的情形。
     */
    private fun parseExtinf(line: String): ExtinfAttributes? {
        val body = line.removePrefix(EXTINF_PREFIX).removePrefix(":").trim()
        val nameIndex = findNameDelimiter(body) ?: return null
        val attributePart = body.substring(0, nameIndex)
        val name = body.substring(nameIndex + 1).trim()

        val attributes = ATTRIBUTE_REGEX.toRegex().findAll(attributePart)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val displayName = name.ifBlank {
            attributes["tvg-name"]?.takeIf { it.isNotBlank() }
        } ?: return null

        return ExtinfAttributes(
            name = displayName,
            group = attributes["group-title"]?.takeIf { it.isNotBlank() },
            tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
        )
    }

    /** 找到第一个不被双引号包裹的逗号下标；不存在时返回 `null`。 */
    private fun findNameDelimiter(body: String): Int? {
        var inQuotes = false
        body.forEachIndexed { index, char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> return index
            }
        }
        return null
    }

    /** 裸 URL 条目的名称推导：优先取最后一个非空路径段，否则取主机名。 */
    private fun deriveNameFromUrl(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val segment = uri.path?.trim('/')
            val lastSegment = segment?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() && !it.equals("index", ignoreCase = true) }
            lastSegment ?: uri.host
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** #EXTINF 行解析出的元数据。 */
    private data class ExtinfAttributes(
        val name: String,
        val group: String?,
        val tvgId: String?,
        val tvgName: String?,
    )
}
