package com.trove.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.trove.core.fs.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FileRowView with Nordic styling and subtle selection/press animations.
 *
 * Visual hierarchy:
 * - Nord Frost Ice Cyan (#88C0D0) folder icons and selection accents
 * - Nord Polar Night & Slate backgrounds with smooth animated selection highlight
 * - Micro-interaction: subtle 0.985x spring press compression and release bounce
 */
class FileRowView(ctx: Context) : View(ctx) {

    // Nordic Theme Palette
    private val colorNord0 = Color.parseColor("#2E3440")
    private val colorNord1 = Color.parseColor("#3B4252")
    private val colorNord3 = Color.parseColor("#4C566A")
    private val colorNord4 = Color.parseColor("#D8DEE9")
    private val colorNord6 = Color.parseColor("#ECEFF4")
    private val colorNord8 = Color.parseColor("#88C0D0") // Frost ice cyan
    private val colorNord10 = Color.parseColor("#5E81AC") // Deep arctic blue

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(15f)
        color = colorNord6
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = Color.parseColor("#81A1C1") // Nord9 glacier blue
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorNord8
    }
    private val selectionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 136, 192, 208) // Nord8 with 18% alpha
    }
    private val selectionAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorNord8
    }

    private val iconBounds = RectF()
    private val selectionBounds = RectF()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Selection animation state
    private var selectionFraction = 0f
    private var selectionAnimator: ValueAnimator? = null

    var isItemSelected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                animateSelection(value)
            }
        }

    var item: FileItem? = null
        set(v) {
            field = v
            // Dynamic icon tint based on item type
            iconPaint.color = when {
                v == null -> colorNord3
                v.isDirectory -> colorNord8 // Frost cyan for folders
                v.name.endsWith(".trve", ignoreCase = true) -> colorNord10 // Deep arctic for vault
                else -> colorNord1 // Slate for normal files
            }
            invalidate()
        }

    /** Decoded thumbnail for the row; drawn inside the icon tile when present. */
    var thumbnail: android.graphics.Bitmap? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /**
     * Computed directory size (async, cached by the adapter). When null the
     * row falls back to [FileItem.size], which is 0 for directories.
     */
    var displaySize: Long? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private fun animateSelection(selected: Boolean) {
        selectionAnimator?.cancel()
        val target = if (selected) 1f else 0f
        selectionAnimator = ValueAnimator.ofFloat(selectionFraction, target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                selectionFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = context.dpInt(58f) // Standard Nordic row height
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Subtle touch-down scale compression
                animate()
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Spring release back to identity
                animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(140)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(c: Canvas) {
        val fi = item ?: return

        // Animated Selection highlight
        if (selectionFraction > 0.01f) {
            selectionBgPaint.alpha = (45 * selectionFraction).toInt()
            selectionBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRect(selectionBounds, selectionBgPaint)

            // Subtle left accent bar in Nord8 Frost Cyan
            val barWidth = context.dp(4f) * selectionFraction
            c.drawRect(0f, 0f, barWidth, height.toFloat(), selectionAccentPaint)
        }

        val padding = context.dp(16f)
        val iconSize = context.dp(38f)

        // Item Icon (rounded rectangle; real thumbnail when one is loaded)
        iconBounds.set(
            padding,
            (height - iconSize) / 2f,
            padding + iconSize,
            (height + iconSize) / 2f
        )
        val thumb = thumbnail
        if (thumb != null && !thumb.isRecycled) {
            val radius = context.dp(6f)
            val path = android.graphics.Path().apply {
                addRoundRect(iconBounds, radius, radius, android.graphics.Path.Direction.CW)
            }
            c.save()
            c.clipPath(path)
            drawBitmapCover(c, thumb, iconBounds)
            c.restore()
        } else {
            c.drawRoundRect(iconBounds, context.dp(6f), context.dp(6f), iconPaint)
        }

        val textLeft = iconBounds.right + context.dp(16f)
        val availableWidth = width - textLeft - padding

        // Item Name
        val ellipsizedName = TextUtils.ellipsize(
            fi.name,
            namePaint,
            availableWidth,
            TextUtils.TruncateAt.END
        )
        c.drawText(ellipsizedName.toString(), textLeft, height / 2f - context.dp(2f), namePaint)

        // Meta (Size and Date)
        val shownSize = displaySize ?: fi.size
        val meta = "${formatSize(shownSize)}  •  ${dateFormatter.format(Date(fi.lastModified))}"
        c.drawText(meta, textLeft, height / 2f + context.dp(17f), metaPaint)
    }

    /** Center-crop (cover) draw of [bmp] into [bounds], preserving aspect. */
    private fun drawBitmapCover(c: Canvas, bmp: android.graphics.Bitmap, bounds: RectF) {
        val srcW = bmp.width.toFloat()
        val srcH = bmp.height.toFloat()
        if (srcW <= 0 || srcH <= 0) return
        val dstW = bounds.width()
        val dstH = bounds.height()
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = bounds.left - (drawW - dstW) / 2f
        val top = bounds.top - (drawH - dstH) / 2f
        c.drawBitmap(bmp, null, RectF(left, top, left + drawW, top + drawH), null)
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
