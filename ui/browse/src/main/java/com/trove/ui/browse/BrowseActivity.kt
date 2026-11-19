package com.trove.ui.browse

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
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

class BrowseActivity : AppCompatActivity(), BrowseFragment.OnDirectoryChangedListener, BrowseFragment.TabHost {
    private lateinit var tabBar: TroveTabBar
    private lateinit var viewPager: ViewPager2
    private val tabs = mutableListOf<FileItem>()
    
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
            // Add root privileged tab
            privilegedService?.let {
                try {
                    val rootPath = "/sdcard"
                    val rootStat = it.stat(rootPath)
                    addNewTab(PrivilegedFileItem(it, rootStat, rootPath))
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
                    addNewTab(LocalFileItem(root))
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

            // Overflow menu (sort / hidden files / new folder / servers)
            val menuButton = Button(this@BrowseActivity).apply {
                text = "⋮"
                setTextColor(android.graphics.Color.parseColor("#ECEFF4"))
                setBackgroundColor(android.graphics.Color.parseColor("#3B4252"))
                setOnClickListener { view -> showOverflowMenu(view) }
            }
            addView(menuButton)
        }
        rootLayout.addView(actionBar)

        tabBar = TroveTabBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            )
            setOnTabSelectedListener { index ->
                if (viewPager.currentItem != index) {
                    viewPager.currentItem = index
                }
            }
            // Long-press a tab to close it (the last tab stays)
            onTabLongClickListener = { index ->
                if (tabs.size > 1) {
                    tabs.removeAt(index)
                    tabBar.removeTab(index)
                    viewPager.adapter?.notifyItemRemoved(index)
                    val target = index.coerceAtMost(tabs.size - 1)
                    viewPager.currentItem = target
                    tabBar.selectTab(target, notify = false)
                } else {
                    Toast.makeText(this@BrowseActivity, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rootLayout.addView(tabBar)

        viewPager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            adapter = TabAdapter(this@BrowseActivity)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    tabBar.selectTab(position, notify = false)
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
        rootLayout.addView(viewPager)

        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this) {
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}") as? BrowseFragment
            if (currentFragment?.handleBackPressed() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Initialize with default internal storage tab
        val root = Environment.getExternalStorageDirectory()
        addNewTab(LocalFileItem(root))

        // Restore persisted SAF root if any
        getPreferences(MODE_PRIVATE).getString("saf_root", null)?.let {
            addNewSafTab(Uri.parse(it))
        }

        ensureAllFilesAccess()
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

    override fun openTab(root: FileItem, label: String) {
        tabs.add(root)
        tabBar.addTab(label)
        viewPager.adapter?.notifyItemInserted(tabs.size - 1)
        viewPager.currentItem = tabs.size - 1
        tabBar.selectTab(tabs.size - 1, notify = false)
    }

    private fun showOverflowMenu(anchor: android.view.View) {
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

        menu.add(0, 14, 0, "Settings")

        popup.setOnMenuItemClickListener { item ->
            val fragment = supportFragmentManager
                .findFragmentByTag("f${viewPager.currentItem}") as? BrowseFragment
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
            }
            true
        }
        popup.show()
    }

    private fun addNewTab(root: FileItem) {
        tabs.add(root)
        val title = if (root.name.isEmpty()) "Root" else root.name
        tabBar.addTab(title)
        viewPager.adapter?.notifyItemInserted(tabs.size - 1)
        viewPager.currentItem = tabs.size - 1
        tabBar.selectTab(tabs.size - 1, notify = false)
    }

    override fun onDirectoryChanged(directory: FileItem) {
        val current = viewPager.currentItem
        if (current in tabs.indices) {
            val label = if (directory.name.isEmpty()) "Root" else directory.name
            tabBar.updateTab(current, label)
        }
    }

    private fun addNewSafTab(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        getPreferences(MODE_PRIVATE).edit().putString("saf_root", uri.toString()).apply()

        val rootDoc = DocumentFile.fromTreeUri(this, uri)
        if (rootDoc != null) {
            addNewTab(ScopedFileItem(this, rootDoc, uri, "/"))
        }
    }

    private inner class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = tabs.size
        override fun createFragment(position: Int): Fragment {
            return BrowseFragment.newInstance(tabs[position])
        }
    }
}
