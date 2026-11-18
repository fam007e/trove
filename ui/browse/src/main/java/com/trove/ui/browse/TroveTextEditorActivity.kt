package com.trove.ui.browse

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trove.core.fs.LocalFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal MiX-style text editor: reads any file through the FileItem stream
 * abstraction, edits in a monospace field, writes back atomically on save.
 * Currently path-based (local + any backend surfaced as a LocalFileItem);
 * SAF/remote editing goes through the cache-copy path in BrowseFragment.
 */
class TroveTextEditorActivity : AppCompatActivity() {

    private lateinit var item: LocalFileItem
    private lateinit var editor: EditText
    private lateinit var saveButton: Button
    private lateinit var statusView: TextView
    private var dirty = false
    private var readOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        item = LocalFileItem(File(path))
        title = File(path).name

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2E3440"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#3B4252"))
            setPadding(16, 8, 16, 8)
        }
        statusView = TextView(this).apply {
            text = "utf-8"
            setTextColor(Color.parseColor("#81A1C1"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(statusView)
        saveButton = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.parseColor("#88C0D0"))
            setBackgroundColor(Color.parseColor("#4C566A"))
            isEnabled = false
            setOnClickListener { save() }
        }
        topBar.addView(saveButton)
        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        editor = EditText(this).apply {
            setTypeface(Typeface.MONOSPACE)
            setTextSize(13f)
            setTextColor(Color.parseColor("#ECEFF4"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            isEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressDirtyFlag) {
                        dirty = true
                        saveButton.isEnabled = !readOnly
                        statusView.text = "modified"
                    }
                }
            })
        }
        root.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        load()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dirty && !readOnly) {
                    androidx.appcompat.app.AlertDialog.Builder(this@TroveTextEditorActivity)
                        .setTitle("Unsaved changes")
                        .setMessage("Discard changes without saving?")
                        .setPositiveButton("Discard") { _, _ -> finish() }
                        .setNegativeButton("Keep editing", null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private var suppressDirtyFlag = false

    private fun load() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                when (val res = item.openInputStream()) {
                    is com.trove.core.fs.Result.Success ->
                        res.value.use { it.readBytes().toString(Charsets.UTF_8) }
                    is com.trove.core.fs.Result.Failure -> null
                }
            }
            if (text == null) {
                Toast.makeText(this@TroveTextEditorActivity, "Failed to read file", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            suppressDirtyFlag = true
            editor.setText(text)
            suppressDirtyFlag = false
            readOnly = !item.exists() || !canWrite(item)
            editor.isEnabled = !readOnly
            saveButton.isEnabled = false
            statusView.text = if (readOnly) "read-only" else "${text.length} chars · utf-8"
        }
    }

    private fun canWrite(item: LocalFileItem): Boolean = try {
        File(item.path).canWrite()
    } catch (_: Exception) {
        false
    }

    private fun save() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (val res = item.openOutputStream(append = false)) {
                    is com.trove.core.fs.Result.Success ->
                        try {
                            res.value.use { it.write(editor.text.toString().toByteArray(Charsets.UTF_8)) }
                            true
                        } catch (_: Exception) {
                            false
                        }
                    is com.trove.core.fs.Result.Failure -> false
                }
            }
            if (ok) {
                dirty = false
                saveButton.isEnabled = false
                statusView.text = "saved"
                Toast.makeText(this@TroveTextEditorActivity, "Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@TroveTextEditorActivity, "Save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_text_path"
    }
}
