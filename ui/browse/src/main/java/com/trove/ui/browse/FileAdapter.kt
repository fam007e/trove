package com.trove.ui.browse

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
import com.trove.feature.image.ImageDecoderUtils
import com.trove.feature.media.MediaHelper
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
        val isThumbnable = !item.isDirectory &&
            (ImageDecoderUtils.isImage(item.name) || MediaHelper.isVideo(item.name))
        val cached = if (isThumbnable) ThumbnailCache.get(item) else null
        holder.view.thumbnail = cached
        if (isThumbnable && cached == null) {
            scope.launch {
                val bmp = ThumbnailCache.load(
                    item,
                    isVideo = MediaHelper.isVideo(item.name)
                ) ?: return@launch
                val current = items.getOrNull(position)
                if (current?.path == item.path && holder.view.item?.path == item.path) {
                    holder.view.thumbnail = bmp
                }
            }
        }
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
