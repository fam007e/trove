package com.trove.ui.browse

import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.trove.core.fs.LocalFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MiX "Home" equivalent: a dashboard tab — storage card with usage bar,
 * media-category cards (MediaStore-backed) with live counts + sizes, and
 * shortcuts. Tapping a card opens the corresponding tab in the active pane.
 */
class TroveHomeFragment : Fragment() {

    /**
     * TEMP diagnostics: if the main thread is still wedged 3s after a card
     * click, dump every thread's stack to /sdcard/trove-anr.txt (readable
     * through Trove's own editor) so the freeze point is visible without adb.
     */
    private fun armWatchdog() {
        // Dump from a BACKGROUND thread: if the main thread is blocked, a
        // main-looper delayed runnable would never fire.
        Thread {
            try {
                Thread.sleep(3000)
                val sb = StringBuilder("ANR dump:\n")
                Thread.getAllStackTraces().forEach { (t, st) ->
                    if (t == Thread.currentThread() || st.size > 8) {
                        sb.append("=== ${t.name} (${t.state}) ===\n")
                        st.take(30).forEach { frame -> sb.append("  at $frame\n") }
                    }
                }
                java.io.File(
                    Environment.getExternalStorageDirectory(),
                    "trove-anr.txt"
                ).writeText(sb.toString())
            } catch (_: Exception) { }
        }.start()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // TEMP diagnostics: device logcat is flooded by kernel spam, so capture
        // crashes to a file readable through Trove's own text editor.
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(
                    Environment.getExternalStorageDirectory(),
                    "trove-crash.txt"
                ).appendText("thread=${t.name}\n${android.util.Log.getStackTraceString(e)}\n\n")
            } catch (_: Exception) { }
        }
        // TEMP diagnostics: device logcat is flooded by kernel spam, so capture
        // crashes to a file we can read back through the text editor.
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(
                    Environment.getExternalStorageDirectory(),
                    "trove-crash.txt"
                ).appendText("thread=${t.name}\n${android.util.Log.getStackTraceString(e)}\n\n")
            } catch (_: Exception) { }
        }
        val context = requireContext()
        val scroll = android.widget.ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#2E3440"))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        fun header(text: String): TextView = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#81A1C1"))
            textSize = 13f
            setPadding(0, 40, 0, 16)
        }

        fun card(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#3B4252"))
                setPadding(32, 28, 32, 28)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.parseColor("#ECEFF4"))
                    textSize = 16f
                })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#81A1C1"))
                    textSize = 12f
                })
                setOnClickListener { onClick() }
            }

        // ---- STORAGE ----
        root.addView(header("STORAGE"))
        val storageBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4C566A"))
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#88C0D0"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }
        val storageTitle = TextView(context).apply {
            text = "Internal storage"
            setTextColor(Color.parseColor("#ECEFF4"))
            textSize = 16f
        }
        val storageSub = TextView(context).apply {
            text = "Measuring…"
            setTextColor(Color.parseColor("#81A1C1"))
            textSize = 12f
        }
        val storageCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#3B4252"))
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            addView(storageTitle)
            addView(storageSub)
            addView(storageBar)
            setOnClickListener {
                val act = activity as? com.trove.ui.browse.BrowseActivity
                if (act != null) {
                    act.openTabFromHome(
                        LocalFileItem(File(Environment.getExternalStorageDirectory().absolutePath)),
                        "Internal storage"
                    )
                } else {
                    Toast.makeText(
                        context,
                        "Host mismatch: ${activity?.javaClass?.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(storageCard)

        // ---- CATEGORIES ----
        root.addView(header("CATEGORIES"))
        val categories = listOf(
            "Audio" to MediaStoreFileItem.categories(context)[0],
            "Video" to MediaStoreFileItem.categories(context)[1],
            "Images" to MediaStoreFileItem.categories(context)[2],
            "Documents" to MediaStoreFileItem.categories(context)[3]
        )
        categories.forEach { (label, item) ->
            val titleView = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#ECEFF4"))
                textSize = 16f
            }
            val subView = TextView(context).apply {
                text = "Measuring…"
                setTextColor(Color.parseColor("#81A1C1"))
                textSize = 12f
            }
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#3B4252"))
                setPadding(32, 28, 32, 28)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
                addView(titleView)
                addView(subView)
                setOnClickListener {
                    Toast.makeText(context, "Opening $label…", Toast.LENGTH_SHORT).show()
                    armWatchdog()
                    val act = activity as? com.trove.ui.browse.BrowseActivity
                    if (act != null) {
                        act.openTabFromHome(item, label)
                    } else {
                        Toast.makeText(
                            context,
                            "Host mismatch: ${activity?.javaClass?.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
            viewLifecycleOwner.lifecycleScope.launch {
                val (count, bytes) = MediaStoreFileItem.stats(context, itemCollection(label), docSelection(label))
                subView.text = "${android.text.format.Formatter.formatFileSize(context, bytes)} · $count items"
            }
        }

        // ---- SHORTCUTS ----
        root.addView(header("SHORTCUTS"))
        val downloadDir = File(Environment.getExternalStorageDirectory(), "Download")
        root.addView(
            card("Download", downloadDir.absolutePath) {
                (activity as? com.trove.ui.browse.BrowseActivity)?.openTabFromHome(
                    LocalFileItem(downloadDir), "Download"
                )
            }
        )

        // ---- TOOLS ----
        root.addView(header("TOOLS"))
        root.addView(
            card("Cleaner", "App caches · corpses · system junk") {
                startActivity(
                    android.content.Intent(context, com.trove.feature.cleaner.TroveCleanerActivity::class.java)
                )
            }
        )

        // storage stats
        viewLifecycleOwner.lifecycleScope.launch {
            val f = File(Environment.getExternalStorageDirectory().absolutePath)
            val (total, usable) = withContext(Dispatchers.IO) {
                try { Triple(f.totalSpace, f.usableSpace, true) } catch (_: Exception) { Triple(0L, 0L, false) }
            }
            if (total > 0) {
                val usedPct = ((total - usable) * 100 / total).toInt()
                storageBar.max = 100
                storageBar.progress = usedPct
                val fmt = android.text.format.Formatter.formatFileSize(context, total)
                val free = android.text.format.Formatter.formatFileSize(context, usable)
                storageSub.text = "$fmt total · $free free · $usedPct% used"
            } else {
                storageSub.text = "Usage unavailable"
            }
        }

        return scroll
    }

    private fun docSelection(label: String): String? = if (label == "Documents")
        "(mime_type LIKE 'text/%' OR mime_type LIKE 'application/pdf' " +
        "OR mime_type LIKE 'application/msword%' OR mime_type LIKE 'application/vnd.openxmlformats-officedocument%' " +
        "OR mime_type LIKE 'application/rtf' OR mime_type LIKE 'application/vnd.ms-excel%')" else null

    private fun itemCollection(label: String): android.net.Uri =
        MediaStoreFileItem.categories(requireContext()).first { it.name == label }.let { item ->
            // reuse the category root's collection via reflection-free re-lookup
            when (label) {
                "Audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                "Video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "Images" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> android.provider.MediaStore.Files.getContentUri("external")
            }
        }

    companion object {
        /** Marker path identifying the Home dashboard tab. */
        const val HOME_PATH = "home://trove"

        /** Explicit marker FileItem — LocalFileItem would mangle the scheme
         *  path via absolutePath, breaking the createFragment check. */
        object Marker : com.trove.core.fs.FileItem {
            override val name = "Home"
            override val path = HOME_PATH
            override val size = 0L
            override val lastModified = 0L
            override val isDirectory = true
            override val permissions = com.trove.core.fs.PosixPermissions.DIRECTORY_DEFAULT
            override val mimeType = "inode/directory"
            override suspend fun listFiles() = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun openInputStream() = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun openOutputStream(append: Boolean) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun createFile(name: String) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun createDirectory(name: String) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun delete() = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun rename(newName: String) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun copyTo(dest: com.trove.core.fs.FileItem) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun moveTo(destDir: com.trove.core.fs.FileItem, newName: String) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun findChild(name: String) = com.trove.core.fs.Result.Failure(UnsupportedOperationException())
            override suspend fun getParent() = com.trove.core.fs.Result.Success(null)
            override suspend fun exists() = true
        }

        fun newInstance(): TroveHomeFragment = TroveHomeFragment()
    }
}
