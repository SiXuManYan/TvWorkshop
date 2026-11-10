package com.sugar.tv.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.sugar.tv.data.builtin.BuiltinChannels
import com.sugar.tv.data.db.AppDatabase
import com.sugar.tv.data.db.FavoriteChannelDao
import com.sugar.tv.data.db.ImportedChannelEntity
import com.sugar.tv.data.db.RecentChannelEntity
import com.sugar.tv.data.m3u.M3uEntry
import com.sugar.tv.data.m3u.M3uParser
import com.sugar.tv.data.m3u.M3uWriter
import com.sugar.tv.data.model.Channel
import com.sugar.tv.data.model.ChannelSource
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 按名称合并后的导入候选频道：仓库导入流程的内部中间结构。
 *
 * @property name 频道名称（取首个出现的写法）
 * @property group 分组名（首个非空 group-title）
 * @property urls 合并去重后的信号源地址，保持原始顺序
 */
private class MergedCandidate(
    val name: String,
    var group: String?,
    val urls: MutableSet<String>,
)

/**
 * 频道数据仓库：统一承载频道列表、收藏、观看记录与频道源导入的数据访问。
 *
 * 业务层（ViewModel）只通过本类读写数据；持久化（Room）、网络（HttpURLConnection）
 * 与 m3u 解析等实现细节全部收敛在此，且耗时操作均运行在 IO 调度器，
 * 频道列表合并与 JSON 解码等 CPU 轻量操作运行在 Default 调度器。
 *
 * 本类不持有任何 View / Activity / Fragment 引用，[appContext] 仅用于构建
 * Application 级的 Room 单例。
 *
 * @param appContext Application 级 Context，仅用于访问应用级资源，不持有页面生命周期对象
 */
class ChannelRepository(appContext: Context) {

    /** 仅保留应用级文件访问入口，文件授权由系统选择器按次提供。 */
    private val contentResolver = appContext.applicationContext.contentResolver

    /** Room 数据库单例：仓库本身是应用级单例，数据库随仓库只建一次。 */
    private val database: AppDatabase = Room.databaseBuilder(
        appContext.applicationContext,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME,
    ).build()

    /** 收藏记录的数据库入口。 */
    private val favoriteDao = database.favoriteChannelDao()

    /** 最近观看记录的数据库入口。 */
    private val recentDao = database.recentChannelDao()

    /** 导入频道的数据库入口。 */
    private val importedDao = database.importedChannelDao()

    /**
     * 观察全部频道列表，数据变化时持续发射。
     *
     * 内容 = 内置目录 + 导入频道（分类按映射规则重推），导入表变化时自动重新合并。
     */
    fun observeAllChannels(): Flow<List<Channel>> =
        importedDao.observeAll().map { imported ->
            BuiltinChannels.all + imported.mapNotNull { it.toChannel() }
        }.flowOn(Dispatchers.Default)

    /**
     * 按唯一标识查询单个频道；不存在时返回 `null`。
     */
    suspend fun channelById(id: String): Channel? = withContext(Dispatchers.IO) {
        BuiltinChannels.byId[id] ?: importedDao.getById(id)?.toChannel()
    }

    /**
     * 观察收藏频道 id 列表，数据变化时持续发射。
     */
    fun observeFavoriteIds(): Flow<List<String>> = favoriteDao.observeFavoriteIds()

