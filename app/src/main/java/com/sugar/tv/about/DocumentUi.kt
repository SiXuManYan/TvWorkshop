package com.sugar.tv.about

import android.text.SpannableString
import android.text.Spanned
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.noties.markwon.Markwon

/** 阅读页按系统栏、挖孔与 IME 的并集设置绝对避让量，重复分发不会累加。 */
internal fun View.applyDocumentInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        view.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}

/** 每次绑定复制正文链接 Span，把点击出口留在页面，避免后台结果携带 Activity。 */
internal fun TextView.bindDocumentText(body: Spanned, renderer: Markwon, onOpenLink: (String) -> Unit) {
    val linked = SpannableString(body)
    val links = linked.getSpans(0, linked.length, URLSpan::class.java)
    links.forEach { span ->
        val start = linked.getSpanStart(span)
        val end = linked.getSpanEnd(span)
        val flags = linked.getSpanFlags(span)
        linked.removeSpan(span)
        linked.setSpan(object : ClickableSpan() {
            /** 链接只转交页面的白名单或浏览器出口，不调用 Markwon 的默认跳转。 */
            override fun onClick(widget: View) = onOpenLink(span.url)
        }, start, end, flags)
    }
    // 运行 core 的 TextView 绑定钩子，保证列表缩进等 Span 能按当前字号正确测量。
    renderer.setParsedMarkdown(this, linked)
    // 回收复用时还原无链接正文的选择行为，不能遗留上一章节的链接移动模式。
    movementMethod = if (links.isNotEmpty()) {
        LinkMovementMethod.getInstance()
    } else {
        ArrowKeyMovementMethod.getInstance()
    }
}
