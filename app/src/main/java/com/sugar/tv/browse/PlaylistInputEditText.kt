package com.sugar.tv.browse

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.textfield.TextInputEditText

/**
 * 导入正文输入框：长文本优先内部滚动，点击、光标与选择继续由原生编辑控件处理。
 *
 * @param context 带页面主题的上下文
 * @param attrs XML 属性
 * @param defStyleAttr 沿用 Material 输入框的默认样式属性
 */
class PlaylistInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : TextInputEditText(context, attrs, defStyleAttr) {

    /** 只协调内外滚动的归属，不接管触摸点击或复制、粘贴等编辑行为。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(
                canScrollVertically(-1) || canScrollVertically(1),
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }
}
