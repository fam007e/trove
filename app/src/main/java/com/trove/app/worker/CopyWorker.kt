package com.trove.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trove.core.fs.FileItem
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Background worker for file copy operations.
 * 
 * PEDAGOGICAL NOTE:
 * We use CoroutineWorker to leverage the suspending methods in our FileItem API.
 * This allows the worker to be canceled gracefully by WorkManager (e.g. if the 
 * system needs resources) and keeps the network/disk IO off the main thread.
 */
class CopyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val srcPath = inputData.getString("src_path") ?: return@withContext Result.failure()
        val destPath = inputData.getString("dest_path") ?: return@withContext Result.failure()
        
        // In a real app, we would have a FileItem provider to resolve these paths.
        // For this pedagogical example, we'll assume LocalFileItem as the default.
        val srcFile = LocalFileItem(File(srcPath))
        val destDir = LocalFileItem(File(destPath))

        if (!srcFile.exists()) return@withContext Result.failure(workDataOf("error" to "Source not found"))

        try {
            copyRecursive(srcFile, destDir)
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }

    private suspend fun copyRecursive(src: FileItem, destDir: FileItem) {
        if (isStopped) throw InterruptedException("Worker stopped")

        if (src.isDirectory) {
            val newDirResult = destDir.createDirectory(src.name)
            if (newDirResult is com.trove.core.fs.Result.Success) {
                val childrenResult = src.listFiles()
                if (childrenResult is com.trove.core.fs.Result.Success) {
                    for (child in childrenResult.value) {
                        copyRecursive(child, newDirResult.value)
                    }
                }
            }
        } else {
            // Perform the copy via the FileItem API
            val copyResult = src.copyTo(destDir)
            if (copyResult is com.trove.core.fs.Result.Failure) {
                throw copyResult.exception
            }
            
            // Progress update (simplified)
            setProgress(workDataOf("current_file" to src.name))
        }
    }
}
