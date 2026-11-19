package com.trove.ui.browse

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.trove.core.fs.LocalFileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local-directory picker dialog: browse from the storage root, tap to
 * descend, ".." to ascend, SELECT HERE to return the chosen directory.
 * Local-only by design — copy/move across remote backends arrives with
 * the cloud phase (destination then comes from open tabs).
 */
class DestinationPickerDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPicked: (LocalFileItem) -> Unit
) {

    private var current: LocalFileItem = LocalFileItem(File("/storage/emulated/0"))

    private lateinit var listView: ListView
    private lateinit var pathView: TextView
    private lateinit var dialog: AlertDialog
    private var dirs: List<LocalFileItem> = emptyList()

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        pathView = TextView(context).apply {
            setPadding(24, 16, 24, 16)
            setTextColor(0xFF88C0D0.toInt())
            textSize = 12f
        }
        root.addView(pathView)
        listView = ListView(context)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 400
        ))
        val select = Button(context).apply {
            text = "SELECT THIS FOLDER"
            setBackgroundColor(0xFF4C566A.toInt())
            setTextColor(0xFF88C0D0.toInt())
        }
        root.addView(select, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(24, 8, 24, 16) })

        select.setOnClickListener { onPicked(current); dialog.dismiss() }

        dialog = AlertDialog.Builder(context)
            .setTitle("Choose destination")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = dirs.getOrNull(position) ?: return@setOnItemClickListener
            current = entry
            load()
        }

        dialog.show()
        load()
    }

    private fun load() {
        pathView.text = current.path
        scope.launch {
            val listing = withContext(Dispatchers.IO) {
                when (val res = current.listFiles()) {
                    is com.trove.core.fs.Result.Success ->
                        res.value.filterIsInstance<LocalFileItem>()
                            .filter { it.isDirectory }
                            .sortedBy { it.name.lowercase() }
                    else -> emptyList()
                }
            }
            dirs = listing
            val rows: List<LocalFileItem?> =
                if (File(current.path).parentFile != null) listOf(null) + listing else listing
            listView.adapter = object : BaseAdapter() {
                override fun getCount(): Int = rows.size
                override fun getItem(position: Int): Any? = rows[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        textSize = 15f
                        setTextColor(0xFFECEFF4.toInt())
                        setPadding(48, 28, 24, 28)
                    }
                    val entry = rows[position]
                    tv.text = entry?.name ?: ".."
                    return tv
                }
            }
            listView.setOnItemClickListener { _, _, position, _ ->
                val entry = rows.getOrNull(position) ?: return@setOnItemClickListener
                if (entry == null) {
                    current = LocalFileItem(File(current.path).parentFile!!)
                } else {
                    current = entry
                }
                load()
            }
        }
    }
}
