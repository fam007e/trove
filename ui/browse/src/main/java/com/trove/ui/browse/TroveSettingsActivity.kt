package com.trove.ui.browse

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal settings screen: list preferences with instant persist via Prefs.
 * Grows with the feature set (servers config, theme, editor defaults…).
 */
class TroveSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        title = "Settings"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2E3440"))
            setPadding(32, 32, 32, 32)
        }

        fun header(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#81A1C1"))
            textSize = 12f
            setPadding(0, 32, 0, 8)
        }

        root.addView(header("LIST"))

        root.addView(Switch(this).apply {
            text = "Show hidden files"
            isChecked = Prefs.showHidden
            setTextColor(Color.parseColor("#ECEFF4"))
            textSize = 16f
            setPadding(0, 16, 0, 16)
            setOnCheckedChangeListener { _, checked -> Prefs.showHidden = checked }
        })

        val sortLabel = TextView(this).apply {
            text = "Sort by: ${Prefs.sortBy.replaceFirstChar { it.uppercase() }}"
            setTextColor(Color.parseColor("#ECEFF4"))
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(sortLabel)

        val sortCycle = Button(this).apply {
            text = "Change sort order"
            setBackgroundColor(Color.parseColor("#3B4252"))
            setTextColor(Color.parseColor("#88C0D0"))
            setOnClickListener {
                Prefs.sortBy = when (Prefs.sortBy) {
                    "name" -> "size"
                    "size" -> "date"
                    else -> "name"
                }
                sortLabel.text = "Sort by: ${Prefs.sortBy.replaceFirstChar { it.uppercase() }}"
            }
        }
        root.addView(sortCycle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(header("CACHE"))

        root.addView(Button(this).apply {
            text = "Clear thumbnail cache"
            setBackgroundColor(Color.parseColor("#3B4252"))
            setTextColor(Color.parseColor("#88C0D0"))
            setOnClickListener {
                ThumbnailCache.clear()
                Toast.makeText(this@TroveSettingsActivity, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(header("ABOUT"))

        root.addView(TextView(this).apply {
            text = "Trove v1.0 — clean-room file manager inspired by MiXplorer.\n" +
                "Signing certificate is GPG-endorsed; see signing/ in the repo."
            setTextColor(Color.parseColor("#D8DEE9"))
            textSize = 13f
            setPadding(0, 8, 0, 8)
        })

        setContentView(root)
    }
}
