package com.trove.ui.browse

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.Result
import com.trove.core.fs.resolve
import com.trove.feature.archive.ArchiveFileItem
import com.trove.feature.archive.ArchiveType
import com.trove.feature.image.ImageDecoderUtils
import com.trove.feature.image.TroveImageViewerActivity
import com.trove.feature.pdf.TrovePdfDocument
import com.trove.feature.pdf.TrovePdfViewerActivity
import com.trove.feature.tagger.TroveMetadataEditor
import com.trove.feature.tagger.MetadataBundle
import com.trove.feature.media.MediaHelper
import com.trove.feature.media.TrovePlayerActivity
import com.trove.feature.media.TroveMediaRegistry
import com.trove.ui.widgets.TroveBreadcrumbBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class BrowseFragment : Fragment() {
    interface OnDirectoryChangedListener {
        fun onDirectoryChanged(directory: FileItem)
    }

    /** Actions dispatched from the activity's overflow menu. */
    enum class MenuAction {
        SORT_NAME, SORT_SIZE, SORT_DATE, TOGGLE_HIDDEN,
        NEW_FOLDER, SERVER_FTP, SERVER_WEBDAV, SERVER_SFTP
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var breadcrumbBar: TroveBreadcrumbBar
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCountView: TextView
    private lateinit var filterBar: LinearLayout
    private lateinit var filterLabel: TextView
    private var currentDirectory: FileItem? = null
    private var rootDirectory: FileItem? = null
    private var searchFilter: String? = null

    companion object {
        // Backed by Prefs (persisted); kept as accessors so existing
        // call sites (BrowseActivity menu) keep working.
        var sortBy: String
            get() = Prefs.sortBy
            set(v) { Prefs.sortBy = v }
        var showHidden: Boolean
            get() = Prefs.showHidden
            set(v) { Prefs.showHidden = v }

        fun newInstance(root: FileItem): BrowseFragment {
            val fragment = BrowseFragment()
            fragment.rootDirectory = root
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        Prefs.init(context)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
        }

        breadcrumbBar = TroveBreadcrumbBar(context).apply {
            setOnSegmentClickListener { index ->
                navigateToIndex(index)
            }
        }
        rootLayout.addView(breadcrumbBar)

        // Search filter chip (visible only while a filter is active)
        filterBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#434C5E"))
            setPadding(32, 12, 32, 12)
            visibility = View.GONE
        }
        filterLabel = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        filterBar.addView(filterLabel)
        filterBar.addView(Button(context).apply {
            text = "✕"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#BF616A"))
            setOnClickListener { setSearchFilter(null) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootLayout.addView(filterBar)

        // Selection action bar (visible only while items are selected)
        selectionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#4C566A"))
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        selectionCountView = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#88C0D0"))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        selectionBar.addView(selectionCountView)
        fun barButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
            text = label
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
            setMinimumWidth(0)
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
            textSize = 12f
            setOnClickListener { onClick() }
        }
        // Horizontal scroll keeps every action reachable on narrow screens
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(barButton("✎") { renameSelection() })
        actions.addView(barButton("⚙") { propertiesSelection() })
        actions.addView(barButton("⧉") { copyOrMoveSelection(move = false) })
        actions.addView(barButton("⇄") { copyOrMoveSelection(move = true) })
        actions.addView(barButton("⇪") { shareSelection() })
        actions.addView(barButton("🗜") { archiveSelection() })
        actions.addView(barButton("🗑") { deleteSelection() })
        actions.addView(barButton("✕") { adapter.clearSelection() })
        selectionBar.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            (44 * resources.displayMetrics.density).toInt()
        ))
        rootLayout.addView(selectionBar)

        recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            layoutManager = LinearLayoutManager(context)
        }

        adapter = FileAdapter(
            items = emptyList(),
            scope = lifecycleScope,
            onItemClick = { item ->
                if (adapter.selectedItems.isNotEmpty()) {
                    // In selection mode taps toggle instead of opening
                    adapter.toggleSelection(item)
                } else if (item.isDirectory) {
                    loadDirectory(item)
                } else if (ArchiveType.isArchive(item)) {
                    openArchive(item)
                } else if (ImageDecoderUtils.isImage(item.name)) {
                    openImageViewer(item)
                } else if (TrovePdfDocument.isPdf(item.name)) {
                    openPdfViewer(item)
                } else if (MediaHelper.isMedia(item.name)) {
                    openMediaPlayer(item)
                } else if (isTextFile(item.name)) {
                    openTextEditor(item)
                } else {
                    openWithExternalApp(item)
                }
            },
            onItemLongClick = { item ->
                adapter.toggleSelection(item)
                true
            }
        )
        adapter.onSelectionChanged = { count -> updateSelectionBar(count) }
        recyclerView.adapter = adapter
        rootLayout.addView(recyclerView)

        return rootLayout
    }

    private fun updateSelectionBar(count: Int) {
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        selectionCountView.text = if (count > 0) "$count selected" else ""
    }

    private fun setSearchFilter(query: String?) {
        searchFilter = query?.takeIf { it.isNotBlank() }
        filterBar.visibility = if (searchFilter != null) View.VISIBLE else View.GONE
        filterLabel.text = "Filter: \"$searchFilter\" in ${currentDirectory?.name ?: ""}"
        reload()
    }

    private fun openArchive(item: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(context, "Opening archive...", Toast.LENGTH_SHORT).show()
            when (val res = ArchiveFileItem.open(item)) {
                is Result.Success -> {
                    loadDirectory(res.value)
                }
                is Result.Failure -> {
                    Toast.makeText(context, "Failed to open archive: ${res.exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openImageViewer(selectedItem: FileItem) {
        val current = currentDirectory ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val res = current.listFiles()) {
                is Result.Success -> {
                    val imageItems = res.value.filter { !it.isDirectory && ImageDecoderUtils.isImage(it.name) }
                    val imagePaths = ArrayList(imageItems.map { it.path })
                    val startIndex = imageItems.indexOfFirst { it.path == selectedItem.path }.coerceAtLeast(0)

                    val intent = Intent(context, TroveImageViewerActivity::class.java).apply {
                        putStringArrayListExtra(TroveImageViewerActivity.EXTRA_IMAGE_PATHS, imagePaths)
                        putExtra(TroveImageViewerActivity.EXTRA_START_INDEX, startIndex)
                    }
                    startTransitionActivity(intent)
                }
                is Result.Failure -> {
                    val intent = Intent(context, TroveImageViewerActivity::class.java).apply {
                        putStringArrayListExtra(TroveImageViewerActivity.EXTRA_IMAGE_PATHS, arrayListOf(selectedItem.path))
                        putExtra(TroveImageViewerActivity.EXTRA_START_INDEX, 0)
                    }
                    startTransitionActivity(intent)
                }
            }
        }
    }

    private fun openPdfViewer(item: FileItem) {
        val intent = Intent(context, TrovePdfViewerActivity::class.java).apply {
            putExtra(TrovePdfViewerActivity.EXTRA_PDF_PATH, item.path)
        }
        startTransitionActivity(intent)
    }

    private fun openMediaPlayer(selectedItem: FileItem) {
        val current = currentDirectory ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val res = current.listFiles()) {
                is Result.Success -> {
                    val mediaItems = res.value.filter { !it.isDirectory && MediaHelper.isMedia(it.name) }
                    mediaItems.forEach { TroveMediaRegistry.register(it) }
                    val mediaPaths = ArrayList(mediaItems.map { it.path })
                    val startIndex = mediaItems.indexOfFirst { it.path == selectedItem.path }.coerceAtLeast(0)

                    val intent = Intent(context, TrovePlayerActivity::class.java).apply {
                        putStringArrayListExtra(TrovePlayerActivity.EXTRA_MEDIA_PATHS, mediaPaths)
                        putExtra(TrovePlayerActivity.EXTRA_START_INDEX, startIndex)
                    }
                    startTransitionActivity(intent)
                }
                is Result.Failure -> {
                    TroveMediaRegistry.register(selectedItem)
                    val intent = Intent(context, TrovePlayerActivity::class.java).apply {
                        putStringArrayListExtra(TrovePlayerActivity.EXTRA_MEDIA_PATHS, arrayListOf(selectedItem.path))
                        putExtra(TrovePlayerActivity.EXTRA_START_INDEX, 0)
                    }
                    startTransitionActivity(intent)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootDirectory?.let { loadDirectory(it) }
    }

    private fun loadDirectory(directory: FileItem) {
        currentDirectory = directory
        breadcrumbBar.setPath(directory.path)
        (activity as? OnDirectoryChangedListener)?.onDirectoryChanged(directory)
        
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = directory.listFiles()) {
                is Result.Success -> {
                    adapter.updateItems(applyListSettings(result.value))
                    applyDirectoryTransition()
                }
                is Result.Failure -> {
                    Toast.makeText(context, "Error: ${result.exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Subtle cascading slide-up and fade-in transition when entering a directory.
     * 160ms duration with DecelerateInterpolator ensures crisp, non-distracting navigation.
     */
    private fun applyDirectoryTransition() {
        val animSet = android.view.animation.AnimationSet(true).apply {
            addAnimation(android.view.animation.AlphaAnimation(0.0f, 1.0f).apply {
                duration = 160
            })
            addAnimation(android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                android.view.animation.Animation.ABSOLUTE, 24f,
                android.view.animation.Animation.ABSOLUTE, 0f
            ).apply {
                duration = 160
            })
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        val controller = android.view.animation.LayoutAnimationController(animSet, 0.04f)
        recyclerView.layoutAnimation = controller
        recyclerView.scheduleLayoutAnimation()
    }

    /** Dirs first, then the chosen key; hidden files filtered by the toggle. */
    private fun applyListSettings(list: List<FileItem>): List<FileItem> {
        var visible = if (Prefs.showHidden) list else list.filter { !it.name.startsWith(".") }
        searchFilter?.let { q ->
            visible = visible.filter { it.name.contains(q, ignoreCase = true) }
        }
        val (dirs, files) = visible.partition { it.isDirectory }
        val sorted = when (sortBy) {
            "size" -> files.sortedByDescending { it.size }
            "date" -> files.sortedByDescending { it.lastModified }
            else -> files.sortedBy { it.name.lowercase() }
        }
        val sortedDirs = when (sortBy) {
            "date" -> dirs.sortedByDescending { it.lastModified }
            else -> dirs.sortedBy { it.name.lowercase() }
        }
        return sortedDirs + sorted
    }

    /** Entry point for the activity's overflow menu. */
    fun onMenuAction(action: MenuAction) {
        when (action) {
            MenuAction.SORT_NAME -> { sortBy = "name"; reload() }
            MenuAction.SORT_SIZE -> { sortBy = "size"; reload() }
            MenuAction.SORT_DATE -> { sortBy = "date"; reload() }
            MenuAction.TOGGLE_HIDDEN -> { showHidden = !showHidden; reload() }
            MenuAction.NEW_FOLDER -> showNewFolderDialog()
            MenuAction.SERVER_FTP -> toggleServer(
                com.trove.server.ftp.FtpServerService::class.java,
                com.trove.server.ftp.FtpServerService.running, "FTP"
            )
            MenuAction.SERVER_WEBDAV -> toggleServer(
                com.trove.server.webdav.WebDavServerService::class.java,
                com.trove.server.webdav.WebDavServerService.running, "WebDAV"
            )
            MenuAction.SERVER_SFTP -> toggleServer(
                com.trove.server.sftp.SftpServerService::class.java,
                com.trove.server.sftp.SftpServerService.running, "SFTP"
            )
        }
    }

    fun reload() {
        currentDirectory?.let { loadDirectory(it) }
    }

    private fun toggleServer(service: Class<*>, running: Boolean, label: String) {
        val ctx = context ?: return
        val intent = Intent(ctx, service)
        if (running) {
            ctx.stopService(intent)
            Toast.makeText(ctx, "$label server stopped", Toast.LENGTH_SHORT).show()
        } else {
            ctx.startService(intent)
            Toast.makeText(ctx, "$label server starting…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNewFolderDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            hint = "Folder name"
        }
        AlertDialog.Builder(ctx)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val dir = currentDirectory ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val res = dir.createDirectory(name)) {
                        is Result.Success -> reload()
                        is Result.Failure -> Toast.makeText(
                            ctx, "Failed: ${res.exception.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Opens any file the built-in viewers don't handle (.py, .xml, .apk, …)
     * with an external app via FileProvider. Local files are shared in place;
     * everything else (SAF, remote, inside archives) is streamed to a cache
     * copy first — the stream/listener abstractions make this transparent.
     */
    private fun openWithExternalApp(item: FileItem) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (item is com.trove.core.fs.LocalFileItem) {
                    java.io.File(item.path)
                } else {
                    val tmp = java.io.File(ctx.cacheDir, "open_" + item.name)
                    val input = item.openInputStream().getOrNull()
                    if (input == null) null
                    else {
                        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp
                    }
                }
            }
            if (file == null || !file.exists()) {
                Toast.makeText(ctx, "Cannot read this file", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFor(item))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                ctx.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(ctx, "No app can open .${item.name.substringAfterLast('.')} files", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Extensions the built-in editor handles by default (MiX behavior). */
    private val textExtensions = setOf(
        "txt", "md", "json", "xml", "py", "sh", "js", "kt", "java", "html", "htm",
        "csv", "log", "ini", "yml", "yaml", "c", "h", "cpp", "hpp", "css", "sql",
        "toml", "gradle", "properties", "php", "rb", "pl", "lua", "conf", "xml"
    )

    private fun isTextFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in textExtensions

    private fun openTextEditor(item: FileItem) {
        val ctx = context ?: return
        if (item is com.trove.core.fs.LocalFileItem) {
            ctx.startActivity(
                Intent(ctx, TroveTextEditorActivity::class.java)
                    .putExtra(TroveTextEditorActivity.EXTRA_PATH, item.path)
            )
        } else {
            // Non-local backends edit through a cache copy; opened externally instead
            openWithExternalApp(item)
        }
    }

    fun showSearchDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            hint = "Search in ${currentDirectory?.name ?: ""}"
        }
        AlertDialog.Builder(ctx)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Filter") { _, _ -> setSearchFilter(input.text.toString()) }
            .setNegativeButton("Clear", null)
            .show()
    }

    // ---- Selection operations ------------------------------------------------

    private fun selectedList(): List<FileItem> = adapter.selectedItems.toList()

    private fun propertiesSelection() {
        val items = selectedList()
        if (items.size == 1) showFileProperties(items[0])
    }

    private fun renameSelection() {
        val item = selectedList().singleOrNull() ?: return
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            setText(item.name)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.name) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        when (val res = item.rename(newName)) {
                            is Result.Success -> { adapter.clearSelection(); reload() }
                            is Result.Failure -> Toast.makeText(
                                ctx, "Rename failed: ${res.exception.message}", Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelection() {
        val items = selectedList()
        if (items.isEmpty()) return
        val ctx = context ?: return

        // Inside the recycle bin, delete means delete permanently.
        if (isInTrash()) {
            AlertDialog.Builder(ctx)
                .setTitle("Delete ${items.size} item(s) permanently?")
                .setPositiveButton("Delete forever") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        var failed = 0
                        withContext(Dispatchers.IO) {
                            items.forEach { if (it.delete() is Result.Failure) failed++ }
                        }
                        adapter.clearSelection()
                        reload()
                        if (failed > 0) {
                            Toast.makeText(ctx, "$failed could not be deleted", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        AlertDialog.Builder(ctx)
            .setTitle("Delete ${items.size} item(s)?")
            .setMessage(items.take(5).joinToString("\n") { it.name } + if (items.size > 5) "\n…" else "")
            .setPositiveButton("Delete") { _, _ ->
                moveToTrash(items) {
                    adapter.clearSelection()
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyOrMoveSelection(move: Boolean) {
        val items = selectedList()
        if (items.isEmpty()) return
        val ctx = context ?: return

        // In the recycle bin the ⇄ action restores from the trash index.
        if (isInTrash() && move) {
            restoreSelection()
            return
        }

        DestinationPickerDialog(ctx, viewLifecycleOwner.lifecycleScope) { destDir ->
            viewLifecycleOwner.lifecycleScope.launch {
                var ok = 0
                var failed = 0
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    items.forEach { item ->
                        val res = if (move) item.moveTo(destDir, item.name)
                        else item.copyTo(destDir)
                        if (res is Result.Success) ok++ else failed++
                    }
                }
                adapter.clearSelection()
                reload()
                val verb = if (move) "Moved" else "Copied"
                Toast.makeText(
                    ctx,
                    "$verb $ok to ${destDir.path}" + if (failed > 0) " · $failed failed" else "",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.show()
    }

    private fun shareSelection() {
        val items = selectedList()
        if (items.isEmpty()) return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val uris = withContext(kotlinx.coroutines.Dispatchers.IO) {
                items.mapNotNull { item ->
                    val local = withContext(kotlinx.coroutines.Dispatchers.IO) { stageShareFile(item) }
                    local?.let {
                        androidx.core.content.FileProvider.getUriForFile(
                            ctx, "${ctx.packageName}.fileprovider", it
                        )
                    }
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(ctx, "Nothing to share", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM, ArrayList(uris)
                    )
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    private suspend fun stageShareFile(item: FileItem): java.io.File? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (item is com.trove.core.fs.LocalFileItem) {
                    java.io.File(item.path)
                } else {
                    val tmp = java.io.File(requireContext().cacheDir, "share_" + item.name)
                    item.openInputStream().getOrNull()?.use { i ->
                        tmp.outputStream().use { o -> i.copyTo(o) }
                    } ?: return@withContext null
                    tmp
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun archiveSelection() {
        val items = selectedList()
        if (items.isEmpty()) return
        val ctx = context ?: return
        val formats = arrayOf(
            com.trove.feature.archive.ArchiveType.ZIP,
            com.trove.feature.archive.ArchiveType.SEVEN_Z,
            com.trove.feature.archive.ArchiveType.TAR,
            com.trove.feature.archive.ArchiveType.TAR_GZ,
            com.trove.feature.archive.ArchiveType.TAR_BZ2,
            com.trove.feature.archive.ArchiveType.TAR_XZ,
            com.trove.feature.archive.ArchiveType.GZIP,
            com.trove.feature.archive.ArchiveType.BZIP2,
            com.trove.feature.archive.ArchiveType.XZ
        )
        val input = android.widget.EditText(ctx).apply { setSingleLine(true); setText("archive") }
        val spinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                formats.map { it.name.lowercase() }
            )
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(ctx).apply { text = "Name" })
            addView(input)
            addView(TextView(ctx).apply { text = "Format" })
            addView(spinner)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Archive ${items.size} item(s)")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val format = formats[spinner.selectedItemPosition]
                val base = input.text.toString().trim().ifEmpty { "archive" }
                val name = "${base}.${format.defaultExtension}"
                viewLifecycleOwner.lifecycleScope.launch {
                    val ctx2 = context ?: return@launch
                    val dest = when (val res = currentDirectory!!.createFile(name)) {
                        is Result.Success -> res.value
                        is Result.Failure -> {
                            Toast.makeText(ctx2, "Failed: ${res.exception.message}", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                    Toast.makeText(ctx2, "Creating $name…", Toast.LENGTH_SHORT).show()
                    when (val res = com.trove.feature.archive.ArchiveCreator.createArchive(
                        items, dest, com.trove.feature.archive.ArchiveCreationParams(format = format)
                    )) {
                        is Result.Success -> { adapter.clearSelection(); reload() }
                        is Result.Failure -> Toast.makeText(
                            ctx2, "Archive failed: ${res.exception.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showBookmarks() {
        val ctx = context ?: return
        val bookmarks = Prefs.bookmarks().toList()
        if (bookmarks.isEmpty()) {
            Toast.makeText(ctx, "No bookmarks yet — add one from the menu", Toast.LENGTH_SHORT).show()
            return
        }
        val names = bookmarks.map { it.substringBefore('|') }
        AlertDialog.Builder(ctx)
            .setTitle("Bookmarks")
            .setItems(names.toTypedArray()) { _, which ->
                val path = bookmarks[which].substringAfter('|')
                val target = com.trove.core.fs.LocalFileItem(java.io.File(path))
                loadDirectory(target)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    fun addBookmark() {
        val dir = currentDirectory ?: return
        val name = dir.name.ifEmpty { dir.path }
        Prefs.addBookmark(name, dir.path)
        Toast.makeText(context, "Bookmarked: $name", Toast.LENGTH_SHORT).show()
    }

    /** Extract-all for the current tab when it is rooted at an archive. */
    fun extractArchiveAll() {
        val archive = rootDirectory as? ArchiveFileItem ?: return
        val ctx = context ?: return
        DestinationPickerDialog(ctx, viewLifecycleOwner.lifecycleScope) { destDir ->
            viewLifecycleOwner.lifecycleScope.launch {
                Toast.makeText(ctx, "Extracting to ${destDir.path}…", Toast.LENGTH_SHORT).show()
                when (val res = com.trove.feature.archive.ArchiveExtractor.extractAll(archive, destDir)) {
                    is Result.Success -> Toast.makeText(ctx, "Extraction complete", Toast.LENGTH_LONG).show()
                    is Result.Failure -> Toast.makeText(
                        ctx, "Extract failed: ${res.exception.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.show()
    }

    // ---- Network places ------------------------------------------------------

    fun showNetworkDialog() {
        NetworkDialog(requireContext(), viewLifecycleOwner.lifecycleScope) { _, root ->
            adapter.clearSelection()
            (activity as? TabHost)?.openTab(root, root.name.ifEmpty { "Network" })
                ?: loadDirectory(root)
        }.show()
    }

    /** Ask the hosting activity for a new tab (keeps tab state in one place). */
    interface TabHost {
        fun openTab(root: FileItem, label: String)
    }

    // ---- Recycle bin ----------------------------------------------------------

    private fun addTabToActivity(root: FileItem, label: String) {
        (activity as? TabHost)?.openTab(root, label) ?: loadDirectory(root)
    }

    private fun trashDir(): LocalFileItem {
        val root = android.os.Environment.getExternalStorageDirectory()
        return LocalFileItem(java.io.File(root, ".TroveTrash"))
    }

    private fun isInTrash(): Boolean =
        currentDirectory?.path?.startsWith(trashDir().path) == true

    private fun moveToTrash(items: List<FileItem>, onDone: () -> Unit) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val trash = withContext(Dispatchers.IO) {
                val f = java.io.File(trashDir().path)
                if (!f.exists()) f.mkdirs()
                LocalFileItem(f)
            }
            var ok = 0
            var failed = 0
            val indexLines = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    var trashName = item.name
                    var n = 1
                    while (trash.findChild(trashName) is Result.Success) {
                        trashName = "${item.name}_$n"
                        n++
                    }
                    val res = item.moveTo(trash, trashName)
                    if (res is Result.Success) {
                        ok++
                        indexLines += "$trashName|${item.path}"
                    } else failed++
                }
                if (indexLines.isNotEmpty()) {
                    val indexFile = java.io.File(java.io.File(trash.path), ".trove_trash_index.txt")
                    indexFile.appendText(indexLines.joinToString("\n", postfix = "\n"))
                }
            }
            onDone()
            Toast.makeText(ctx, "$ok moved to Recycle bin" + if (failed > 0) " · $failed failed" else "", Toast.LENGTH_LONG).show()
        }
    }

    fun openRecycleBin() {
        addTabToActivity(trashDir(), "Recycle bin")
    }

    private fun restoreSelection() {
        val items = selectedList()
        if (items.isEmpty()) return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val index = withContext(Dispatchers.IO) {
                try {
                    java.io.File(java.io.File(trashDir().path), ".trove_trash_index.txt")
                        .readLines().filter { it.contains("|") }
                        .associate { it.substringBefore("|") to it.substringAfter("|") }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    val original = index[item.name]
                    if (original != null) {
                        val parent = java.io.File(original).parentFile
                        if (parent != null && parent.exists()) {
                            val res = item.moveTo(LocalFileItem(parent), item.name)
                            if (res is Result.Success) ok++ else failed++
                        } else failed++
                    } else failed++
                }
            }
            adapter.clearSelection()
            reload()
            Toast.makeText(ctx, "Restored $ok" + if (failed > 0) " · $failed failed" else "", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Search (recursive, bounded) ------------------------------------------

    private var searchResultsMode = false

    fun recursiveSearch(query: String) {
        val ctx = context ?: return
        val root = currentDirectory ?: return
        Toast.makeText(ctx, "Searching recursively…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val found = mutableListOf<FileItem>()
                val queue = ArrayDeque<FileItem>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && found.size < 300 && visited < 2000) {
                    val dir = queue.removeFirst()
                    visited++
                    when (val res = dir.listFiles()) {
                        is Result.Success -> res.value.forEach {
                            if (it.isDirectory) queue.add(it)
                            else if (it.name.contains(query, ignoreCase = true)) found.add(it)
                        }
                        is Result.Failure -> { /* skip unreadable dirs */ }
                    }
                }
                found
            }
            searchResultsMode = true
            searchFilter = null
            adapter.updateItems(results)
            filterBar.visibility = View.VISIBLE
            filterLabel.text = "Search \"$query\": ${results.size} results · tap ✕ to clear"
        }
    }

    // ---- Tools ----------------------------------------------------------------

    fun checksumSelection() {
        val item = selectedList().singleOrNull() ?: return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val hashes = withContext(Dispatchers.IO) {
                suspend fun digest(algorithm: String): String {
                    val md = java.security.MessageDigest.getInstance(algorithm)
                    item.openInputStream().getOrNull()?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    return@digest md.digest().joinToString("") { "%02x".format(it) }
                }
                mapOf("MD5" to digest("MD5"), "SHA-256" to digest("SHA-256"))
            }
            AlertDialog.Builder(ctx)
                .setTitle(item.name)
                .setItems(hashes.map { "${it.key}  ${it.value}" }.toTypedArray(), null)
                .show()
        }
    }

    fun openInTerminal() {
        val ctx = context ?: return
        val dir = currentDirectory?.path ?: return
        val termuxIntent = Intent().apply {
            setClassName("com.termux", "com.termux.app.TermuxActivity")
            action = "com.termux.app.OPEN_NEW_INSTANCE"
            data = android.net.Uri.parse(dir)
        }
        try {
            ctx.startActivity(termuxIntent)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Termux not installed — install it for terminal access", Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeFor(item: FileItem): String {
        val known = mapOf(
            "py" to "text/x-python", "xml" to "text/xml", "txt" to "text/plain",
            "json" to "application/json", "md" to "text/markdown", "sh" to "text/x-sh",
            "html" to "text/html", "htm" to "text/html", "js" to "text/javascript",
            "kt" to "text/plain", "java" to "text/plain", "csv" to "text/csv",
            "log" to "text/plain", "ini" to "text/plain", "yml" to "text/plain",
            "yaml" to "text/plain", "apk" to "application/vnd.android.package-archive"
        )
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val probed = item.mimeType?.takeIf {
            it.isNotBlank() && it != "application/octet-stream"
        }
        return probed ?: known[ext]
            ?: if (ext in setOf("py", "sh", "pl", "rb", "lua", "conf")) "text/plain"
            else "application/octet-stream"
    }

    private fun startTransitionActivity(intent: Intent) {
        val ctx = context ?: return
        val options = android.app.ActivityOptions.makeCustomAnimation(
            ctx,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        startActivity(intent, options.toBundle())
    }

    private fun navigateToIndex(index: Int) {
        val root = rootDirectory ?: return
        val current = currentDirectory ?: return

        // The breadcrumb displays the absolute path, but resolve() navigates
        // relative to the tab root. Map the tapped segment index onto a
        // root-relative path; tapping an ancestor ABOVE the tab root is
        // clamped to the root (tabs are jailed to where they were opened).
        viewLifecycleOwner.lifecycleScope.launch {
            val seg = { p: String -> p.split("/").filter { it.isNotEmpty() } }
            val rootSegs = seg(root.path)
            val absSegs = seg(current.path)

            val targetRelative = when {
                // Path shape unexpected (e.g. exotic SAF roots): fall back to root
                !current.path.startsWith(root.path.trimEnd('/').ifEmpty { "/" }) &&
                    current.path != root.path -> ""
                // Ancestor at or above the tab root -> root itself
                index <= rootSegs.size -> ""
                // Below the root -> root-relative remainder
                else -> absSegs.drop(rootSegs.size).take(index - rootSegs.size).joinToString("/")
            }

            when (val result = root.resolve(targetRelative, jailRoot = root)) {
                is Result.Success -> loadDirectory(result.value)
                is Result.Failure -> Toast.makeText(
                    context,
                    "Resolve failed: ${result.exception.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Show a properties dialog for the given file. If a TroveMetadataEditor
     * supports the format, it reads and displays all available metadata.
     * Otherwise it shows basic file properties (name, size, modified date).
     *
     * This mirrors MiX's long-press → "Properties" action, which dispatches
     * to the Tagger addon's Commands.getMetadata() / Commands.getInfo().
     */
    private fun showFileProperties(item: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val editor = TroveMetadataEditor.forItem(item)
            if (editor != null) {
                when (val result = editor.readMetadata(item)) {
                    is Result.Success -> showMetadataDialog(item, result.value)
                    is Result.Failure -> showBasicProperties(item)
                }
            } else {
                showBasicProperties(item)
            }
        }
    }

    private fun showMetadataDialog(item: FileItem, bundle: MetadataBundle) {
        val ctx = context ?: return
        val sb = StringBuilder()

        sb.appendLine("— ${bundle.source.name} Metadata —")
        sb.appendLine()

        bundle.title?.let { sb.appendLine("Title: $it") }
        if (bundle.artists.isNotEmpty()) {
            sb.appendLine("Artist: ${bundle.artists.joinToString(", ")}")
        }
        bundle.album?.let { sb.appendLine("Album: $it") }
        bundle.albumArtist?.let { sb.appendLine("Album Artist: $it") }
        bundle.genre?.let { sb.appendLine("Genre: $it") }
        bundle.year?.let { sb.appendLine("Year: $it") }
        bundle.trackNumber?.let { num ->
            val total = bundle.trackTotal?.let { "/$it" } ?: ""
            sb.appendLine("Track: $num$total")
        }
        bundle.discNumber?.let { num ->
            val total = bundle.discTotal?.let { "/$it" } ?: ""
            sb.appendLine("Disc: $num$total")
        }
        bundle.composer?.let { sb.appendLine("Composer: $it") }
        bundle.author?.let { sb.appendLine("Author: $it") }
        bundle.subject?.let { sb.appendLine("Subject: $it") }
        if (bundle.keywords.isNotEmpty()) {
            sb.appendLine("Keywords: ${bundle.keywords.joinToString(", ")}")
        }
        bundle.creator?.let { sb.appendLine("Creator: $it") }
        bundle.producer?.let { sb.appendLine("Producer: $it") }
        bundle.comment?.let { sb.appendLine("Comment: $it") }

        // Technical info
        bundle.durationMs?.let {
            val secs = it / 1000
            sb.appendLine("Duration: ${secs / 60}:${"%02d".format(secs % 60)}")
        }
        bundle.bitRate?.let { sb.appendLine("Bit Rate: ${it} kbps") }
        bundle.sampleRate?.let { sb.appendLine("Sample Rate: ${it} Hz") }
        bundle.channels?.let { sb.appendLine("Channels: $it") }
        bundle.encodingType?.let { sb.appendLine("Encoding: $it") }

        if (bundle.width != null && bundle.height != null) {
            sb.appendLine("Dimensions: ${bundle.width} × ${bundle.height}")
        }
        bundle.pageCount?.let { sb.appendLine("Pages: $it") }

        bundle.creationDate?.let { sb.appendLine("Created: $it") }
        bundle.modificationDate?.let { sb.appendLine("Modified: $it") }

        bundle.lyrics?.let {
            sb.appendLine()
            sb.appendLine("— Lyrics —")
            sb.appendLine(it.take(500))
            if (it.length > 500) sb.appendLine("…")
        }

        // Extra tags (format-specific)
        if (bundle.extra.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("— Additional —")
            for ((key, value) in bundle.extra.entries.take(50)) {
                sb.appendLine("$key: $value")
            }
            if (bundle.extra.size > 50) {
                sb.appendLine("… and ${bundle.extra.size - 50} more")
            }
        }

        // Cover art indicator
        bundle.coverArt?.let {
            sb.appendLine()
            sb.appendLine("Cover Art: ${it.size / 1024} KB (${bundle.coverArtMimeType ?: "unknown"})")
        }

        AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBasicProperties(item: FileItem) {
        val ctx = context ?: return
        val sizeKb = item.size / 1024
        val modified = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
        ).format(java.util.Date(item.lastModified))

        val msg = buildString {
            appendLine("Name: ${item.name}")
            appendLine("Path: ${item.path}")
            appendLine("Size: $sizeKb KB (${item.size} bytes)")
            appendLine("Modified: $modified")
            appendLine("Type: ${item.mimeType}")
            appendLine("Directory: ${item.isDirectory}")
        }

        AlertDialog.Builder(ctx)
            .setTitle("Properties")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    fun handleBackPressed(): Boolean {
        val root = rootDirectory ?: return false
        val current = currentDirectory ?: return false
        
        if (current.path == root.path) return false

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = current.getParent()) {
                is Result.Success -> {
                    val parent = result.value
                    if (parent != null) {
                        loadDirectory(parent)
                    }
                }
                is Result.Failure -> {}
            }
        }
        return true
    }
}
