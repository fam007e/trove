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

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var breadcrumbBar: TroveBreadcrumbBar
    private var currentDirectory: FileItem? = null
    private var rootDirectory: FileItem? = null

    companion object {
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
                    Toast.makeText(context, "File: ${item.name}", Toast.LENGTH_SHORT).show()
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
                    android.util.Log.v(
                        "TroveList",
                        "${directory.path}: ${result.value.size} entries, " +
                            "${result.value.count { it.isDirectory }} dirs, " +
                            "${result.value.count { !it.isDirectory }} files"
                    )
                    adapter.updateItems(result.value)
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
