package com.trove.ui.widgets

import android.content.Context
import android.util.TypedValue

fun Context.dp(value: Float): Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

fun Context.sp(value: Float): Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

fun Context.dpInt(value: Float): Int = dp(value).toInt()
fun Context.spInt(value: Float): Int = sp(value).toInt()
