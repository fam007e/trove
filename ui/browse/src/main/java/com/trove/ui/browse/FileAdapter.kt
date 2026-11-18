package com.trove.ui.browse

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trove.core.fs.FileItem
import com.trove.ui.widgets.FileRowView

class FileAdapter(
    private var items: List<FileItem> = emptyList(),
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: ((FileItem) -> Boolean)? = null
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

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
        holder.view.setOnClickListener { onItemClick(item) }
        holder.view.setOnLongClickListener {
            onItemLongClick?.invoke(item) ?: false
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<FileItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
