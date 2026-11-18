package com.trove.feature.media

import android.net.Uri
import androidx.media3.datasource.DataSource
import com.trove.core.fs.FileItem

/**
 * Factory for creating [FileItemDataSource] instances.
 */
class FileItemDataSourceFactory(
    private val itemProvider: (Uri) -> FileItem? = { TroveMediaRegistry.get(it) }
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return FileItemDataSource(itemProvider)
    }
}
