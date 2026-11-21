package com.trove.ui.browse

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.trove.core.fs.FileItem
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.ScopedFileItem
import com.trove.privilege.fs.PrivilegedFileItem
import com.trove.privilege.ITrovePrivileged
import com.trove.privilege.TrovePrivilegedService
import com.trove.ui.widgets.TroveTabBar
import rikka.shizuku.Shizuku
import java.io.File

class BrowseActivity : AppCompatActivity(),
    BrowseFragment.OnDirectoryChangedListener,
    BrowseFragment.TabHost,
    DualPaneHost {

    private val dualPaneState = DualPaneState()

    // Pane 0 (Primary)
    private lateinit var tabBar0: TroveTabBar
    private lateinit var viewPager0: ViewPager2
    private val pane0Tabs = mutableListOf<FileItem>()
    private lateinit var pane0Container: LinearLayout

    // Pane 1 (Secondary / Dual pane)
    private lateinit var tabBar1: TroveTabBar
    private lateinit var viewPager1: ViewPager2
    private val pane1Tabs = mutableListOf<FileItem>()
    private lateinit var pane1Container: LinearLayout

    // Layout containers
    private lateinit var panesContainer: LinearLayout
    private lateinit var paneDivider: View

    private var privilegedService: ITrovePrivileged? = null

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            addNewSafTab(uri)
        }
    }

    private val shizukuPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindShizukuService()
        } else {
            Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            privilegedService = ITrovePrivileged.Stub.asInterface(service)
            // Add root privileged tab to active pane
            privilegedService?.let {
                try {
                    val rootPath = "/sdcard"
                    val rootStat = it.stat(rootPath)
                    addNewTab(dualPaneState.activePaneIndex, PrivilegedFileItem(it, rootStat, rootPath))
                } catch (e: Exception) {
                    Toast.makeText(this@BrowseActivity, "Failed to mount Shizuku root", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        dualPaneState.setDualPane(Prefs.dualPane)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // One uniform strip behind the whole row — per-item backgrounds
            // left a visible seam under the flexible spacer.
            setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt()
            )

            // Text-hugging items with one uniform gap: weight-equal slots
            // centered their labels, so the short "SAF" sat visibly farther
            // from "Storage" than "Storage" sat from "Shizuku". TextViews
            // instead of Buttons — the Button style forces an 88dp minimum
            // width that setMinimumWidth(0) can't clear, which squeezed the
            // overflow ⋮ off the right edge.
            fun barItem(label: String, color: String, size: Float, onClick: (TextView) -> Unit): TextView =
                TextView(this@BrowseActivity).apply {
                    text = label
                    setTextColor(android.graphics.Color.parseColor(color))
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(12, 0, 12, 0)
                    includeFontPadding = false
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    textSize = size
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setOnClickListener { onClick(this) }
                }

            addView(barItem("☰", "#88C0D0", 15f) { openDrawer() })
            addView(barItem("SAF", "#88C0D0", 14f) { launchSafPicker() })
            addView(barItem("Storage", "#ECEFF4", 14f) {
                val root = Environment.getExternalStorageDirectory()
                addNewTab(dualPaneState.activePaneIndex, LocalFileItem(root))
            })
            addView(barItem("Shizuku", "#81A1C1", 14f) { tryMountShizuku() })

            // Flexible spacer keeps the overflow ⋮ pinned to the right edge
            // and absorbs all remaining real estate, so nothing is pushed
            // off-screen on narrow portrait displays.
            addView(View(this@BrowseActivity), LinearLayout.LayoutParams(0, 1, 1f))

            // Overflow menu (sort / hidden files / new folder / servers / dual pane)
            addView(barItem("⋮", "#ECEFF4", 18f) { showOverflowMenu(it) })
        }
        rootLayout.addView(actionBar)

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        panesContainer = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // Setup Pane 0
        tabBar0 = createTabBar(0)
        viewPager0 = createViewPager(0, pane0Tabs, tabBar0)
        setupTabClose(0, tabBar0, viewPager0, pane0Tabs)

        pane0Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1.0f
            )
            addView(wrapTabBarWithMenu(0, tabBar0))
            addView(viewPager0)
        }
        panesContainer.addView(pane0Container)

        // Setup Pane Divider
        val dividerPx = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        paneDivider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#4C566A"))
            layoutParams = LinearLayout.LayoutParams(
                if (isLandscape) dividerPx else LinearLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else dividerPx
            )
            visibility = if (dualPaneState.isDualPane) View.VISIBLE else View.GONE
        }
        panesContainer.addView(paneDivider)

        // Setup Pane 1
        tabBar1 = createTabBar(1)
        viewPager1 = createViewPager(1, pane1Tabs, tabBar1)
        setupTabClose(1, tabBar1, viewPager1, pane1Tabs)

        pane1Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1.0f
            )
            visibility = if (dualPaneState.isDualPane) View.VISIBLE else View.GONE
            addView(wrapTabBarWithMenu(1, tabBar1))
            addView(viewPager1)
        }
        panesContainer.addView(pane1Container)

        rootLayout.addView(panesContainer)

        // Slide-in side drawer (MiX-style bookmarks panel): scrim + panel over content
        drawerScrim = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }

        val densityDp = resources.displayMetrics.density
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
            elevation = 16f * densityDp
            visibility = View.GONE
        }
        drawerPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
            setPadding(12, 20, 12, 20)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@BrowseActivity).apply {
                text = "Bookmarks"
                setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BrowseActivity).apply {
                text = "＋"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(android.graphics.Color.parseColor("#88C0D0"))
                textSize = 15f
                // Compact glyphs: two default-width Buttons would not even fit
                // in the 45%-wide drawer panel.
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    (supportFragmentManager.findFragmentByTag(activePaneFragmentTag()) as? BrowseFragment)?.addBookmark()
                    populateDrawer()
                }
            })
            addView(TextView(this@BrowseActivity).apply {
                text = "✕"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(android.graphics.Color.parseColor("#BF616A"))
                textSize = 15f
                setPadding(16, 8, 16, 8)
                setOnClickListener { closeDrawer() }
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        drawerList = ListView(this).apply {
            divider = null
        }
        drawerPanel.addView(drawerList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Slide-over container: content below, scrim + drawer above (z-order)
        val drawerFrame = android.widget.FrameLayout(this)
        drawerFrame.addView(rootLayout, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        drawerFrame.addView(drawerScrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // The panel MUST carry its own width: a WRAP_CONTENT panel lets the
        // MATCH_PARENT ListView inside expand it to the full screen, so the
        // "45% drawer" ended up covering the whole browse area.
        drawerFrame.addView(drawerPanel, android.widget.FrameLayout.LayoutParams(
            drawerWidthPx(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.START
        ))
        setContentView(drawerFrame)

        drawerPanel.translationX = -drawerWidthPx().toFloat()

        onBackPressedDispatcher.addCallback(this) {
            val activeIndex = dualPaneState.activePaneIndex
            val activePager = if (activeIndex == 0) viewPager0 else viewPager1
            val tag = "f${(activeIndex * 10000L) + activePager.currentItem}"
            val currentFragment = supportFragmentManager.findFragmentByTag(tag) as? BrowseFragment
            if (currentFragment?.handleBackPressed() != true) {
                if (activeIndex == 1) {
                    dualPaneState.setActivePane(0)
                    updatePaneFocusUi()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        // Initialize with the Home dashboard tab (MiX "Home" equivalent)
        addNewTab(0, TroveHomeFragment.Companion.Marker, "Home")

        // Restore persisted SAF root if any
        getPreferences(MODE_PRIVATE).getString("saf_root", null)?.let {
            addNewSafTab(Uri.parse(it))
        }

        // If dual pane was saved as enabled, initialize pane 1
        if (dualPaneState.isDualPane) {
            updateDualPaneVisibility()
        }

        ensureAllFilesAccess()
    }

    /** MiX btn_tab_menu equivalent, one per pane. */
    private fun wrapTabBarWithMenu(paneIndex: Int, bar: TroveTabBar): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
        }
        // TextView, not Button: the Button style's 88dp minimum width ate a
        // whole tab slot before the tabs even started. Fixed 26dp width with
        // a centered glyph puts ▾ on the ☰'s column (☰ ≈ glyph + 12dp
        // padding), one row below — the requested alignment.
        row.addView(TextView(this).apply {
            text = "▾"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#88C0D0"))
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            gravity = android.view.Gravity.CENTER
            textSize = 18f
            setOnClickListener { showTabMenu(it, paneIndex) }
        }, LinearLayout.LayoutParams(
            (26 * resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        bar.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        row.addView(bar)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (44 * resources.displayMetrics.density).toInt()
        )
        return row
    }

    private fun paneParts(paneIndex: Int): Triple<MutableList<FileItem>, TroveTabBar, ViewPager2> =
        if (paneIndex == 0) Triple(pane0Tabs, tabBar0, viewPager0)
        else Triple(pane1Tabs, tabBar1, viewPager1)

    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerList: ListView
    private var drawerOpen = false

    private fun activePaneFragmentTag(): String {
        val activeIndex = dualPaneState.activePaneIndex
        val activePager = if (activeIndex == 0) viewPager0 else viewPager1
        return "f${(activeIndex * 10000L) + activePager.currentItem}"
    }

    private fun openDrawer() {
        populateDrawer()
        // Re-apply width each open so an orientation change can't leave a
        // stale panel width from the previous configuration.
        (drawerPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.width = drawerWidthPx()
        drawerPanel.translationX = -drawerWidthPx().toFloat()
        drawerOpen = true
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.animate().translationX(0f).setDuration(180).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawerScrim.visibility = View.GONE
        drawerPanel.animate()
            .translationX(-drawerWidthPx().toFloat())
            .setDuration(160)
            .withEndAction { if (!drawerOpen) drawerPanel.visibility = View.GONE }
            .start()
    }

    /** Drawer panel width: 45% of the screen, same value the slide offsets use. */
    private fun drawerWidthPx(): Int = resources.displayMetrics.widthPixels * 45 / 100

    private fun populateDrawer() {
        val rows = mutableListOf<DrawerRow>()
        rows += DrawerRow("PLACES", null, true)
        rows += DrawerRow("Internal storage", Environment.getExternalStorageDirectory().absolutePath, false)
        rows += DrawerRow("Recycle bin", java.io.File(
            Environment.getExternalStorageDirectory(), ".TroveTrash").absolutePath, false)
        val bookmarks = Prefs.bookmarks().toList().sorted()
        if (bookmarks.isNotEmpty()) rows += DrawerRow("BOOKMARKS", null, true)
        bookmarks.forEach { b ->
            rows += DrawerRow(b.substringBefore('|'), b.substringAfter('|'), false)
        }
        drawerList.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                return if (row.header) {
                    (convertView as? TextView ?: TextView(this@BrowseActivity)).apply {
                        text = row.label
                        setTextColor(android.graphics.Color.parseColor("#81A1C1"))
                        textSize = 12f
                        setPadding(40, 32, 24, 8)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        isEnabled = false
                    }
                } else {
                    (convertView as? TextView ?: TextView(this@BrowseActivity)).apply {
                        text = row.label
                        setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
                        textSize = 15f
                        setPadding(40, 24, 24, 24)
                    }
                }
            }
        }
        drawerList.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            if (!row.header && row.path != null) {
                closeDrawer()
                addNewTab(dualPaneState.activePaneIndex, LocalFileItem(java.io.File(row.path)))
            }
        }
        drawerList.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows[position]
            if (!row.header) {
                Prefs.removeBookmark(row.label, row.path!!)
                populateDrawer()
                Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
    }

    data class DrawerRow(val label: String, val path: String?, val header: Boolean)

    /** MiX tab menu: add / reset / close variants / jump-to, per pane. */
    private fun showTabMenu(anchor: android.view.View, paneIndex: Int) {
        val (tabList, bar, pager) = paneParts(paneIndex)
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Add new tab")
        popup.menu.add(0, 2, 0, "Reset tabs")
        popup.menu.add(0, 3, 0, "Close tab")
        popup.menu.add(0, 4, 0, "Close others")
        popup.menu.add(0, 5, 0, "Close left tabs")
        popup.menu.add(0, 6, 0, "Jump to…")
        popup.setOnMenuItemClickListener { item ->
            fun closeInPane(index: Int) {
                if (tabList.size <= 1) {
                    Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
                    return
                }
                tabList.removeAt(index)
                bar.removeTab(index)
                pager.adapter?.notifyItemRemoved(index)
                val target = index.coerceAtMost(tabList.size - 1)
                pager.currentItem = target
                bar.selectTab(target, notify = false)
            }
            when (item.itemId) {
                1 -> addNewTab(paneIndex, LocalFileItem(Environment.getExternalStorageDirectory()))
                2 -> {
                    val current = pager.currentItem
                    for (i in tabList.indices.reversed()) {
                        if (i != current) closeInPane(i)
                    }
                }
                3 -> closeInPane(pager.currentItem)
                4 -> {
                    val current = pager.currentItem
                    for (i in tabList.indices.reversed()) {
                        if (i != current) closeInPane(i)
                    }
                }
                5 -> {
                    val current = pager.currentItem
                    for (i in current - 1 downTo 0) closeInPane(i)
                }
                6 -> {
                    val labels = tabList.map { t -> if (t.name.isEmpty()) "Root" else t.name }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Jump to tab")
                        .setItems(labels.toTypedArray()) { _, which ->
                            pager.currentItem = which
                            bar.selectTab(which, notify = false)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun createTabBar(paneIndex: Int): TroveTabBar {
        return TroveTabBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            )
            setOnTabSelectedListener { index ->
                notifyPaneActive(paneIndex)
                val pager = if (paneIndex == 0) viewPager0 else viewPager1
                if (pager.currentItem != index) {
                    pager.currentItem = index
                }
            }
        }
    }

    private fun createViewPager(
        paneIndex: Int,
        tabList: MutableList<FileItem>,
        bar: TroveTabBar
    ): ViewPager2 {
        return ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            adapter = PaneTabAdapter(this@BrowseActivity, paneIndex, tabList)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    bar.selectTab(position, notify = false)
                    notifyPaneActive(paneIndex)
                }
            })
            // Subtle Nordic page transformer for smooth, low-latency tab swiping
            setPageTransformer { page, position ->
                val absPos = Math.abs(position)
                if (position < -1f || position > 1f) {
                    page.alpha = 0f
                } else {
                    page.alpha = 1.0f - absPos * 0.15f
                    page.scaleX = 1.0f - absPos * 0.03f
                    page.scaleY = 1.0f - absPos * 0.03f
                }
            }
        }
    }

    private fun setupTabClose(
        paneIndex: Int,
        bar: TroveTabBar,
        pager: ViewPager2,
        tabList: MutableList<FileItem>
    ) {
        bar.onTabLongClickListener = { index ->
            if (tabList.size > 1) {
                tabList.removeAt(index)
                bar.removeTab(index)
                pager.adapter?.notifyItemRemoved(index)
                val target = index.coerceAtMost(tabList.size - 1)
                pager.currentItem = target
                bar.selectTab(target, notify = false)
            } else {
                Toast.makeText(this@BrowseActivity, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPaneOrientation(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyPaneOrientation(isLandscape: Boolean) {
        panesContainer.orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val dividerPx = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        pane0Container.layoutParams = LinearLayout.LayoutParams(
            if (isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else 0,
            1.0f
        )
        paneDivider.layoutParams = LinearLayout.LayoutParams(
            if (isLandscape) dividerPx else LinearLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else dividerPx
        )
        pane1Container.layoutParams = LinearLayout.LayoutParams(
            if (isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
            if (isLandscape) LinearLayout.LayoutParams.MATCH_PARENT else 0,
            1.0f
        )
    }

    private fun toggleDualPane() {
        val enabled = dualPaneState.toggleDualPane()
        Prefs.dualPane = enabled
        updateDualPaneVisibility()
        Toast.makeText(
            this,
            if (enabled) "Dual pane enabled" else "Dual pane disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateDualPaneVisibility() {
        val isDual = dualPaneState.isDualPane
        paneDivider.visibility = if (isDual) View.VISIBLE else View.GONE
        pane1Container.visibility = if (isDual) View.VISIBLE else View.GONE

        if (isDual && pane1Tabs.isEmpty()) {
            val root = Environment.getExternalStorageDirectory()
            addNewTab(1, LocalFileItem(root))
        }
        updatePaneFocusUi()
    }

    private fun updatePaneFocusUi() {
        if (!dualPaneState.isDualPane) {
            tabBar0.setBackgroundColor(android.graphics.Color.parseColor("#2E3440"))
            return
        }
        val active0 = dualPaneState.activePaneIndex == 0
        tabBar0.setBackgroundColor(android.graphics.Color.parseColor(if (active0) "#3B4252" else "#242933"))
        tabBar1.setBackgroundColor(android.graphics.Color.parseColor(if (!active0) "#3B4252" else "#242933"))
        paneDivider.setBackgroundColor(android.graphics.Color.parseColor(if (active0) "#81A1C1" else "#88C0D0"))
    }

    // ---- DualPaneHost implementation -----------------------------------------

    override val isDualPaneActive: Boolean
        get() = dualPaneState.isDualPane

    override val activePaneIndex: Int
        get() = dualPaneState.activePaneIndex

    override fun getOppositePaneDirectory(currentPaneIndex: Int): FileItem? {
        if (!dualPaneState.isDualPane) return null
        val oppIndex = if (currentPaneIndex == 0) 1 else 0
        val oppPager = if (oppIndex == 0) viewPager0 else viewPager1
        val oppTabs = if (oppIndex == 0) pane0Tabs else pane1Tabs
        val tag = "f${(oppIndex * 10000L) + oppPager.currentItem}"
        val frag = supportFragmentManager.findFragmentByTag(tag) as? BrowseFragment
        return frag?.activeDirectory ?: oppTabs.getOrNull(oppPager.currentItem)
    }

    override fun reloadOppositePane(currentPaneIndex: Int) {
        if (!dualPaneState.isDualPane) return
        val oppIndex = if (currentPaneIndex == 0) 1 else 0
        val oppPager = if (oppIndex == 0) viewPager0 else viewPager1
        val tag = "f${(oppIndex * 10000L) + oppPager.currentItem}"
        (supportFragmentManager.findFragmentByTag(tag) as? BrowseFragment)?.reload()
    }

    override fun notifyPaneActive(paneIndex: Int) {
        if (dualPaneState.setActivePane(paneIndex)) {
            updatePaneFocusUi()
        }
    }

    override fun openTab(paneIndex: Int, root: FileItem, label: String) {
        val tabList = if (paneIndex == 0) pane0Tabs else pane1Tabs
        val bar = if (paneIndex == 0) tabBar0 else tabBar1
        val pager = if (paneIndex == 0) viewPager0 else viewPager1

        tabList.add(root)
        bar.addTab(label)
        pager.adapter?.notifyItemInserted(tabList.size - 1)
        pager.currentItem = tabList.size - 1
        bar.selectTab(tabList.size - 1, notify = false)
        notifyPaneActive(paneIndex)
    }

    override fun openTab(root: FileItem, label: String) {
        openTab(dualPaneState.activePaneIndex, root, label)
    }

    /** Concrete-class entry point for the Home dashboard (interface casts
     *  failed at runtime under R8 full mode — ClassCastException). */
    fun openTabFromHome(root: FileItem, label: String) {
        openTab(dualPaneState.activePaneIndex, root, label)
    }

    private fun addNewTab(paneIndex: Int, root: FileItem, label: String? = null) {
        val tabList = if (paneIndex == 0) pane0Tabs else pane1Tabs
        val bar = if (paneIndex == 0) tabBar0 else tabBar1
        val pager = if (paneIndex == 0) viewPager0 else viewPager1

        tabList.add(root)
        val title = label ?: if (root.name.isEmpty()) "Root" else root.name
        bar.addTab(title)
        pager.adapter?.notifyItemInserted(tabList.size - 1)
        pager.currentItem = tabList.size - 1
        bar.selectTab(tabList.size - 1, notify = false)
        notifyPaneActive(paneIndex)
    }

    override fun onDirectoryChanged(directory: FileItem) {
        onDirectoryChanged(dualPaneState.activePaneIndex, directory)
    }

    override fun onDirectoryChanged(paneIndex: Int, directory: FileItem) {
        val tabList = if (paneIndex == 0) pane0Tabs else pane1Tabs
        val bar = if (paneIndex == 0) tabBar0 else tabBar1
        val pager = if (paneIndex == 0) viewPager0 else viewPager1
        val current = pager.currentItem
        if (current in tabList.indices) {
            val label = if (directory.name.isEmpty()) "Root" else directory.name
            bar.updateTab(current, label)
        }
    }

    /**
     * Scoped storage (Android 11+): without the MANAGE_EXTERNAL_STORAGE grant
     * the FUSE layer shows directories but silently hides every file from
     * java.io.File — a file manager that lists only folders. Route the user
     * to the system "All files access" panel until granted.
     */
    private fun ensureAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (android.os.Environment.isExternalStorageManager()) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("All files access needed")
            .setMessage(
                "Trove needs \"All files access\" to list and manage files. " +
                    "Without it Android shows only empty folders.\n\n" +
                    "Enable it for Trove in the next screen, then come back."
            )
            .setPositiveButton("Open settings") { d, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // Rare OEM fallback: generic all-files panel
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            )
                        )
                    } catch (_: Exception) { }
                }
                d.dismiss()
            }
            .setNegativeButton("Use SAF only") { d, _ -> d.dismiss() }
            .show()
    }

    private fun tryMountShizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku not running", Toast.LENGTH_SHORT).show()
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindShizukuService()
        } else {
            Shizuku.requestPermission(0)
        }
    }

    private fun bindShizukuService() {
        val serviceArgs = Shizuku.UserServiceArgs(ComponentName(this, TrovePrivilegedService::class.java))
            .daemon(false)
            .processNameSuffix("privileged")

        Shizuku.bindUserService(serviceArgs, shizukuConnection)
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        val menu = popup.menu

        menu.add(0, 19, 0, "Sort…")

        menu.add(0, 4, 0, "Show hidden files")
            .setCheckable(true).isChecked = BrowseFragment.showHidden

        menu.add(0, 5, 0, "New folder")

        val servers = menu.addSubMenu("Servers")
        servers.add(0, 6, 0, "FTP (port 2121)")
            .setCheckable(true).isChecked = com.trove.server.ftp.FtpServerService.running
        servers.add(0, 7, 0, "WebDAV (port 8080)")
            .setCheckable(true).isChecked = com.trove.server.webdav.WebDavServerService.running
        servers.add(0, 8, 0, "SFTP (port 2222)")
            .setCheckable(true).isChecked = com.trove.server.sftp.SftpServerService.running

        menu.add(0, 9, 0, "Search")
        menu.add(0, 10, 0, "Refresh")
        menu.add(0, 11, 0, "Extract all")
        menu.add(0, 15, 0, "Add network place…")
        menu.add(0, 16, 0, "Recycle bin")

        val bookmarks = menu.addSubMenu("Bookmarks")
        bookmarks.add(0, 12, 0, "Add current folder")
        bookmarks.add(0, 13, 0, "Go to bookmark…")

        val tools = menu.addSubMenu("Tools")
        tools.add(0, 21, 0, "Cleaner")
        tools.add(0, 17, 0, "Checksum (selected file)")
        tools.add(0, 18, 0, "Open in terminal")

        menu.add(0, 20, 0, "Dual pane")
            .setCheckable(true).isChecked = dualPaneState.isDualPane

        menu.add(0, 14, 0, "Settings")

        popup.setOnMenuItemClickListener { item ->
            val activeIndex = dualPaneState.activePaneIndex
            val activePager = if (activeIndex == 0) viewPager0 else viewPager1
            val tag = "f${(activeIndex * 10000L) + activePager.currentItem}"
            val fragment = supportFragmentManager.findFragmentByTag(tag) as? BrowseFragment

            when (item.itemId) {
                1 -> { BrowseFragment.sortBy = "name"; reloadAllPanes(BrowseFragment.MenuAction.SORT_NAME) }
                2 -> { BrowseFragment.sortBy = "size"; reloadAllPanes(BrowseFragment.MenuAction.SORT_SIZE) }
                3 -> { BrowseFragment.sortBy = "date"; reloadAllPanes(BrowseFragment.MenuAction.SORT_DATE) }
                4 -> {
                    BrowseFragment.showHidden = !BrowseFragment.showHidden
                    reloadAllPanes(BrowseFragment.MenuAction.TOGGLE_HIDDEN)
                }
                5 -> fragment?.onMenuAction(BrowseFragment.MenuAction.NEW_FOLDER)
                6 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_FTP)
                7 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_WEBDAV)
                8 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_SFTP)
                9 -> fragment?.showSearchDialog()
                10 -> reloadAllPanes()
                11 -> fragment?.extractArchiveAll()
                12 -> fragment?.addBookmark()
                13 -> fragment?.showBookmarks()
                14 -> startActivity(android.content.Intent(this, TroveSettingsActivity::class.java))
                15 -> fragment?.showNetworkDialog()
                16 -> fragment?.openRecycleBin()
                17 -> fragment?.checksumSelection()
                18 -> fragment?.openInTerminal()
                19 -> fragment?.showSortMenu(anchor)
                20 -> toggleDualPane()
                21 -> startActivity(
                    android.content.Intent(this, com.trove.feature.cleaner.TroveCleanerActivity::class.java)
                )
            }
            true
        }
        popup.show()
    }

    private fun reloadAllPanes(action: BrowseFragment.MenuAction? = null) {
        val f0 = supportFragmentManager.findFragmentByTag("f${viewPager0.currentItem}") as? BrowseFragment
        if (action != null) f0?.onMenuAction(action) else f0?.reload()
        if (dualPaneState.isDualPane) {
            val f1 = supportFragmentManager.findFragmentByTag("f${10000 + viewPager1.currentItem}") as? BrowseFragment
            if (action != null) f1?.onMenuAction(action) else f1?.reload()
        }
    }

    /**
     * Opens the SAF tree picker at a location the system actually allows to
     * be mounted. Launched with no initial URI it lands on the storage root,
     * where Android 11+ blocks tree picking ("Can't use this folder") and the
     * disabled USE THIS FOLDER button renders as an unreadable black pill.
     * Start in the browsed folder (or the last mounted SAF root) instead so
     * the button is live from the start; from the blocked root, say why.
     */
    private fun launchSafPicker() {
        val storageRootPath = Environment.getExternalStorageDirectory().absolutePath
        val current = (supportFragmentManager.findFragmentByTag(activePaneFragmentTag()) as? BrowseFragment)
            ?.activeDirectory as? LocalFileItem

        // DocumentsUI ignores file:// initial URIs; it only honours document
        // URIs of its externalstorage provider (primary:<relative path>).
        val relPath = current?.path
            ?.takeIf { it.startsWith(storageRootPath) && it != storageRootPath }
            ?.removePrefix(storageRootPath)
            ?.trim('/')

        val initial: Uri? = if (relPath != null) {
            android.provider.DocumentsContract.buildDocumentUri(
                "content://com.android.externalstorage.documents", "primary:$relPath"
            )
        } else {
            getPreferences(MODE_PRIVATE).getString("saf_root", null)?.let(Uri::parse)
        }

        if (initial == null && (current == null || current.path == storageRootPath)) {
            Toast.makeText(
                this,
                "Android blocks SAF mounting of the storage root — enter any subfolder first (USE THIS FOLDER activates there)",
                Toast.LENGTH_LONG
            ).show()
        }
        openDocumentTreeLauncher.launch(initial)
    }

    private fun addNewSafTab(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        getPreferences(MODE_PRIVATE).edit().putString("saf_root", uri.toString()).apply()

        val rootDoc = DocumentFile.fromTreeUri(this, uri)
        if (rootDoc != null) {
            addNewTab(dualPaneState.activePaneIndex, ScopedFileItem(this, rootDoc, uri, "/"))
        }
    }

    private inner class PaneTabAdapter(
        activity: AppCompatActivity,
        private val paneIndex: Int,
        private val tabList: MutableList<FileItem>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = tabList.size
        override fun getItemId(position: Int): Long = (paneIndex * 10000L) + position
        override fun containsItem(itemId: Long): Boolean {
            val pos = (itemId - (paneIndex * 10000L)).toInt()
            return pos in tabList.indices
        }
        override fun createFragment(position: Int): Fragment {
            val root = tabList[position]
            return if (root.path == TroveHomeFragment.HOME_PATH) {
                TroveHomeFragment.newInstance()
            } else {
                BrowseFragment.newInstance(root, paneIndex)
            }
        }
    }
}
