package com.trove.ui.browse

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
import com.trove.feature.image.ImageDecoderUtils
import com.trove.feature.media.MediaHelper
import com.trove.feature.pdf.TrovePdfDocument
import com.trove.ui.widgets.FileRowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FileAdapter with selection tracking, row binding, async thumbnails and
 * async folder info (size / item count / dominant content badge).
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

    /** Session cache of folder info (path -> item count / dominant content type). */
    private val folderInfos = mutableMapOf<String, FolderInfo>()

    /** Currently selected items, in selection order. */
    val selectedItems: Set<FileItem> get() = selection.selectedItems

    /** Fired whenever the selection changes (count passed). */
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** Grid rendering mode (mirrors Prefs.viewType; set by the fragment). */
    var gridMode: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                notifyDataSetChanged()
            }
        }

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
        // TalkBack: announce name, type and size — custom-drawn rows have no
        // child views for accessibility to read.
        holder.view.contentDescription = buildString {
            append(item.name)
            append(if (item.isDirectory) ", folder" else ", file")
            append(", ${android.text.format.Formatter.formatFileSize(holder.view.context, item.size)}")
        }
        holder.view.isItemSelected = selectedItems.contains(item)
        holder.view.setOnClickListener { onItemClick(item) }
        holder.view.setOnLongClickListener {
            onItemLongClick?.invoke(item) ?: false
        }

        // Async thumbnail: cached hit binds immediately; otherwise decode on
        // IO and rebind only if this row still shows the same item.
        val isPdf = TrovePdfDocument.isPdf(item.name)
        val isApk = item.name.substringAfterLast('.', "").lowercase() == "apk"
        val isThumbnable = !item.isDirectory &&
            (ImageDecoderUtils.isImage(item.name) || MediaHelper.isVideo(item.name) || isPdf || isApk)
        val cached = if (isThumbnable) ThumbnailCache.get(item) else null
        holder.view.thumbnail = cached
        if (isThumbnable && cached == null) {
            scope.launch {
                val bmp = ThumbnailCache.load(
                    item,
                    isVideo = MediaHelper.isVideo(item.name),
                    isPdf = isPdf,
                    isApk = isApk
                ) ?: return@launch
                val current = items.getOrNull(position)
                if (current?.path == item.path && holder.view.item?.path == item.path) {
                    holder.view.thumbnail = bmp
                }
            }
        }

        // Async folder info: MiX-style lazy computation. Placeholders fill in
        // as walks complete; results are cached for the session.
        if (item.isDirectory) {
            holder.view.displaySize = dirSizes[item.path]
            folderInfos[item.path]?.let {
                holder.view.itemCount = it.itemCount
                holder.view.contentBadge = it.dominantType
            }
            if (!dirSizes.containsKey(item.path) && item is com.trove.core.fs.LocalFileItem) {
                scope.launch {
                    val info = computeDirectoryInfo(java.io.File(item.path))
                    dirSizes[item.path] = info.first
                    folderInfos[item.path] = info.second
                    val current = items.getOrNull(position)
                    if (current?.path == item.path && holder.view.item?.path == item.path) {
                        holder.view.displaySize = info.first
                        holder.view.itemCount = info.second.itemCount
                        holder.view.contentBadge = info.second.dominantType
                    }
                }
            }
        } else {
            holder.view.displaySize = null
        }
        holder.view.gridMode = gridMode
    }

    /** (size, count + dominant content type) in one symlink-safe walk. */
    private suspend fun computeDirectoryInfo(root: java.io.File): Pair<Long, FolderInfo> =
        withContext(Dispatchers.IO) {
            var total = 0L
            var count = 0
            val categories = mutableMapOf<String, Int>()
            try {
                java.nio.file.Files.walkFileTree(
                    root.toPath(),
                    object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun visitFile(
                            file: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes
                        ): java.nio.file.FileVisitResult {
                            if (attrs.isRegularFile) {
                                total += attrs.size()
                                count++
                                val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
                                val cat = when (ext) {
                                    in setOf("mp3", "flac", "ogg", "wav", "m4a", "aac", "opus") -> "audio"
                                    in setOf("mp4", "mkv", "avi", "webm", "mov", "3gp") -> "video"
                                    in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> "image"
                                    in setOf("pdf", "doc", "docx", "txt", "md", "epub", "mobi") -> "doc"
                                    in setOf("apk", "apks", "xapk") -> "apk"
                                    in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz") -> "archive"
                                    else -> null
                                }
                                if (cat != null) categories[cat] = (categories[cat] ?: 0) + 1
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: java.nio.file.Path,
                            exc: java.io.IOException?
                        ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                    }
                )
            } catch (_: Exception) {
                // Partial/failed walk: report what was counted, like MiX does.
            }
            total to FolderInfo(count, categories.maxByOrNull { it.value }?.key)
        }

    override fun getItemCount(): Int = items.size

    fun toggleSelection(item: FileItem) {
        val index = items.indexOf(item)
        if (index != -1) {
            selection.toggle(item)
            notifyItemChanged(index)
            onSelectionChanged?.invoke(selectedItems.size)
        }
    }

    fun selectAll() {
        selection.selectAll(items)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun invertSelection() {
        selection.invert(items)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun selectMatching(predicate: (FileItem) -> Boolean): Int {
        val count = selection.selectMatching(items, predicate)
        if (count > 0) {
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedItems.size)
        }
        return count
    }

    fun clearSelection() {
        if (selection.clear() > 0) {
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedItems.size)
        }
    }

    fun updateItems(newItems: List<FileItem>) {
        items = newItems
        selection.clear()
        notifyDataSetChanged()
    }

    data class FolderInfo(val itemCount: Int, val dominantType: String?)
}
