package com.sugar.tv.about

import android.app.Application
import android.text.SpannedString
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 读取随包 Markdown，在后台按二级标题分章并排版；不执行网络请求。
 *
 * @property application 仅用于访问 assets 与创建不依赖页面的 Markwon core 渲染器。
 */
internal class LocalDocumentRepository(private val application: Application) {

    /** Markwon 非线程安全，取消后重试也须串行访问同一渲染器。 */
    private val renderMutex = Mutex()

    /** 首次后台排版时创建；不启用图片或其他会访问网络的插件。 */
    private val renderer by lazy { Markwon.create(application) }

    /** 文档一级标题；与仓库的三份源文件格式保持一致。 */
    private val titlePattern = Regex("(?m)^# +(.+)$")

    /** 二级标题作为章节边界，三级及以下标题继续交给 Markwon 渲染。 */
    private val sectionPattern = Regex("(?m)^## +(.+)$")

    /** 文件读取放 IO，解析和 Span 准备放 Default，结果冻结后交给页面绑定。 */
    suspend fun load(document: LocalDocument): DocumentContent {
        val markdown = withContext(Dispatchers.IO) {
            application.assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use {
                it.readText().removePrefix("\uFEFF").replace("\r\n", "\n")
            }
        }
        return withContext(Dispatchers.Default) {
            renderMutex.withLock {
                val heading = requireNotNull(titlePattern.find(markdown))
                val sections = sectionPattern.findAll(markdown).toList()
                require(sections.isNotEmpty())
                val introduction = markdown.substring(
                    startIndex = heading.range.last + 1,
                    endIndex = sections.first().range.first,
                ).trim()
                DocumentContent(
                    title = heading.groupValues[1].trim(),
                    introduction = SpannedString(renderer.toMarkdown(introduction)),
                    sections = sections.mapIndexed { index, section ->
                        // 重试或销毁取消后停止后续章节排版，旧任务不能写回新状态。
                        ensureActive()
                        val body = markdown.substring(
                            startIndex = section.range.last + 1,
                            endIndex = sections.getOrNull(index + 1)?.range?.first ?: markdown.length,
                        ).trim()
                        require(body.isNotEmpty())
                        DocumentSection(
                            title = section.groupValues[1].trim(),
                            body = SpannedString(renderer.toMarkdown(body)),
                        )
                    },
                )
            }
        }
    }
}
