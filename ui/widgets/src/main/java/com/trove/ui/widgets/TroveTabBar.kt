package com.trove.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout

/**
 * A functional, custom-drawn tab bar for Trove with Nordic Dark theme
 * and smooth animated indicator transitions.
 */
class TroveTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Nordic Palette
    private val colorNord0 = Color.parseColor("#2E3440") // Background
    private val colorNord3 = Color.parseColor("#4C566A") // Inactive text
    private val colorNord6 = Color.parseColor("#ECEFF4") // Active text
    private val colorNord8 = Color.parseColor("#88C0D0") // Indicator accent (Frost cyan)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f)
        color = colorNord6
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorNord8
        strokeWidth = context.dp(3f)
    }

    private val tabState = TabState()
    private var onTabSelectedListener: ((Int) -> Unit)? = null

    // Smooth indicator transition
    private var animatedIndicatorX = 0f
    private var indicatorAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(colorNord0)
        setWillNotDraw(false)
    }

    fun addTab(label: String) {
        tabState.add(label)
        val tabView = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            // Children own their touches: the parent must NOT intercept
            // (see onInterceptTouchEvent) or clicks/long-presses never arrive.
            setOnClickListener {
                selectTab(indexOfChild(it))
            }
            setOnLongClickListener {
                onTabLongClickListener?.invoke(indexOfChild(it))
                true
            }
        }
        addView(tabView)
        requestLayout()
        invalidate()
    }

    fun updateTab(index: Int, label: String) {
        if (tabState.update(index, label)) {
            invalidate()
        }
    }

    fun removeTab(index: Int) {
        if (tabState.remove(index)) {
            if (childCount > index) {
                removeViewAt(index)
            }
            requestLayout()
            invalidate()
        }
    }

    fun selectTab(index: Int, notify: Boolean = true) {
        val oldIndex = tabState.select(index)
        if (oldIndex >= 0) {
            if (notify) {
                onTabSelectedListener?.invoke(index)
            }
            animateIndicator(oldIndex, index)
        }
    }

    private fun animateIndicator(from: Int, to: Int) {
        if (width == 0 || tabState.size == 0) return
        val tabWidth = width.toFloat() / tabState.size
        val startX = from * tabWidth
        val targetX = to * tabWidth

        indicatorAnimator?.cancel()
        indicatorAnimator = ValueAnimator.ofFloat(startX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                animatedIndicatorX = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setOnTabSelectedListener(listener: (Int) -> Unit) {
        onTabSelectedListener = listener
    }

    /** Invoked with the tab index when a tab is long-pressed (close gesture). */
    var onTabLongClickListener: ((Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tabState.size == 0) return

        val labels = tabState.labelList
        val tabWidth = width.toFloat() / labels.size
        val centerY = height / 2f
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2

        for (i in labels.indices) {
            val label = labels[i]
            textPaint.color = if (i == tabState.selectedIndex) colorNord6 else colorNord3
            val textWidth = textPaint.measureText(label)
            val startX = i * tabWidth
            
            // Draw Label
            canvas.drawText(
                label,
                startX + (tabWidth - textWidth) / 2,
                baseline,
                textPaint
            )
        }

        // Draw animated selection indicator line in Nord8 Frost Cyan
        val indicatorX = if (indicatorAnimator?.isRunning == true) {
            animatedIndicatorX
        } else {
            tabState.selectedIndex * tabWidth
        }

        canvas.drawLine(
            indicatorX + context.dp(12f),
            height - selectedPaint.strokeWidth / 2f,
            indicatorX + tabWidth - context.dp(12f),
            height - selectedPaint.strokeWidth / 2f,
            selectedPaint
        )
    }

    // Children (equal-weight views spanning the whole bar) handle taps and
    // long-presses themselves; the parent must not intercept or they would
    // never receive events. Empty-bar taps simply do nothing.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return true
    }
}
