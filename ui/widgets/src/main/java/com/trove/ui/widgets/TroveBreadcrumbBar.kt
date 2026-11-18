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
 * A high-performance, custom-drawn breadcrumb navigation bar for Trove.
 */
class TroveBreadcrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f)
        color = Color.BLACK
    }
    private val separatorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f)
        color = Color.GRAY
    }
    
    private var segments: List<String> = emptyList()
    private val segmentRects = mutableListOf<RectF>()
    private var onSegmentClickListener: ((Int) -> Unit)? = null

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
        val height = context.dpInt(40f)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        segmentRects.clear()
        
        var currentX = context.dp(16f)
        val centerY = height / 2f
        val separator = " > "
        val sepWidth = separatorPaint.measureText(separator)

        for (i in segments.indices) {
            val text = segments[i]
            val textWidth = textPaint.measureText(text)
            
            // Record clickable area
            val rect = RectF(currentX, 0f, currentX + textWidth, height.toFloat())
            segmentRects.add(rect)
            
            // Draw text
            val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, currentX, baseline, textPaint)
            
            currentX += textWidth
            
            // Draw separator if not last
            if (i < segments.size - 1) {
                canvas.drawText(separator, currentX, baseline, separatorPaint)
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
                    onSegmentClickListener?.invoke(i)
                    return true
                }
            }
        }
        return true
    }
}

@Deprecated("Renamed to TroveBreadcrumbBar", ReplaceWith("TroveBreadcrumbBar"))
typealias MiBreadcrumbBar = TroveBreadcrumbBar
