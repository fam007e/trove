package com.trove.ui.browse

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
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
    private var currentDirectory: FileItem? = null
    private var rootDirectory: FileItem? = null

    companion object {
        // App-wide list settings (persisted in a later pass via SharedPreferences)
        var sortBy: String = "name"      // "name" | "size" | "date"
        var showHidden: Boolean = false

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
                if (item.isDirectory) {
                    loadDirectory(item)
                } else if (ArchiveType.isArchive(item)) {
                    openArchive(item)
                } else if (ImageDecoderUtils.isImage(item.name)) {
                    openImageViewer(item)
                } else if (TrovePdfDocument.isPdf(item.name)) {
                    openPdfViewer(item)
                } else if (MediaHelper.isMedia(item.name)) {
                    openMediaPlayer(item)
                } else {
                    openWithExternalApp(item)
                }
            },
            onItemLongClick = { item ->
                showFileProperties(item)
                true
            }
        )
        recyclerView.adapter = adapter
        rootLayout.addView(recyclerView)

        return rootLayout
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
        val visible = if (showHidden) list else list.filter { !it.name.startsWith(".") }
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

    private fun reload() {
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
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
