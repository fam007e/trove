package com.trove.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * A functional, custom-drawn tab bar for Trove.
 */
class TroveTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f)
        color = Color.BLACK
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        strokeWidth = context.dp(3f)
    }

    private val tabs = mutableListOf<String>()
    private var selectedIndex = 0
    private var onTabSelectedListener: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        setWillNotDraw(false)
    }

    fun addTab(label: String) {
        tabs.add(label)
        val tabView = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        addView(tabView)
        requestLayout()
        invalidate()
    }

    fun updateTab(index: Int, label: String) {
        if (index in tabs.indices) {
            tabs[index] = label
            invalidate()
        }
    }

    fun removeTab(index: Int) {
        if (index in tabs.indices) {
            tabs.removeAt(index)
            if (childCount > index) {
                removeViewAt(index)
            }
            if (selectedIndex >= tabs.size) {
                selectedIndex = (tabs.size - 1).coerceAtLeast(0)
            }
            requestLayout()
            invalidate()
        }
    }

    fun selectTab(index: Int, notify: Boolean = true) {
        if (index in tabs.indices && selectedIndex != index) {
            selectedIndex = index
            if (notify) {
                onTabSelectedListener?.invoke(index)
            }
            invalidate()
        }
    }

    fun setOnTabSelectedListener(listener: (Int) -> Unit) {
        onTabSelectedListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tabs.isEmpty()) return

        val tabWidth = width.toFloat() / tabs.size
        val centerY = height / 2f
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2

        for (i in tabs.indices) {
            val label = tabs[i]
            val textWidth = textPaint.measureText(label)
            val startX = i * tabWidth
            
            // Draw Label
            canvas.drawText(
                label,
                startX + (tabWidth - textWidth) / 2,
                baseline,
                textPaint
            )

            // Draw selection indicator
            if (i == selectedIndex) {
                canvas.drawLine(
                    startX + context.dp(8f),
                    height - selectedPaint.strokeWidth,
                    startX + tabWidth - context.dp(8f),
                    height - selectedPaint.strokeWidth,
                    selectedPaint
                )
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && tabs.isNotEmpty()) {
            val tabWidth = width.toFloat() / tabs.size
            val index = (event.x / tabWidth).toInt()
            selectTab(index)
        }
        return true
    }
}

@Deprecated("Renamed to TroveTabBar", ReplaceWith("TroveTabBar"))

