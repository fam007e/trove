package com.trove.ui.browse

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
import com.trove.feature.image.ImageDecoderUtils
import com.trove.feature.media.MediaHelper
import com.trove.feature.pdf.TrovePdfDocument
import com.trove.ui.widgets.FileRowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * FileAdapter with selection tracking, row binding and async thumbnails.
 */
class FileAdapter(
    private var items: List<FileItem> = emptyList(),
    private val scope: CoroutineScope,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: ((FileItem) -> Boolean)? = null
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val selection = SelectionTracker<FileItem>()

    /** Session cache of computed directory sizes (path -> bytes). */
    private val dirSizes = mutableMapOf<String, Long>()

    /** Currently selected items, in selection order. */
    val selectedItems: Set<FileItem> get() = selection.selectedItems

    class ViewHolder(val view: FileRowView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = FileRowView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.view.item = item
        holder.view.isItemSelected = selectedItems.contains(item)
        holder.view.setOnClickListener { onItemClick(item) }
        holder.view.setOnLongClickListener {
            onItemLongClick?.invoke(item) ?: false
        }

        // Async thumbnail: cached hit binds immediately; otherwise decode on
        // IO and rebind only if this row still shows the same item.
        val isPdf = TrovePdfDocument.isPdf(item.name)
        val isThumbnable = !item.isDirectory &&
            (ImageDecoderUtils.isImage(item.name) || MediaHelper.isVideo(item.name) || isPdf)
        val cached = if (isThumbnable) ThumbnailCache.get(item) else null
        holder.view.thumbnail = cached
        if (isThumbnable && cached == null) {
            scope.launch {
                val bmp = ThumbnailCache.load(
                    item,
                    isVideo = MediaHelper.isVideo(item.name),
                    isPdf = isPdf
                ) ?: return@launch
                val current = items.getOrNull(position)
                if (current?.path == item.path && holder.view.item?.path == item.path) {
                    holder.view.thumbnail = bmp
                }
            }
        }

        // Async folder sizes: MiX-style lazy computation. 0 B placeholders
        // fill in as walks complete; results are cached for the session.
        if (item.isDirectory) {
            holder.view.displaySize = dirSizes[item.path]
            if (!dirSizes.containsKey(item.path) && item is com.trove.core.fs.LocalFileItem) {
                scope.launch {
                    val size = computeDirectorySize(java.io.File(item.path))
                    dirSizes[item.path] = size
                    val current = items.getOrNull(position)
                    if (current?.path == item.path && holder.view.item?.path == item.path) {
                        holder.view.displaySize = size
                    }
                }
            }
        } else {
            holder.view.displaySize = null
        }
    }

    /**
     * Recursive byte count without following symlinks (prevents cycle
     * loops on Android's FUSE view). Session-cached by the adapter.
     */
    private fun computeDirectorySize(root: java.io.File): Long {
        var total = 0L
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult =
                        java.nio.file.FileVisitResult.CONTINUE
                }
            )
        } catch (_: Exception) {
            // Partial/failed walk: report what was counted, like MiX does.
        }
        return total
    }

    override fun getItemCount(): Int = items.size

    fun toggleSelection(item: FileItem) {
        val index = items.indexOf(item)
        if (index != -1) {
            selection.toggle(item)
            notifyItemChanged(index)
        }
    }

    fun clearSelection() {
        if (selection.clear() > 0) {
            notifyDataSetChanged()
        }
    }

    fun updateItems(newItems: List<FileItem>) {
        items = newItems
        selection.clear()
        notifyDataSetChanged()
    }
}
