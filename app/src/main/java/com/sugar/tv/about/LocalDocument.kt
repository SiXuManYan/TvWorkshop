package com.sugar.tv.about

import android.text.Spanned
import androidx.annotation.StringRes
import com.sugar.tv.R

/**
 * 仅允许打开已登记的随包文档，不接收任意路径或网络文档。
 *
 * @property key Intent 与恢复句柄共用的稳定文档标识。
 * @property titleRes 工具栏标题；本轮文档与入口仅提供简体中文。
 * @property assetPath 相对 APK assets 的文件路径。
 * @property collapsible 是否按版本独立展开；法律文档始终完整显示。
 */
internal enum class LocalDocument(
    val key: String,
    @param:StringRes val titleRes: Int,
    val assetPath: String,
    val collapsible: Boolean = false,
) {
    VERSION_HISTORY(
        key = "version_history",
        titleRes = R.string.document_version_history,
        assetPath = "version-history/CHANGELOG.md",
        collapsible = true,
    ),
    USER_AGREEMENT(
        key = "user_agreement",
        titleRes = R.string.document_user_agreement,
        assetPath = "legal/USER_AGREEMENT.md",
    ),
    PRIVACY_POLICY(
        key = "privacy_policy",
        titleRes = R.string.document_privacy_policy,
        assetPath = "legal/PRIVACY_POLICY.md",
    );

    companion object {
        /** 与 SavedStateHandle 复用的 Intent 参数，只保存文档标识。 */
        const val EXTRA_DOCUMENT = "local_document_key"

        /** 未知参数回退到用户协议，禁止将参数直接用作文件路径。 */
        fun fromKey(key: String?): LocalDocument =
            entries.firstOrNull { it.key == key } ?: USER_AGREEMENT

        /** 仅解析同目录两份法律文档的互链，不开放任意本地文件读取。 */
        fun fromLink(link: String): LocalDocument? = entries.firstOrNull {
            it != VERSION_HISTORY && it.assetPath.substringAfterLast('/') == link
        }
    }
}

/**
 * 已在后台解析的单个章节，正文不可变且不含页面回调。
 *
 * @property title 二级标题，兼作章节导航或版本卡片标题。
 * @property body 仅由 Markwon core 生成的正文，不加载远程图片。
 */
internal data class DocumentSection(val title: String, val body: Spanned)

/**
 * 阅读页可复用的完整内容，不持有 Activity、View 或可点击回调。
 *
 * @property title 文档一级标题。
 * @property introduction 标题与第一个二级标题之间的日期、概述等内容。
 * @property sections 顺序与 Markdown 源文件一致的章节。
 */
internal data class DocumentContent(
    val title: String,
    val introduction: Spanned,
    val sections: List<DocumentSection>,
)

/**
 * 不可变阅读状态；展开集合只对版本日志生效。
 *
 * @property content 加载完成的内容，加载与失败时为空。
 * @property expandedSections 当前展开的章节下标。
 * @property loading 是否正在读取和排版。
 * @property failed 是否展示读取失败与重试入口。
 */
internal data class LocalDocumentUiState(
    val content: DocumentContent? = null,
    val expandedSections: Set<Int> = emptySet(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)
