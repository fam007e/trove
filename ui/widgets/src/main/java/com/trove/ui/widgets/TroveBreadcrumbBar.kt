package com.trove.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A high-performance, custom-drawn breadcrumb navigation bar for Trove
 * with Nordic Dark palette (Polar Night slate with Frost Ice Cyan active path).
 */
class TroveBreadcrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Nordic Theme Palette
    private val colorNord1 = Color.parseColor("#3B4252") // Background (Polar Slate)
    private val colorNord3 = Color.parseColor("#4C566A") // Separator
    private val colorNord4 = Color.parseColor("#D8DEE9") // Ancestor segments
    private val colorNord8 = Color.parseColor("#88C0D0") // Active segment (Frost Cyan)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13.5f)
        color = colorNord4
    }
    private val activeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13.5f)
        color = colorNord8
        isFakeBoldText = true
    }
    private val separatorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = colorNord3
    }

    private var segments: List<String> = emptyList()
    private val segmentRects = mutableListOf<RectF>()
    private var onSegmentClickListener: ((Int) -> Unit)? = null

    init {
        setBackgroundColor(colorNord1)
    }

    fun setPath(path: String) {
        segments = if (path == "/" || path.isEmpty()) {
            listOf("/")
        } else {
            listOf("/") + path.split("/").filter { it.isNotEmpty() }
        }
        requestLayout()
        invalidate()
    }

    fun setOnSegmentClickListener(listener: (Int) -> Unit) {
        onSegmentClickListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = context.dpInt(38f)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        segmentRects.clear()

        var currentX = context.dp(16f)
        val centerY = height / 2f
        val separator = "  /  "
        val sepWidth = separatorPaint.measureText(separator)

        for (i in segments.indices) {
            val text = segments[i]
            val isLast = (i == segments.size - 1)
            val paint = if (isLast) activeTextPaint else textPaint
            val textWidth = paint.measureText(text)

            // Record clickable area
            val rect = RectF(currentX - context.dp(4f), 0f, currentX + textWidth + context.dp(4f), height.toFloat())
            segmentRects.add(rect)

            // Draw text
            val baseline = centerY - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, currentX, baseline, paint)

            currentX += textWidth

            // Draw separator if not the last segment
            if (!isLast) {
                val sepBaseline = centerY - (separatorPaint.descent() + separatorPaint.ascent()) / 2
                canvas.drawText(separator, currentX, sepBaseline, separatorPaint)
                currentX += sepWidth
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            for (i in segmentRects.indices) {
                if (segmentRects[i].contains(x, y)) {
                    // Subtle tap feedback
                    animate()
                        .alpha(0.85f)
                        .setDuration(60)
                        .withEndAction {
                            animate().alpha(1.0f).setDuration(100).start()
                        }
                        .start()
                    onSegmentClickListener?.invoke(i)
                    return true
                }
            }
        }
        return true
    }
}
