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
import android.widget.Button
import android.widget.LinearLayout
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val mountButton = Button(this@BrowseActivity).apply {
                text = "+ SAF"
                setTextColor(android.graphics.Color.parseColor("#88C0D0"))
                setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
                setOnClickListener { openDocumentTreeLauncher.launch(null) }
            }
            addView(mountButton)

            val localButton = Button(this@BrowseActivity).apply {
                text = "+ Storage"
                setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
                setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
                setOnClickListener {
                    val root = Environment.getExternalStorageDirectory()
                    addNewTab(dualPaneState.activePaneIndex, LocalFileItem(root))
                }
            }
            addView(localButton)

            val shizukuButton = Button(this@BrowseActivity).apply {
                text = "+ Shizuku"
                setTextColor(android.graphics.Color.parseColor("#81A1C1"))
                setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
                setOnClickListener {
                    tryMountShizuku()
                }
            }
            addView(shizukuButton)

            // Overflow menu (sort / hidden files / new folder / servers / dual pane)
            val menuButton = Button(this@BrowseActivity).apply {
                text = "⋮"
                setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
                setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
                setOnClickListener { view -> showOverflowMenu(view) }
            }
            addView(menuButton)
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
            addView(tabBar0)
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
            addView(tabBar1)
            addView(viewPager1)
        }
        panesContainer.addView(pane1Container)

        rootLayout.addView(panesContainer)
        setContentView(rootLayout)

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

        // Initialize pane 0 with default internal storage tab
        val root = Environment.getExternalStorageDirectory()
        addNewTab(0, LocalFileItem(root))

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

    private fun addNewTab(paneIndex: Int, root: FileItem) {
        val tabList = if (paneIndex == 0) pane0Tabs else pane1Tabs
        val bar = if (paneIndex == 0) tabBar0 else tabBar1
        val pager = if (paneIndex == 0) viewPager0 else viewPager1

        tabList.add(root)
        val title = if (root.name.isEmpty()) "Root" else root.name
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

        val sort = menu.addSubMenu("Sort by")
        sort.add(0, 1, 0, "Name").isChecked = BrowseFragment.sortBy == "name"
        sort.add(0, 2, 0, "Size").isChecked = BrowseFragment.sortBy == "size"
        sort.add(0, 3, 0, "Date modified").isChecked = BrowseFragment.sortBy == "date"
        sort.setGroupCheckable(0, true, true)

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
        tools.add(0, 17, 0, "Checksum (selected file)")
        tools.add(0, 18, 0, "Open in terminal")

        menu.add(0, 19, 0, "Dual pane")
            .setCheckable(true).isChecked = dualPaneState.isDualPane

        menu.add(0, 14, 0, "Settings")

        popup.setOnMenuItemClickListener { item ->
            val activeIndex = dualPaneState.activePaneIndex
            val activePager = if (activeIndex == 0) viewPager0 else viewPager1
            val tag = "f${(activeIndex * 10000L) + activePager.currentItem}"
            val fragment = supportFragmentManager.findFragmentByTag(tag) as? BrowseFragment

            when (item.itemId) {
                1 -> { BrowseFragment.sortBy = "name"; fragment?.onMenuAction(BrowseFragment.MenuAction.SORT_NAME) }
                2 -> { BrowseFragment.sortBy = "size"; fragment?.onMenuAction(BrowseFragment.MenuAction.SORT_SIZE) }
                3 -> { BrowseFragment.sortBy = "date"; fragment?.onMenuAction(BrowseFragment.MenuAction.SORT_DATE) }
                4 -> { BrowseFragment.showHidden = !BrowseFragment.showHidden; fragment?.onMenuAction(BrowseFragment.MenuAction.TOGGLE_HIDDEN) }
                5 -> fragment?.onMenuAction(BrowseFragment.MenuAction.NEW_FOLDER)
                6 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_FTP)
                7 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_WEBDAV)
                8 -> fragment?.onMenuAction(BrowseFragment.MenuAction.SERVER_SFTP)
                9 -> fragment?.showSearchDialog()
                10 -> fragment?.reload()
                11 -> fragment?.extractArchiveAll()
                12 -> fragment?.addBookmark()
                13 -> fragment?.showBookmarks()
                14 -> startActivity(android.content.Intent(this, TroveSettingsActivity::class.java))
                15 -> fragment?.showNetworkDialog()
                16 -> fragment?.openRecycleBin()
                17 -> fragment?.checksumSelection()
                18 -> fragment?.openInTerminal()
                19 -> toggleDualPane()
            }
            true
        }
        popup.show()
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
            return BrowseFragment.newInstance(tabList[position], paneIndex)
        }
    }
}
