package com.trove.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.trove.core.fs.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileRowView(ctx: Context) : View(ctx) {
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(16f)
        color = Color.BLACK
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = Color.GRAY
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
    }
    
    private val iconBounds = RectF()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    var item: FileItem? = null
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = context.dpInt(56f) // Standard list item height
        setMeasuredDimension(width, height)
    }

    override fun onDraw(c: Canvas) {
        val fi = item ?: return
        val padding = context.dp(16f)
        val iconSize = context.dp(40f)
        
        // Icon
        iconBounds.set(padding, (height - iconSize) / 2f, padding + iconSize, (height + iconSize) / 2f)
        c.drawRoundRect(iconBounds, context.dp(4f), context.dp(4f), iconPaint)
        
        val textLeft = iconBounds.right + context.dp(16f)
        val availableWidth = width - textLeft - padding
        
        // Name
        val ellipsizedName = TextUtils.ellipsize(fi.name, namePaint, availableWidth, TextUtils.TruncateAt.END)
        c.drawText(ellipsizedName.toString(), textLeft, height / 2f - context.dp(2f), namePaint)
        
        // Meta (Size and Date)
        val meta = "${formatSize(fi.size)} | ${dateFormatter.format(Date(fi.lastModified))}"
        c.drawText(meta, textLeft, height / 2f + context.dp(18f), metaPaint)
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
