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

    /** Rect → segment index it navigates to (the ellipsis maps to the first
     *  hidden ancestor). Kept inside the view bounds — never spilling over. */
    private val segmentRects = mutableListOf<Pair<RectF, Int>>()
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
        if (segments.isEmpty()) return

        // Overflowing text would paint under the sibling disk-usage chip, so
        // drawing is clipped and long paths keep their TAIL (deepest folders)
        // with a leading ellipsis for the hidden ancestors.
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        val startPad = context.dp(12f)
        val endPad = context.dp(8f)
        val avail = (width - startPad - endPad).coerceAtLeast(0f)
        val centerY = height / 2f
        val separator = "  /  "
        val sepWidth = separatorPaint.measureText(separator)
        val ellipsisWidth = textPaint.measureText("…")

        val widths = segments.mapIndexed { i, text ->
            (if (i == segments.size - 1) activeTextPaint else textPaint).measureText(text)
        }

        var from = 0
        var lastText = segments.last()
        var lastWidth = widths.last()
        if (widths.sum() + sepWidth * (segments.size - 1) > avail) {
            from = segments.size - 1
            var sum = lastWidth + ellipsisWidth + sepWidth
            while (from > 0 && sum + widths[from - 1] + sepWidth <= avail) {
                sum += widths[from - 1] + sepWidth
                from--
            }
            if (from == segments.size - 1 && sum > avail) {
                // Even the active segment alone overflows: shorten it in place
                // so the current directory always stays readable.
                lastText = android.text.TextUtils.ellipsize(
                    segments.last(), activeTextPaint,
                    (avail - ellipsisWidth - sepWidth).coerceAtLeast(0f),
                    android.text.TextUtils.TruncateAt.START
                ).toString()
                lastWidth = activeTextPaint.measureText(lastText)
            }
        }

        var x = startPad
        if (from > 0) {
            val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText("…", x, baseline, textPaint)
            segmentRects.add(
                RectF(0f, 0f, x + ellipsisWidth + sepWidth / 2, height.toFloat()) to (from - 1)
            )
            x += ellipsisWidth + sepWidth
        }
        for (i in from until segments.size) {
            val isLast = (i == segments.size - 1)
            val paint = if (isLast) activeTextPaint else textPaint
            val text = if (isLast) lastText else segments[i]
            val textWidth = if (isLast) lastWidth else widths[i]

            val baseline = centerY - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, x, baseline, paint)

            segmentRects.add(
                RectF(
                    x - context.dp(4f), 0f,
                    minOf(x + textWidth + context.dp(4f), width.toFloat()),
                    height.toFloat()
                ) to i
            )
            x += textWidth

            if (!isLast) {
                val sepBaseline = centerY - (separatorPaint.descent() + separatorPaint.ascent()) / 2
                canvas.drawText(separator, x, sepBaseline, separatorPaint)
                x += sepWidth
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            for ((rect, index) in segmentRects) {
                if (rect.contains(x, y)) {
                    // Subtle tap feedback
                    animate()
                        .alpha(0.85f)
                        .setDuration(60)
                        .withEndAction {
                            animate().alpha(1.0f).setDuration(100).start()
                        }
                        .start()
                    onSegmentClickListener?.invoke(index)
                    return true
                }
            }
        }
        return true
    }
}
