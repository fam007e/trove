package com.trove.feature.image

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.trove.core.fs.LocalFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TroveImageViewerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var overlayTitle: TextView
    private val imagePaths = mutableListOf<String>()

    private val slideshowHandler = Handler(Looper.getMainLooper())
    private var isSlideshowRunning = false
    private val SLIDESHOW_INTERVAL_MS = 3200L

    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (isSlideshowRunning && imagePaths.isNotEmpty()) {
                val next = (viewPager.currentItem + 1) % imagePaths.size
                viewPager.setCurrentItem(next, true)
                slideshowHandler.postDelayed(this, SLIDESHOW_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val paths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS)
        if (!paths.isNullOrEmpty()) {
            imagePaths.addAll(paths)
        }
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        viewPager = ViewPager2(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            adapter = ImagePagerAdapter()
        }
        rootLayout.addView(viewPager)

        overlayTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#80000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        rootLayout.addView(overlayTitle)

        setContentView(rootLayout)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateOverlay(position)
            }
        })

        if (imagePaths.isNotEmpty()) {
            viewPager.setCurrentItem(startIndex.coerceIn(0, imagePaths.size - 1), false)
            updateOverlay(viewPager.currentItem)
        }
    }

    fun toggleSlideshow() {
        isSlideshowRunning = !isSlideshowRunning
        if (isSlideshowRunning) {
            slideshowHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL_MS)
        } else {
            slideshowHandler.removeCallbacks(slideshowRunnable)
        }
    }

    override fun onDestroy() {
        slideshowHandler.removeCallbacks(slideshowRunnable)
        super.onDestroy()
    }

    private fun updateOverlay(position: Int) {
        if (position in imagePaths.indices) {
            val file = File(imagePaths[position])
            overlayTitle.text = "${position + 1} / ${imagePaths.size}  •  ${file.name}"
        }
    }

    private inner class ImagePagerAdapter : RecyclerView.Adapter<ImageViewHolder>() {
        override fun getItemCount(): Int = imagePaths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return ImageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            holder.bind(imagePaths[position])
        }

        override fun onViewRecycled(holder: ImageViewHolder) {
            holder.recycle()
        }
    }

    private inner class ImageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView) {
        private var loadedBitmap: android.graphics.Bitmap? = null

        fun bind(path: String) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val fileItem = LocalFileItem(File(path))
                    val inResult = fileItem.openInputStream()
                    if (inResult is com.trove.core.fs.Result.Success) {
                        inResult.value.use { stream ->
                            val decoded = ImageDecoderUtils.decodeSampledBitmap(
                                inputStream = stream,
                                reqWidth = 1920,
                                reqHeight = 1080,
                                pool = TroveBitmapPool.shared
                            )
                            decoded
                        }
                    } else null
                }
                recycle()
                loadedBitmap = bitmap
                imageView.setImageBitmap(bitmap)
            }
        }

        fun recycle() {
            loadedBitmap?.let {
                TroveBitmapPool.shared.put(it)
                loadedBitmap = null
            }
            imageView.setImageDrawable(null)
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}