    /**
     * 切换指定频道的收藏状态（未收藏则收藏，已收藏则取消）。
     *
     * 读改写收敛为 DAO 层单条事务（见 [FavoriteChannelDao.toggle]）：并发双击不会
     * 交错读改写导致切换丢失；返回切换后的收藏状态（`true` = 已收藏），
     * 调用方以实际落点同步 UI 与提示，而非依据调用前的旧快照。
     */
    suspend fun toggleFavorite(channelId: String): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.toggle(channelId = channelId, addedAt = System.currentTimeMillis())
    }

    /**
     * 观察最近观看频道 id 列表（按最近观看时间排序），数据变化时持续发射。
     */
    fun observeRecentIds(): Flow<List<String>> = recentDao.observeRecentIds()

    /**
     * 记录一次频道观看，用于「最近观看」分类。
     */
    suspend fun recordRecent(channelId: String) {
        withContext(Dispatchers.IO) {
            recentDao.upsert(
                RecentChannelEntity(
                    channelId = channelId,
                    watchedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 删除全部观看记录，频道目录与收藏不受影响。 */
    suspend fun clearRecentChannels() = withContext(Dispatchers.IO) {
        recentDao.deleteAll()
    }

    /** 观察用户导入的频道，管理页不混入内置目录或受业务分类限制。 */
    fun observeImportedChannels(): Flow<List<Channel>> = importedDao.observeAll().map { imported ->
        imported.mapNotNull { it.toChannel() }
    }.flowOn(Dispatchers.Default)

    /** 按系统授权的 URI 流式读取 UTF-8 列表，复用文本导入的合并和去重规则。 */
    suspend fun importFromDocument(uri: Uri): Int = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("无法读取列表文件")
        val entries = input.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(reader = it) }
        importEntries(entries)
    }

    /**
     * 导出全部导入频道的快照，保留分组与各路地址，不包含内置目录、收藏或观看记录。
     * 输出目的地由用户通过系统选择器决定，关闭流后才报告成功。
     */
    suspend fun exportToDocument(uri: Uri): Int = withContext(Dispatchers.IO) {
        var channelCount = 0
        val exportContext = currentCoroutineContext()
        val entries = importedDao.getAll().flatMap { entity ->
            exportContext.ensureActive()
            val sources = SourceCodec.decode(entity.sourcesJson)
            if (sources.isNotEmpty()) channelCount++
            sources.map { source ->
                M3uEntry(
                    name = entity.name,
                    group = entity.groupName,
                    tvgId = null,
                    tvgName = null,
                    url = source.url,
                )
            }
        }
        require(entries.isNotEmpty()) { "没有可导出的频道" }
        exportContext.ensureActive()
        val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入列表文件")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            M3uWriter.write(entries = entries, writer = writer)
        }
        channelCount
    }

    /**
     * 从网络订阅地址导入频道。
     *
     * 使用 [HttpURLConnection] 下载订阅文本（连接与读取超时各 10 秒），
     * 字符集优先取响应头 `Content-Type` 中的 `charset`，缺省按 UTF-8 解码。
     * 下载与解析流式衔接（逐行消费），不把整份订阅文本驻留内存。
     *
     * @return 实际新增的导入频道数量；地址非法抛 [IllegalArgumentException]，
     *   网络失败抛 [IOException]，内容无可播放频道抛 [IllegalArgumentException]
     */
    suspend fun importFromUrl(url: String): Int = withContext(Dispatchers.IO) {
        requireValidHttpUrl(url)
        importEntries(downloadAndParse(url))
    }

    /**
     * 从已获取的订阅文本（m3u / m3u8 内容）导入频道。
     *
     * 同名频道合并信号源并去重；与内置频道同名且信号源全部重复的条目不导入，
     * 同名但含新地址的条目只保留新增地址后照常导入；已导入过的同名同源频道幂等跳过。
     *
     * @return 实际新增的导入频道数量；解析不到任何可播放频道时抛
     *   [IllegalArgumentException] 由调用方提示
     */
    suspend fun importFromText(text: String): Int = withContext(Dispatchers.IO) {
        importEntries(M3uParser.parse(text))
    }

    /**
     * 清空全部导入的自定义频道，收藏与观看记录不受影响。
     *
     * @return 被清空的频道数量
     */
    suspend fun clearImportedChannels(): Int = withContext(Dispatchers.IO) {
        importedDao.deleteAll()
    }

    /**
     * 删除单个导入的自定义频道。
     *
     * @return 是否删除成功（频道不存在或非导入频道返回 `false`）
     */
    suspend fun removeImportedChannel(channelId: String): Boolean = withContext(Dispatchers.IO) {
        importedDao.deleteById(channelId) > 0
    }

    /**
     * 导入流程共用实现：解析结果 → 按名称合并去重 → 过滤与内置/已导入的重复 → 落库。
     */
    private suspend fun importEntries(entries: List<M3uEntry>): Int {
        val importContext = currentCoroutineContext()
        importContext.ensureActive()
        if (entries.isEmpty()) {
            throw IllegalArgumentException("订阅内容中未找到任何可播放频道")
        }

        // 内置目录按「空白归一化 + 小写的频道名 → 已固化地址」预建索引：
        // 大体积订阅逐条比对内置频道时由 O(订阅数 × 内置数) 降为 O(订阅数)。
        val builtinUrlsByName = BuiltinChannels.all
            .groupBy(
                keySelector = { it.name.replace(WHITESPACE_REGEX, "").lowercase() },
                valueTransform = { channel -> channel.sources.map { source -> source.url } },
            )
            .mapValues { (_, urlGroups) -> urlGroups.flatten().toSet() }

        val merged = LinkedHashMap<String, MergedCandidate>()
        for (entry in entries) {
            importContext.ensureActive()
            val key = entry.name.replace(WHITESPACE_REGEX, "").lowercase()
            val current = merged.getOrPut(key) {
                MergedCandidate(name = entry.name, group = entry.group, urls = linkedSetOf())
            }
            if (current.group == null) current.group = entry.group
            current.urls.add(entry.url)
        }

        val newEntities = mutableListOf<ImportedChannelEntity>()
        for (item in merged.values) {
            importContext.ensureActive()
            // 与内置频道同名时剔除已固化的信号源；全部重复则跳过。
            // 名称归一化与合并 key 同一规则：两侧都做空白归一化并转小写后比较，
            // 避免条目名含内部空白（如 "CCTV-1  综合" 双空格写法）时漏判重复导致同源重复导入。
            val normalizedItemName = item.name.replace(WHITESPACE_REGEX, "").lowercase()
            val builtinUrls = builtinUrlsByName[normalizedItemName].orEmpty()
            val freshUrls = item.urls.filterNot { url -> url in builtinUrls }
            if (freshUrls.isEmpty()) continue

            newEntities.add(
                ImportedChannelEntity(
                    id = M3uParser.importedChannelId(name = item.name, firstUrl = freshUrls.first()),
                    name = item.name,
                    groupName = item.group,
                    sourcesJson = SourceCodec.encode(
                        freshUrls.mapIndexed { index, url ->
                            ChannelSource(label = "线路${index + 1}", url = url)
                        },
                    ),
                ),
            )
        }

        // 主键冲突由同一次批量插入原子跳过，无需先加载全部旧频道及其 JSON；计数取实际写入结果。
        importContext.ensureActive()
        return if (newEntities.isEmpty()) 0 else importedDao.insertAll(newEntities).count { it != -1L }
    }

    /**
     * 下载并流式解析订阅：连接与读取超时各 10 秒，非 2xx 抛 [IOException]，
     * 字符集优先响应头 `charset`，无法识别时回退 UTF-8；逐行消费不驻留整包文本。
     */
    private fun downloadAndParse(url: String): List<M3uEntry> {
        val uri = requireNotNull(parseHttpUri(url.trim())) { "订阅地址必须是有效的 http(s) 链接" }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
        // 显式 return try：try 内尾部的 use 表达式需处于值位置才能作为函数返回值。
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("订阅下载失败：HTTP $code")
            }
            val charset = parseCharset(connection.contentType)
            connection.inputStream.bufferedReader(charset).use { reader ->
                M3uParser.parse(reader)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 校验地址必须为 http(s)，否则抛 [IllegalArgumentException]。 */
    private fun requireValidHttpUrl(url: String) {
        require(parseHttpUri(url.trim()) != null) { "订阅地址必须是有效的 http(s) 链接" }
    }

    /**
     * 从响应头 Content-Type 解析字符集；缺省或无法识别时回退 UTF-8。
     */
    private fun parseCharset(contentType: String?): Charset {
        val charsetName = contentType?.let { CHARSET_REGEX.find(it)?.groupValues?.get(1) }
            .orEmpty()
        if (charsetName.isBlank()) return StandardCharsets.UTF_8
        return runCatching { Charset.forName(charsetName) }.getOrDefault(StandardCharsets.UTF_8)
    }

    /** 把导入表实体转换回业务频道；信号源 JSON 损坏（解码为空）时丢弃该条。 */
    private fun ImportedChannelEntity.toChannel(): Channel? {
        val sources = SourceCodec.decode(sourcesJson)
        if (sources.isEmpty()) return null
        return Channel(
            id = id,
            name = name,
            category = M3uParser.categorize(name = name, group = groupName),
            sources = sources,
        )
    }

    private companion object {
        /** 网络连接与读取超时（毫秒）。 */
        const val TIMEOUT_MILLIS = 10_000

        /** 名称归一化用的空白字符正则。 */
        val WHITESPACE_REGEX = Regex("\\s+")

        /** HTTP 参数名不区分大小写，字符集值截至下一个分号或引号。 */
        val CHARSET_REGEX = Regex(
            pattern = """(?:^|;)\s*charset\s*=\s*["']?([^;"'\s]+)""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
