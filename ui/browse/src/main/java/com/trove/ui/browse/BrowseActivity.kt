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

class BrowseActivity : AppCompatActivity(), BrowseFragment.OnDirectoryChangedListener {
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
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            
            val mountButton = Button(this@BrowseActivity).apply {
                text = "Add SAF Tab"
                setOnClickListener { openDocumentTreeLauncher.launch(null) }
            }
            addView(mountButton)

            val localButton = Button(this@BrowseActivity).apply {
                text = "Add Local Tab"
                setOnClickListener {
                    val root = Environment.getExternalStorageDirectory()
                    addNewTab(LocalFileItem(root))
                }
            }
            addView(localButton)

            val shizukuButton = Button(this@BrowseActivity).apply {
                text = "Add Shizuku Tab"
                setOnClickListener {
                    tryMountShizuku()
                }
            }
            addView(shizukuButton)
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
