package com.sugar.tv.about

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.sugar.tv.databinding.ItemDocumentSectionBinding
import kotlin.math.roundToInt

/**
 * 复用工坊系列版本卡片的高度、透明度与箭头联动，动画仅归当前章节 ViewHolder 所有。
 *
 * @property binding 当前卡片的视图，不能跨 Holder 共享。
 * @property onSettled 动画完全结束后通知列表处理最新章节定位。
 */
internal class DocumentSectionAnimator(
    private val binding: ItemDocumentSectionBinding,
    private val onSettled: () -> Unit,
) {

    /** 目标展开状态独立于可见性，收起动画中正文仍须参与布局。 */
    private var expanded = true

    /** 连续反向点击先撤销旧回调，再从当前视觉状态接续。 */
    private var animator: ValueAnimator? = null

    /** 与 RadioWorkshop 的版本展开使用同一插值曲线。 */
    private val interpolator = AnimationUtils.loadInterpolator(
        binding.root.context,
        android.R.interpolator.fast_out_slow_in,
    )

    /** 列表定位等待所有正在改变高度的可见章节完成。 */
    val isRunning: Boolean get() = animator != null

    init {
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // 窗口宽度变化后旧的测量高度不再可靠，立即恢复 wrap_content 重新排版。
            if (isRunning && right - left != oldRight - oldLeft) finish()
        }
    }

    /** 首次绑定、回收恢复或系统关闭动画时直接还原；正文应由调用方在展开前绑定。 */
    fun setExpanded(expanded: Boolean, animate: Boolean) {
        cancel()
        this.expanded = expanded
        val body = binding.sectionBody
        body.importantForAccessibility = if (expanded) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (!expanded && body.hasFocus()) binding.sectionHeader.requestFocus()
        body.isFocusable = expanded
        body.isFocusableInTouchMode = expanded
        body.linksClickable = expanded
        val container = body.parent as ViewGroup
        val width = container.width - container.paddingLeft - container.paddingRight
        if (!animate || !binding.root.isAttachedToWindow || !binding.root.isLaidOut ||
            width <= 0 || !ValueAnimator.areAnimatorsEnabled()
        ) {
            finish()
            return
        }
        val startHeight = if (body.isVisible) body.layoutParams.height.takeIf { it >= 0 } ?: body.height else 0
        val startAlpha = if (body.isVisible) body.alpha else 0f
        val startRotation = binding.sectionArrow.rotation
        val endRotation = if (expanded) EXPANDED_ROTATION else 0f
        val endHeight = if (expanded) {
            // 正文宽度包含自身 padding，按当前字号测量完整内容，不写死展开高度。
            body.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            body.measuredHeight
        } else {
            0
        }
        body.updateLayoutParams<ViewGroup.LayoutParams> { height = startHeight }
        body.alpha = startAlpha
        body.isVisible = true
        val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXPANSION_DURATION_MS
            interpolator = this@DocumentSectionAnimator.interpolator
            addUpdateListener { value ->
                val fraction = value.animatedValue as Float
                val height = (startHeight + (endHeight - startHeight) * fraction).roundToInt()
                if (body.layoutParams.height != height) {
                    body.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
                }
                body.alpha = startAlpha + ((if (expanded) 1f else 0f) - startAlpha) * fraction
                binding.sectionArrow.rotation = startRotation + (endRotation - startRotation) * fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                /** 已取消的动画不能回写旧目标，也不能提前执行新一轮的定位请求。 */
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
        }
        animator = nextAnimator
        nextAnimator.start()
    }

    /** 离屏、回收和页面销毁时都落到目标状态，不留下半高度卡片或链接回调。 */
    fun finish() {
        cancel()
        binding.sectionBody.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.sectionBody.isVisible = expanded
        binding.sectionBody.alpha = 1f
        binding.sectionArrow.rotation = if (expanded) EXPANDED_ROTATION else 0f
        if (!expanded) {
            binding.sectionBody.text = null
            binding.sectionBody.movementMethod = null
        }
        onSettled()
    }

    /** 先解除监听再取消，保留当前高度、透明度和角度以接续反向操作。 */
    private fun cancel() {
        animator?.let { current ->
            current.removeAllListeners()
            current.removeAllUpdateListeners()
            current.cancel()
        }
        animator = null
    }

    private companion object {
        /** 与参考项目一致的短过渡，避免长正文展开时产生整行闪烁。 */
        const val EXPANSION_DURATION_MS = 240L

        /** 展开时箭头朝上，收起时回到向下的初始角度。 */
        const val EXPANDED_ROTATION = 180f
    }
}
