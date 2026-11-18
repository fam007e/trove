package com.trove.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TrovePdfViewerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private lateinit var nightModeButton: Button
    private var pdfRenderer: TrovePdfRenderer? = null
    private var isNightMode = false
    private var filePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrovePdfDocument.initialize(this)

        filePath = intent.getStringExtra(EXTRA_PDF_PATH)
        if (filePath.isNullOrEmpty()) {
            Toast.makeText(this, "No PDF path specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
        }

        viewPager = ViewPager2(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            adapter = PdfPagerAdapter()
        }
        rootLayout.addView(viewPager)

        // Bottom controller bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        pageIndicator = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Loading..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        bottomBar.addView(pageIndicator)

        nightModeButton = Button(this).apply {
            text = "Night Mode"
            textSize = 12f
            setOnClickListener {
                isNightMode = !isNightMode
                text = if (isNightMode) "Day Mode" else "Night Mode"
                viewPager.adapter?.notifyDataSetChanged()
            }
        }
        bottomBar.addView(nightModeButton)

        rootLayout.addView(bottomBar)
        setContentView(rootLayout)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updatePageIndicator(position)
            }
        })

        loadPdf(filePath!!)
    }

    private fun loadPdf(path: String) {
        lifecycleScope.launch {
            val fileItem = LocalFileItem(File(path))
            when (val result = TrovePdfRenderer.open(fileItem)) {
                is Result.Success -> {
                    pdfRenderer = result.value
                    viewPager.adapter?.notifyDataSetChanged()
                    updatePageIndicator(viewPager.currentItem)
                }
                is Result.Failure -> {
                    Toast.makeText(
                        this@TrovePdfViewerActivity,
                        "Failed to open PDF: ${result.exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun updatePageIndicator(currentPage: Int) {
        val total = pdfRenderer?.pageCount ?: 0
        if (total > 0) {
            val file = File(filePath ?: "")
            pageIndicator.text = "${file.name}  •  Page ${currentPage + 1} of $total"
        }
    }

    override fun onDestroy() {
        pdfRenderer?.close()
        pdfRenderer = null
        super.onDestroy()
    }

    private inner class PdfPagerAdapter : RecyclerView.Adapter<PdfPageViewHolder>() {
        override fun getItemCount(): Int = pdfRenderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(if (isNightMode) Color.BLACK else Color.WHITE)
            }
            return PdfPageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
            holder.bind(position, isNightMode)
        }

        override fun onViewRecycled(holder: PdfPageViewHolder) {
            holder.recycle()
        }
    }

    private inner class PdfPageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView) {
        private var renderedBitmap: Bitmap? = null

        fun bind(pageIndex: Int, nightMode: Boolean) {
            val renderer = pdfRenderer ?: return
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val (w, h) = renderer.getPageDimensions(pageIndex)
                    // Scale factor for crisp rendering on high-DPI displays
                    val scale = 2.0f
                    val targetW = (w * scale).toInt().coerceAtLeast(1)
                    val targetH = (h * scale).toInt().coerceAtLeast(1)
                    renderer.renderPage(pageIndex, targetW, targetH, nightMode)
                }
                recycle()
                renderedBitmap = bitmap
                imageView.setImageBitmap(bitmap)
                imageView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
            }
        }

        fun recycle() {
            renderedBitmap?.recycle()
            renderedBitmap = null
            imageView.setImageDrawable(null)
        }
    }

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
    }
}
