package com.trove.ui.browse

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        fun onDirectoryChanged(directory: FileItem) {}
        fun onDirectoryChanged(paneIndex: Int, directory: FileItem) {
            onDirectoryChanged(directory)
        }
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
    private lateinit var bottomBar: LinearLayout
    private lateinit var filterBar: LinearLayout
    private lateinit var filterLabel: TextView
    private var currentDirectory: FileItem? = null
    private var rootDirectory: FileItem? = null
    private var searchFilter: String? = null

    var paneIndex: Int = 0
        private set

    val activeDirectory: FileItem?
        get() = currentDirectory ?: rootDirectory

    companion object {
        // Backed by Prefs (persisted); kept as accessors so existing
        // call sites (BrowseActivity menu) keep working.
        var sortBy: String
            get() = Prefs.sortBy
            set(v) { Prefs.sortBy = v }
        var showHidden: Boolean
            get() = Prefs.showHidden
            set(v) { Prefs.showHidden = v }
        var sortAscending: Boolean
            get() = Prefs.sortAscending
            set(v) { Prefs.sortAscending = v }

        fun newInstance(root: FileItem, paneIndex: Int = 0): BrowseFragment {
            val fragment = BrowseFragment()
            fragment.rootDirectory = root
            fragment.paneIndex = paneIndex
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
        ThumbnailCache.initContext(context)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
        }

        // Breadcrumb row: ◁ go-up (MiX's breadcrumb_back) + breadcrumb + disk usage
        val breadcrumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbBar = TroveBreadcrumbBar(context).apply {
            setOnSegmentClickListener { index ->
                navigateToIndex(index)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        breadcrumbRow.addView(breadcrumbBar)
        // Disk-usage chip: a tiny rounded usage bar + rounded percentage.
        // The old text form ("13.36 GiB / 52.55 GiB  25%") was long, noisy
        // and fought the breadcrumb for width; the full figures moved to a
        // tap toast. Pill background keeps it reading as its own element.
        diskUsageChip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#3B4252"))
                cornerRadius = 999f
            }
            setPadding(14, 7, 14, 7)
            visibility = View.GONE
        }
        val usageTrack = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#4C566A"))
            cornerRadius = 99f
        }
        val usageFill = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#88C0D0"))
            cornerRadius = 99f
        }
        val usageLayers = android.graphics.drawable.LayerDrawable(
            arrayOf(usageTrack, usageFill)
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(1, 1, 1, 1, 1)
        }
        diskUsageBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = usageLayers
            layoutParams = LinearLayout.LayoutParams(
                (52 * resources.displayMetrics.density).toInt(),
                (7 * resources.displayMetrics.density).toInt()
            )
        }
        diskUsageChip.addView(diskUsageBar)
        diskUsagePct = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
            textSize = 11f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (7 * resources.displayMetrics.density).toInt() }
        }
        diskUsageChip.addView(diskUsagePct)
        diskUsageChip.setOnClickListener {
            diskUsageDetail?.let { detail ->
                Toast.makeText(context, detail, Toast.LENGTH_SHORT).show()
            }
        }
        breadcrumbRow.addView(diskUsageChip)
        rootLayout.addView(breadcrumbRow)

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
        // TextView, not Button: the Button style's 88dp minimum width bloats
        // a small clear glyph into a chunky slot.
        filterBar.addView(TextView(context).apply {
            text = "✕"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#BF616A"))
            textSize = 14f
            setPadding(24, 8, 24, 8)
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
            textSize = 13f
            setOnClickListener { onClick() }
        }
        // Horizontal scroll keeps every action reachable on narrow screens
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        // Text labels (not glyphs): discoverability + accessibility
        actions.addView(barButton("Delete") { deleteSelection() })
        actions.addView(barButton("Copy") { copyOrMoveSelection(move = false) })
        actions.addView(barButton("Move") { copyOrMoveSelection(move = true) })
        actions.addView(barButton("Extract") { extractSelection() })
        actions.addView(barButton("Archive") { archiveSelection() })
        actions.addView(barButton("Share") { shareSelection() })
        actions.addView(barButton("Rename") { renameSelection() })
        actions.addView(barButton("Info") { propertiesSelection() })
        actions.addView(barButton("Select all") { adapter.selectAll() })
        actions.addView(barButton("Invert") { adapter.invertSelection() })
        actions.addView(barButton("Clear") { adapter.clearSelection() })
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
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_DOWN) {
                        (activity as? DualPaneHost)?.notifyPaneActive(paneIndex)
                    }
                    return false
                }
            })
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
                } else if (item.name.substringAfterLast('.', "").lowercase() == "apk") {
                    openApkDialog(item)
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

        // MiX-style bottom action bar: Search · View · Add · Refresh · Sort · Select all
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
        }
        fun barAction(label: String, onClick: (View) -> Unit): Button = Button(context).apply {
            text = label
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
            setMinimumWidth(0)
            minimumWidth = 0
            setPadding(0, 12, 0, 12)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { onClick(this) }
        }
        bottomBar.addView(barAction("🔍") { showSearchDialog() })
        bottomBar.addView(barAction(if (Prefs.viewType == "grid") "▤" else "▦") { toggleViewType() })
        bottomBar.addView(barAction("＋") { showCreateMenu(it) })
        bottomBar.addView(barAction("⟳") { reload() })
        bottomBar.addView(barAction(if (Prefs.sortAscending) "AZ" else "ZA") { showSortMenu(it) })
        bottomBar.addView(barAction("☑") { adapter.selectAll() })
        rootLayout.addView(bottomBar)

        return rootLayout
    }

    private lateinit var diskUsageChip: LinearLayout
    private lateinit var diskUsageBar: ProgressBar
    private lateinit var diskUsagePct: TextView

    /** Full figures for the chip's tap toast ("13.4 / 52.6 GiB · 39.2 free"). */
    private var diskUsageDetail: String? = null

    /** Disk usage for the current volume, compact chip in the breadcrumb row. */
    private fun updateDiskUsage(directory: FileItem) {
        if (directory !is LocalFileItem) {
            diskUsageChip.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Some FUSE mounts report 0 space on deep paths; probe
                    // upwards until a volume answers, mirroring MiX.
                    var probe: java.io.File? = java.io.File(directory.path)
                    var total = 0L
                    var usable = 0L
                    var hops = 0
                    while (probe != null && hops < 6) {
                        total = probe.totalSpace
                        usable = probe.usableSpace
                        if (total > 0) break
                        probe = probe.parentFile
                        hops++
                    }
                    if (total <= 0) return@withContext null
                    val used = total - usable
                    val usedPct = Math.round(used * 100.0 / total).toInt()
                    Triple(usedPct, used, total)
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                val (usedPct, used, total) = result
                diskUsageBar.progress = usedPct.coerceIn(0, 100)
                diskUsagePct.text = "$usedPct%"
                diskUsageDetail = String.format(
                    "%.1f / %.1f GiB used · %.1f GiB free",
                    used / 1024.0 / 1024.0 / 1024.0,
                    total / 1024.0 / 1024.0 / 1024.0,
                    (total - used) / 1024.0 / 1024.0 / 1024.0
                )
                diskUsageChip.visibility = View.VISIBLE
            } else {
                diskUsageChip.visibility = View.GONE
            }
        }
    }


    /** Toggle list/grid rendering; persists via Prefs.viewType. */
    private fun toggleViewType() {
        Prefs.viewType = if (Prefs.viewType == "grid") "list" else "grid"
        applyViewType()
    }

    private fun applyViewType() {
        adapter.gridMode = Prefs.viewType == "grid"
        recyclerView.layoutManager = if (Prefs.viewType == "grid") {
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
        reload()
    }

    private fun updateSelectionBar(count: Int) {
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        // Hide the bottom action bar (Search/Sort/…) while selecting — MiX does
        // the same; having Search visible mid-selection is confusing.
        bottomBar.visibility = if (count > 0) View.GONE else View.VISIBLE
        if (count > 0) {
            val totalBytes = selectedList().sumOf { it.size }
            val formattedSize = android.text.format.Formatter.formatFileSize(context, totalBytes)
            selectionCountView.text = "$count selected ($formattedSize)"
        } else {
            selectionCountView.text = ""
        }
    }

    private fun showSelectByPatternDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = "*.pdf, *.jpg, img_*"
            setSingleLine(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Select by pattern")
            .setMessage("Enter wildcard pattern (* matches anything, ? matches one char):")
            .setView(input)
            .setPositiveButton("Select") { _, _ ->
                val pattern = input.text.toString().trim()
                if (pattern.isNotEmpty()) {
                    val regexPattern = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                    val regex = Regex("^$regexPattern$", RegexOption.IGNORE_CASE)
                    val count = adapter.selectMatching { regex.matches(it.name) }
                    Toast.makeText(ctx, "Selected $count item(s)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        adapter.gridMode = Prefs.viewType == "grid"
        recyclerView.layoutManager = if (Prefs.viewType == "grid") {
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
        rootDirectory?.let { loadDirectory(it) }
    }

    private fun loadDirectory(directory: FileItem) {
        currentDirectory = directory
        breadcrumbBar.setPath(directory.path)
        updateDiskUsage(directory)
        (activity as? OnDirectoryChangedListener)?.onDirectoryChanged(paneIndex, directory)
        (activity as? DualPaneHost)?.notifyPaneActive(paneIndex)
        
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

    /** Dirs first, then the chosen key + direction; hidden files filtered by the toggle. */
    private fun applyListSettings(list: List<FileItem>): List<FileItem> {
        var visible = if (Prefs.showHidden) list else list.filter { !it.name.startsWith(".") }
        searchFilter?.let { q ->
            visible = visible.filter { it.name.contains(q, ignoreCase = true) }
        }
        val (dirs, files) = visible.partition { it.isDirectory }
        val cmp: Comparator<FileItem> = when (sortBy) {
            "size" -> compareBy { it.size }
            "date" -> compareBy { it.lastModified }
            "type" -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            else -> compareBy { it.name.lowercase() }
        }
        val sorted = if (Prefs.sortAscending) files.sortedWith(cmp) else files.sortedWith(cmp.reversed())
        val sortedDirs = if (Prefs.sortAscending) dirs.sortedWith(cmp) else dirs.sortedWith(cmp.reversed())
        return sortedDirs + sorted
    }

    /** Entry point for the activity's overflow menu. */
    fun onMenuAction(action: MenuAction) {
        when (action) {
            MenuAction.SORT_NAME -> reload()  // Value already set by BrowseActivity
            MenuAction.SORT_SIZE -> reload()
            MenuAction.SORT_DATE -> reload()
            MenuAction.TOGGLE_HIDDEN -> reload()  // Value already toggled by BrowseActivity
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
        (currentDirectory ?: rootDirectory)?.let { loadDirectory(it) }
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

    /** MiX's ＋ menu: create a folder or an empty file in the current directory. */
    private fun showCreateMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "New folder")
        popup.menu.add(0, 2, 0, "New file")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showNewFolderDialog()
                2 -> showNewFileDialog()
            }
            true
        }
        popup.show()
    }

    private fun showNewFileDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            hint = "File name (e.g. notes.txt)"
        }
        AlertDialog.Builder(ctx)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val dir = currentDirectory ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val res = dir.createFile(name)) {
                        is Result.Success -> {
                            Toast.makeText(ctx, "Created $name", Toast.LENGTH_SHORT).show()
                            reload()
                        }
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
                    performPermanentDelete(items)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val preview = items.take(4).joinToString("\n") { it.name } +
            if (items.size > 4) "\n… (+${items.size - 4} more)" else ""

        AlertDialog.Builder(ctx)
            .setTitle("Delete ${items.size} item(s)")
            .setMessage(preview)
            .setPositiveButton("Recycle Bin") { _, _ ->
                moveToTrash(items) {
                    adapter.clearSelection()
                    reload()
                }
            }
            .setNeutralButton("Delete Permanently") { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle("Permanent Deletion")
                    .setMessage("Permanently delete these ${items.size} item(s)? This cannot be undone.")
                    .setPositiveButton("Delete Forever") { _, _ ->
                        performPermanentDelete(items)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performPermanentDelete(items: List<FileItem>) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = 0
            withContext(Dispatchers.IO) {
                items.forEach { if (it.delete() is Result.Failure) failed++ }
            }
            adapter.clearSelection()
            reload()
            if (failed > 0) {
                Toast.makeText(ctx, "$failed could not be deleted", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "Deleted ${items.size} item(s) permanently", Toast.LENGTH_SHORT).show()
            }
        }
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

        val dualHost = activity as? DualPaneHost
        val oppositeDir = if (dualHost?.isDualPaneActive == true) dualHost.getOppositePaneDirectory(paneIndex) else null

        if (oppositeDir != null) {
            val oppName = oppositeDir.name.ifEmpty { oppositeDir.path }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(if (move) "Move files" else "Copy files")
                .setMessage("Select destination for ${items.size} file(s):")
                .setPositiveButton("Other pane ($oppName)") { _, _ ->
                    executeTransfer(items, oppositeDir, move) {
                        dualHost?.reloadOppositePane(paneIndex)
                    }
                }
                .setNeutralButton("Choose folder…") { _, _ ->
                    DestinationPickerDialog(ctx, viewLifecycleOwner.lifecycleScope) { destDir ->
                        executeTransfer(items, destDir, move)
                    }.show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        DestinationPickerDialog(ctx, viewLifecycleOwner.lifecycleScope) { destDir ->
            executeTransfer(items, destDir, move)
        }.show()
    }

    private fun executeTransfer(
        items: List<FileItem>,
        destDir: FileItem,
        move: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        val ctx = context ?: return
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
            onComplete?.invoke()
            val verb = if (move) "Moved" else "Copied"
            Toast.makeText(
                ctx,
                "$verb $ok to ${destDir.path}" + if (failed > 0) " · $failed failed" else "",
                Toast.LENGTH_LONG
            ).show()
        }
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
                val chosen = bookmarks[which]
                val name = chosen.substringBefore('|')
                val path = chosen.substringAfter('|')
                val target = com.trove.core.fs.LocalFileItem(java.io.File(path))

                AlertDialog.Builder(ctx)
                    .setTitle(name)
                    .setMessage(path)
                    .setPositiveButton("Open") { _, _ -> loadDirectory(target) }
                    .setNeutralButton("Open in new tab") { _, _ ->
                        (activity as? DualPaneHost)?.openTab(paneIndex, target, name)
                            ?: (activity as? TabHost)?.openTab(target, name)
                            ?: loadDirectory(target)
                    }
                    .setNegativeButton("Delete") { _, _ ->
                        Prefs.removeBookmark(name, path)
                        Toast.makeText(ctx, "Removed bookmark \"$name\"", Toast.LENGTH_SHORT).show()
                    }
                    .show()
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
            (activity as? DualPaneHost)?.openTab(paneIndex, root, root.name.ifEmpty { "Network" })
                ?: (activity as? TabHost)?.openTab(root, root.name.ifEmpty { "Network" })
                ?: loadDirectory(root)
        }.show()
    }

    /** Ask the hosting activity for a new tab (keeps tab state in one place). */
    interface TabHost {
        fun openTab(root: FileItem, label: String)
    }

    // ---- Recycle bin ----------------------------------------------------------

    private fun addTabToActivity(root: FileItem, label: String) {
        (activity as? DualPaneHost)?.openTab(paneIndex, root, label)
            ?: (activity as? TabHost)?.openTab(root, label)
            ?: loadDirectory(root)
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

    /**
     * Opens the current directory in Termux via the official RUN_COMMAND API
     * (Termux's RunCommandService): workdir = this folder, a login bash
     * session is opened in the terminal.
     *
     * Two gates, both needed:
     * 1. com.termux.permission.RUN_COMMAND is a runtime ("dangerous")
     *    permission — declaring it in the manifest is NOT enough; startService
     *    throws SecurityException without the grant (that was the "Termux
     *    refused the request" report).
     * 2. Termux-side policy: allow-external-apps=true in
     *    ~/.termux/termux.properties. Termux 0.119 has NO Settings toggle for
     *    it — the property file is the only mechanism.
     */
    private var pendingTerminalDir: String? = null

    private val runCommandPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startTermuxSession()
        } else {
            AlertDialog.Builder(context)
                .setTitle("Termux Run command permission denied")
                .setMessage(
                    "Trove needs Termux's \"Run commands\" permission to open " +
                        "folders in Termux. Grant it under Android Settings → Apps → " +
                        "Trove → Permissions, then try again."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    fun openInTerminal() {
        val ctx = context ?: return
        val dir = currentDirectory?.path ?: return

        val installed = try {
            ctx.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!installed) {
            AlertDialog.Builder(ctx)
                .setTitle("Termux not installed")
                .setMessage("Install Termux (F-Droid build recommended) to open folders in a real terminal.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        pendingTerminalDir = dir
        runCommandPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
    }

    private fun startTermuxSession() {
        val ctx = context ?: return
        val dir = pendingTerminalDir ?: return
        val runCommand = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("--login"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", dir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }
        try {
            ctx.startService(runCommand)
            Toast.makeText(ctx, "Termux opened at $dir", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            AlertDialog.Builder(ctx)
                .setTitle("Termux refused the request")
                .setMessage(
                    "Termux's RunCommandService rejected the start. Check both gates:\n\n" +
                        "1. Permission: grant Termux's \"Run commands\" to Trove " +
                        "(Android Settings → Apps → Trove → Permissions), or just retry.\n\n" +
                        "2. Termux policy: run this inside Termux, then force-stop Termux once:\n" +
                        "echo allow-external-apps=true >> ~/.termux/termux.properties\n\n" +
                        "(Termux 0.119 has no Settings toggle for this — the property file is the only switch.)"
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** MiX-style sort menu: key + direction as separate entries. */
    fun showSortMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        val entries = listOf(
            "Name (A - Z)" to ("name" to true),
            "Name (Z - A)" to ("name" to false),
            "Size (Smaller)" to ("size" to true),
            "Size (Larger)" to ("size" to false),
            "Date (Older)" to ("date" to true),
            "Date (Newer)" to ("date" to false),
            "Type (Ascending)" to ("type" to true),
            "Type (Descending)" to ("type" to false)
        )
        entries.forEachIndexed { i, (label, spec) ->
            val mi = popup.menu.add(0, i, 0, label)
            mi.isChecked = Prefs.sortBy == spec.first && Prefs.sortAscending == spec.second
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { mi ->
            val spec = entries[mi.itemId].second
            Prefs.sortBy = spec.first
            Prefs.sortAscending = spec.second
            reload()
            true
        }
        popup.show()
    }

    /** APK tap: offer install (via system installer through FileProvider) or properties. */
    private fun openApkDialog(item: FileItem) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage("Install this APK with the system installer?")
            .setPositiveButton("Install") { _, _ -> openWithExternalApp(item) }
            .setNeutralButton("Properties") { _, _ -> showFileProperties(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Selection action: extract selected archive(s) to a chosen folder. */
    private fun extractSelection() {
        val archives = selectedList().filter { ArchiveType.isArchive(it) }
        if (archives.isEmpty()) {
            Toast.makeText(context, "Select archive file(s) first", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context ?: return
        DestinationPickerDialog(ctx, viewLifecycleOwner.lifecycleScope) { destDir ->
            viewLifecycleOwner.lifecycleScope.launch {
                var ok = 0
                var failed = 0
                archives.forEach { archive ->
                    when (val opened = ArchiveFileItem.open(archive)) {
                        is Result.Success -> {
                            when (val res = com.trove.feature.archive.ArchiveExtractor.extractAll(opened.value, destDir)) {
                                is Result.Success -> ok++
                                is Result.Failure -> failed++
                            }
                        }
                        is Result.Failure -> failed++
                    }
                }
                adapter.clearSelection()
                Toast.makeText(
                    ctx,
                    "Extracted $ok archive(s) to ${destDir.path}" + if (failed > 0) " · $failed failed" else "",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.show()
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
        val probed = item.mimeType.takeIf {
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

        // File identity first: MiX's Properties shows where the file lives,
        // and metadata alone gives no hint of the location.
        sb.appendLine("Location: ${item.path}")
        sb.appendLine("Size: ${android.text.format.Formatter.formatFileSize(ctx, item.size)}")
        sb.appendLine()
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
        // MiX behavior: back first clears an active selection
        if (adapter.selectedItems.isNotEmpty()) {
            adapter.clearSelection()
            return true
        }
        val root = rootDirectory ?: return false
        val current = currentDirectory ?: return false

        // At (or above) the tab root: let the system back minimize the app.
        // Never pop above the jail — that produced "Failed to list" toasts.
        val rootNorm = root.path.trimEnd('/').ifEmpty { "/" }
        val curNorm = current.path.trimEnd('/').ifEmpty { "/" }
        if (curNorm == rootNorm || !curNorm.startsWith(rootNorm)) return false

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = current.getParent()) {
                is Result.Success -> {
                    val parent = result.value
                    if (parent != null) {
                        val parentNorm = parent.path.trimEnd('/').ifEmpty { "/" }
                        if (parentNorm == rootNorm || parentNorm.startsWith("$rootNorm/")) {
                            loadDirectory(parent)
                        } else {
                            loadDirectory(root)
                        }
                    }
                }
                is Result.Failure -> {}
            }
        }
        return true
    }
}
