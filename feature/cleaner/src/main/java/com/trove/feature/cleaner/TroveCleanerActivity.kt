package com.trove.feature.cleaner

import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trove.feature.cleaner.appcleaner.AppCleaner
import com.trove.feature.cleaner.appcleaner.automation.TroveAutomationService
import com.trove.feature.cleaner.corpse.Corpse
import com.trove.feature.cleaner.corpse.CorpseScanner
import com.trove.feature.cleaner.dedupe.DupeFile
import com.trove.feature.cleaner.dedupe.DuplicateGroup
import com.trove.feature.cleaner.dedupe.DuplicateRules
import com.trove.feature.cleaner.dedupe.DuplicateScanner
import com.trove.feature.cleaner.core.RootShell
import com.trove.feature.cleaner.systemcleaner.DefaultFilters
import com.trove.feature.cleaner.systemcleaner.JunkItem
import com.trove.feature.cleaner.systemcleaner.SystemCleanerScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cleaner hub (clean-room, after SD Maid SE's documented modules):
 * App Cleaner, Corpse Finder, System Cleaner — plus the accessibility
 * automation that lets App Cleaner work without root.
 *
 * All destructive actions are preview-first: scan → list → select →
 * confirm dialog → execute.
 */
class TroveCleanerActivity : AppCompatActivity() {

    private enum class Module { APPS, DUPLICATES, CORPSES, SYSTEM }

    private lateinit var content: FrameLayout
    private val shell = RootShell()
    private var rootStatus: RootShell.Status? = null
    private var busy = false

    private val corpseScanner = CorpseScanner(shell)
    private var corpses: List<Corpse> = emptyList()

    private val appCleaner = AppCleaner(this, shell)
    private var cacheTargets: List<com.trove.feature.cleaner.appcleaner.AppCacheTarget> = emptyList()

    private val systemScanner = SystemCleanerScanner(shell)
    private var junkItems: List<JunkItem> = emptyList()
    private val enabledFilters = DefaultFilters.all().toMutableList()

    private val dupeScanner = DuplicateScanner()
    private var dupeGroups: List<DuplicateGroup> = emptyList()
    private var dupeRows: List<DupeRow> = emptyList()
    private var dupeHeaderPositions: Set<Int> = emptySet()

    /** Per-group user choice of the copy to keep (groupIdx → fileIdx);
     *  absent = default policy (oldest). */
    private val dupeKeepOverride = android.util.SparseIntArray()

    /** Selection captured when the dedupe dialog was confirmed. */
    private var dupePending: List<DupeFile> = emptyList()

    private var currentResultList: ListView? = null

    private lateinit var rootBadgeView: TextView
    private lateinit var hubStatusView: TextView
    private lateinit var automationStatus: TextView
    private var refreshSubtitlesFn: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CleanerPrefs.init(this)
        title = "Cleaner"

        content = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#2E3440"))
        }
        setContentView(content)
        showHub()
        checkRoot()
    }

    override fun onDestroy() {
        shell.close()
        super.onDestroy()
    }

    // ---- hub -----------------------------------------------------------------

    /** Anonymous-ish tag holder for module card subtitles. */
    private class Subtitles(val refresh: () -> Unit)

    private fun showHub() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "Cleaner"
            setTextColor(Color.parseColor("#ECEFF4"))
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val rootBadge = TextView(this).apply {
            setTextColor(Color.parseColor("#BF616A"))
            textSize = 12f
            text = "checking root…"
            setPadding(12, 6, 12, 6)
        }
        headerRow.addView(rootBadge)
        root.addView(headerRow)

        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#81A1C1"))
            textSize = 12f
            setPadding(0, 12, 0, 8)
            text = "Cleaning is preview-first: scan, review, then delete."
        }
        root.addView(status)

        fun moduleCard(title: String, subtitle: () -> String, onTap: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#3B4252"))
                setPadding(32, 28, 32, 28)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
                addView(TextView(this@TroveCleanerActivity).apply {
                    text = title
                    setTextColor(Color.parseColor("#ECEFF4"))
                    textSize = 16f
                })
                val sub = TextView(this@TroveCleanerActivity).apply {
                    setTextColor(Color.parseColor("#81A1C1"))
                    textSize = 12f
                }
                addView(sub)
                tag = Subtitles { sub.text = subtitle() }
                setOnClickListener { if (!busy) onTap() }
            }

        val appCard = moduleCard("App Cleaner", {
            val cached = CleanerPrefs.lastAppCleanerBytes
            when {
                cached > 0 -> "${cacheTargets.size} packages · ${Formatter.formatFileSize(this, cached)} last scan"
                cached == 0L -> "No cleanable caches at last scan"
                else -> "Caches of installed apps (root / automation)"
            }
        }, { showModule(Module.APPS) })

        val corpseCard = moduleCard("Corpse Finder", {
            val n = CleanerPrefs.lastCorpsesCount
            when {
                n > 0 -> "$n leftover areas from uninstalled apps"
                n == 0 -> "No corpses at last scan"
                else -> "Data left behind by uninstalled apps"
            }
        }, { showModule(Module.CORPSES) })

        val systemCard = moduleCard("System Cleaner", {
            val n = CleanerPrefs.lastSystemCleanerCount
            when {
                n > 0 -> "$n findings across the enabled filters"
                n == 0 -> "Nothing found at last scan"
                else -> "Filter-based junk search"
            }
        }, { showModule(Module.SYSTEM) })

        val automationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#3B4252"))
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            addView(TextView(this@TroveCleanerActivity).apply {
                text = "Automation (accessibility)"
                setTextColor(Color.parseColor("#ECEFF4"))
                textSize = 16f
            })
            automationStatus = TextView(this@TroveCleanerActivity).apply {
                setTextColor(Color.parseColor("#81A1C1"))
                textSize = 12f
                text = automationStatusText()
            }
            addView(automationStatus)
            addView(Button(this@TroveCleanerActivity).apply {
                text = "Enable in accessibility settings"
                setBackgroundColor(Color.parseColor("#3B4252"))
                setTextColor(Color.parseColor("#88C0D0"))
                setOnClickListener {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this@TroveCleanerActivity, "Accessibility settings unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        val dupeCard = moduleCard("Deduplicator", {
            val n = CleanerPrefs.lastDupeGroups
            when {
                n > 0 -> "$n duplicate groups at last scan"
                n == 0 -> "No duplicates at last scan"
                else -> "Identical files by content hash"
            }
        }, { showModule(Module.DUPLICATES) })

        root.addView(appCard)
        root.addView(dupeCard)
        root.addView(corpseCard)
        root.addView(systemCard)
        root.addView(automationCard)

        root.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#4C566A"))
            textSize = 11f
            setPadding(0, 8, 0, 0)
            text = "Root areas cover user 0 only. Corpse deletion is permanent — " +
                "Trove's recycle bin doesn't apply to /data."
        })

        root.addView(Button(this).apply {
            text = "Run storage diagnostics"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#4C566A"))
            textSize = 12f
            setOnClickListener { if (!busy) runDiagnostics() }
        })

        content.removeAllViews()
        content.addView(scroll)

        this.rootBadgeView = rootBadge
        this.hubStatusView = status
        refreshSubtitlesFn = {
            (appCard.tag as? Subtitles)?.refresh?.invoke()
            (dupeCard.tag as? Subtitles)?.refresh?.invoke()
            (corpseCard.tag as? Subtitles)?.refresh?.invoke()
            (systemCard.tag as? Subtitles)?.refresh?.invoke()
        }
        refreshSubtitlesFn?.invoke()

        updateRootBadge()
    }

    private fun automationStatusText(): String = when {
        TroveAutomationService.isRunning -> "Running…"
        TroveAutomationService.isReady -> "Service ready — can clear caches without root"
        else -> "Service not enabled"
    }

    private fun updateRootBadge() {
        if (!::rootBadgeView.isInitialized) return
        val s = rootStatus ?: return
        rootBadgeView.text = if (s is RootShell.Status.Granted) "Root ✓" else "No root"
        rootBadgeView.setTextColor(
            Color.parseColor(if (s is RootShell.Status.Granted) "#A3BE8C" else "#BF616A")
        )
        if (::hubStatusView.isInitialized) {
            hubStatusView.text = when (s) {
                is RootShell.Status.Granted -> "Root active (Magisk) — private app data reachable"
                is RootShell.Status.Unavailable -> s.reason
            }
        }
        if (::automationStatus.isInitialized) {
            automationStatus.text = automationStatusText()
        }
    }

    private fun checkRoot() {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { shell.probe() }
            rootStatus = s
            updateRootBadge()
        }
    }

    // ---- module screens ------------------------------------------------------

    private fun showModule(module: Module) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(Button(this).apply {
            text = "← Back"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#88C0D0"))
            setOnClickListener { if (!busy) showHub() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = when (module) {
                Module.APPS -> "App Cleaner"
                Module.DUPLICATES -> "Deduplicator"
                Module.CORPSES -> "Corpse Finder"
                Module.SYSTEM -> "System Cleaner"
            }
            setTextColor(Color.parseColor("#ECEFF4"))
            textSize = 20f
            setPadding(0, 8, 0, 8)
        })

        val summary = TextView(this).apply {
            setTextColor(Color.parseColor("#81A1C1"))
            textSize = 13f
            setPadding(0, 4, 0, 8)
            text = when (module) {
                Module.APPS -> "Tap Scan to list cleanable caches."
                Module.DUPLICATES -> "Tap Scan to find identical files (same content, anywhere in user storage)."
                Module.CORPSES -> "Tap Scan to list leftovers of uninstalled apps."
                Module.SYSTEM -> "Pick filters, then Scan."
            }
        }
        root.addView(summary)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        val resultList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            divider = null
        }
        currentResultList = resultList

        // Long-press a duplicate copy → make it the group's keeper.
        resultList.setOnItemLongClickListener { _, _, position, _ ->
            if (module == Module.DUPLICATES && !busy) {
                showKeepPicker(position)
                true
            } else false
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }
        // TextView, not Button: the Button style's 88dp minWidth squeezed
        // four items into wrapping mid-word ("DELET/E"). Equal weights give
        // each label its own slot so nothing ever wraps.
        fun barItem(label: String, color: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            includeFontPadding = false
            setPadding(8, 12, 8, 12)
            setBackgroundColor(Color.parseColor("#3B4252"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { if (!busy) onClick() }
        }
        actionBar.addView(barItem("SCAN", "#88C0D0") { runScan(module, summary, progress) })
        actionBar.addView(barItem("ALL", "#88C0D0") { checkAll(resultList, true) })
        actionBar.addView(barItem("NONE", "#88C0D0") { checkAll(resultList, false) })
        val deleteVerb = module == Module.CORPSES || module == Module.DUPLICATES
        actionBar.addView(barItem(if (deleteVerb) "DELETE" else "CLEAN", "#BF616A") {
            if (!busy) confirmAndExecute(module, resultList, summary)
        })

        // Module-specific controls, then the result list + action bar last.
        when (module) {
            Module.APPS -> {
                root.addView(android.widget.Switch(this).apply {
                    text = "Include system apps"
                    isChecked = CleanerPrefs.includeSystemApps
                    setTextColor(Color.parseColor("#ECEFF4"))
                    textSize = 13f
                    setOnCheckedChangeListener { _, checked -> CleanerPrefs.includeSystemApps = checked }
                })
                root.addView(Button(this).apply {
                    text = "Clear via automation (no root)"
                    setBackgroundColor(Color.parseColor("#3B4252"))
                    setTextColor(Color.parseColor("#81A1C1"))
                    textSize = 13f
                    setOnClickListener { if (!busy) runAutomation(resultList, summary) }
                })
            }
            Module.SYSTEM -> {
                root.addView(TextView(this).apply {
                    text = "FILTERS (long-press for details)"
                    setTextColor(Color.parseColor("#81A1C1"))
                    textSize = 11f
                    setPadding(0, 16, 0, 8)
                })
                DefaultFilters.all().forEach { filter ->
                    root.addView(android.widget.Switch(this).apply {
                        text = filter.label + if (filter.requiresRoot) " ·root" else ""
                        isChecked = filter in enabledFilters
                        setTextColor(Color.parseColor("#ECEFF4"))
                        textSize = 13f
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) enabledFilters.add(filter) else enabledFilters.remove(filter)
                        }
                        setOnLongClickListener {
                            Toast.makeText(this@TroveCleanerActivity, filter.description, Toast.LENGTH_LONG).show()
                            true
                        }
                    })
                }
            }
            Module.DUPLICATES -> {
                root.addView(android.widget.Switch(this).apply {
                    text = "Only group same-named files"
                    isChecked = CleanerPrefs.dedupeMatchByName
                    setTextColor(Color.parseColor("#ECEFF4"))
                    textSize = 13f
                    setOnLongClickListener {
                        Toast.makeText(
                            this@TroveCleanerActivity,
                            "Content hashes can't see metadata: date-stamped backups with identical bytes look like duplicates. This requires the same file name too.",
                            Toast.LENGTH_LONG
                        ).show()
                        true
                    }
                    setOnCheckedChangeListener { _, checked -> CleanerPrefs.dedupeMatchByName = checked }
                })
            }
            Module.CORPSES -> { }
        }

        root.addView(resultList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(actionBar)

        content.removeAllViews()
        content.addView(root)
    }

    private fun checkAll(list: ListView, checked: Boolean) {
        val count = list.adapter?.count ?: 0
        for (i in 0 until count) {
            if (i in dupeHeaderPositions) continue // group headers are not selectable
            list.setItemChecked(i, checked)
        }
    }

    // ---- scans ---------------------------------------------------------------

    private fun runScan(module: Module, summary: TextView, progress: ProgressBar) {
        val list = currentResultList ?: return
        busy = true
        progress.visibility = View.VISIBLE
        summary.text = "Scanning…"
        lifecycleScope.launch {
            try {
                when (module) {
                    Module.APPS -> {
                        val result = withContext(Dispatchers.IO) {
                            appCleaner.scan(CleanerPrefs.includeSystemApps)
                        }
                        cacheTargets = result.targets
                        CleanerPrefs.lastAppCleanerBytes = result.targets.sumOf { it.totalKnownBytes }
                        list.adapter = RowAdapter(result.targets.map { t ->
                            val size = Formatter.formatFileSize(this@TroveCleanerActivity, t.totalKnownBytes)
                            val internal = if (t.internalCacheBytes >= 0) "" else " ·private needs root/automation"
                            "${t.label} — $size$internal"
                        })
                        summary.text = buildSummary(module, result.notes)
                    }
                    Module.CORPSES -> {
                        val result = withContext(Dispatchers.IO) {
                            corpseScanner.scan(this@TroveCleanerActivity, rootStatus is RootShell.Status.Granted)
                        }
                        corpses = result.corpses
                        CleanerPrefs.lastCorpsesCount = result.corpses.size
                        list.adapter = RowAdapter(result.corpses.map { c ->
                            val size = if (c.sizeBytes >= 0)
                                Formatter.formatFileSize(this@TroveCleanerActivity, c.sizeBytes) else "size ?"
                            "${c.path} — $size"
                        })
                        summary.text = buildSummary(module, result.notes)
                    }
                    Module.DUPLICATES -> {
                        val result = withContext(Dispatchers.IO) {
                            dupeScanner.scan(
                                java.io.File(android.os.Environment.getExternalStorageDirectory().absolutePath),
                                CleanerPrefs.dedupeMatchByName
                            )
                        }
                        dupeGroups = result.groups
                        dupeKeepOverride.clear() // fresh scan → default policy again
                        CleanerPrefs.lastDupeGroups = result.groups.size
                        renderDuplicates(list)
                        summary.text = buildSummary(module, result.notes)
                    }
                    Module.SYSTEM -> {
                        val result = withContext(Dispatchers.IO) {
                            systemScanner.scan(this@TroveCleanerActivity, enabledFilters.toList())
                        }
                        junkItems = result.items
                        CleanerPrefs.lastSystemCleanerCount = result.items.size
                        list.adapter = RowAdapter(result.items.map { j ->
                            val size = if (j.sizeBytes > 0)
                                Formatter.formatFileSize(this@TroveCleanerActivity, j.sizeBytes) else ""
                            "${j.path}${if (size.isEmpty()) "" else " — $size"}"
                        })
                        summary.text = buildSummary(module, result.notes)
                    }
                }
            } catch (e: Exception) {
                summary.text = "Scan failed: ${e.message}"
            } finally {
                busy = false
                progress.visibility = View.GONE
                refreshSubtitlesFn?.invoke()
            }
        }
    }

    private fun buildSummary(module: Module, notes: List<String>): String {
        val base = when (module) {
            Module.APPS -> "${cacheTargets.size} packages with caches"
            Module.DUPLICATES -> "${dupeGroups.size} duplicate groups"
            Module.CORPSES -> "${corpses.size} corpses found"
            Module.SYSTEM -> "${junkItems.size} findings"
        }
        return if (notes.isEmpty()) base else base + " · " + notes.joinToString(" · ")
    }

    // ---- deduplicator rows ---------------------------------------------------

    /** Mixed list model: non-selectable group headers + selectable file rows. */
    private sealed class DupeRow {
        data class Header(val label: String) : DupeRow()
        data class FileRow(val groupIdx: Int, val fileIdx: Int, val label: String) : DupeRow()
    }

    private fun dupeKeepIndex(groupIdx: Int): Int =
        dupeKeepOverride.get(groupIdx, DuplicateRules.keepIndex(dupeGroups[groupIdx].files))

    private fun renderDuplicates(list: ListView) {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val rows = mutableListOf<DupeRow>()
        val headers = mutableSetOf<Int>()
        dupeGroups.forEachIndexed { g, group ->
            val keep = dupeKeepIndex(g)
            val keepName = group.files[keep].path.substringAfterLast('/')
            rows += DupeRow.Header(
                "${group.files.size} copies · ${Formatter.formatFileSize(this, group.sizeBytes)} each · " +
                    "${Formatter.formatFileSize(this, group.wastedBytes)} reclaimable — keeping $keepName"
            )
            headers += rows.lastIndex
            group.files.forEachIndexed { i, f ->
                val meta = "${Formatter.formatFileSize(this, f.sizeBytes)} · " +
                    dateFormat.format(java.util.Date(f.lastModified))
                rows += DupeRow.FileRow(
                    g, i,
                    "${f.path} · $meta" + if (i == keep) " — KEEP" else ""
                )
            }
        }
        dupeRows = rows
        dupeHeaderPositions = headers
        list.adapter = DupeAdapter(rows)
        // Pre-check every copy suggested for deletion; the keeper stays clean.
        rows.forEachIndexed { pos, row ->
            if (row is DupeRow.FileRow && row.fileIdx != dupeKeepIndex(row.groupIdx)) {
                list.setItemChecked(pos, true)
            }
        }
    }

    /** Long-press on a copy: promote it to the group's keeper. */
    private fun showKeepPicker(position: Int) {
        val list = currentResultList ?: return
        when (val row = dupeRows.getOrNull(position)) {
            is DupeRow.FileRow -> {
                val file = dupeGroups[row.groupIdx].files[row.fileIdx]
                AlertDialog.Builder(this)
                    .setTitle("Keep this copy?")
                    .setMessage(
                        "${file.path}\n${Formatter.formatFileSize(this, file.sizeBytes)}\n\n" +
                            "This copy becomes the group's KEEP; the others are checked for deletion."
                    )
                    .setPositiveButton("KEEP this one") { _, _ ->
                        dupeKeepOverride.put(row.groupIdx, row.fileIdx)
                        renderDuplicates(list)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> { }
        }
    }

    /** Selected (groupIdx, file) pairs from the checked rows. */
    private fun selectedDupeRows(list: ListView): List<Pair<Int, DupeFile>> {
        val positions = list.checkedItemPositions
        val out = mutableListOf<Pair<Int, DupeFile>>()
        for (p in 0 until positions.size()) {
            if (!positions.valueAt(p)) continue
            when (val row = dupeRows.getOrNull(positions.keyAt(p))) {
                is DupeRow.FileRow -> out += row.groupIdx to dupeGroups[row.groupIdx].files[row.fileIdx]
                else -> { }
            }
        }
        return out
    }

    private inner class DupeAdapter(private val rows: List<DupeRow>) : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is DupeRow.FileRow
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is DupeRow.Header) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is DupeRow.Header -> (convertView as? TextView ?: TextView(this@TroveCleanerActivity)).apply {
                    text = row.label
                    setTextColor(Color.parseColor("#81A1C1"))
                    textSize = 12f
                    setPadding(8, 24, 8, 8)
                }
                is DupeRow.FileRow -> (convertView as? CheckedTextView ?: CheckedTextView(this@TroveCleanerActivity)).apply {
                    text = row.label
                    setTextColor(Color.parseColor("#ECEFF4"))
                    textSize = 13f
                    setPadding(24, 20, 24, 20)
                    checkMarkTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#88C0D0"))
                }
            }
    }

    private inner class RowAdapter(private val rows: List<String>) : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? CheckedTextView ?: CheckedTextView(this@TroveCleanerActivity)).apply {
                text = rows[position]
                setTextColor(Color.parseColor("#ECEFF4"))
                textSize = 13f
                setPadding(24, 20, 24, 20)
                checkMarkTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#88C0D0"))
            }
    }

    // ---- destructive actions -------------------------------------------------

    private fun confirmAndExecute(module: Module, list: ListView, summary: TextView) {
        val positions = list.checkedItemPositions
        val selectedIdx = (0 until positions.size()).filter { positions.valueAt(it) }

        data class Preview(val count: Int, val bytes: Long, val rows: List<String>)
        val preview = when (module) {
            Module.APPS -> {
                val sel = selectedIdx.mapNotNull { cacheTargets.getOrNull(it) }
                Preview(sel.size, sel.sumOf { it.totalKnownBytes }, sel.take(5).map { it.label })
            }
            Module.DUPLICATES -> {
                val sel = selectedDupeRows(list)
                // Guard: at least one copy per group must survive.
                val selectedPerGroup = HashMap<Int, Int>()
                sel.forEach { (g, _) -> selectedPerGroup[g] = (selectedPerGroup[g] ?: 0) + 1 }
                val wipedGroup = dupeGroups.indices.firstOrNull { g ->
                    selectedPerGroup[g] == dupeGroups[g].files.size
                }
                if (wipedGroup != null) {
                    Toast.makeText(
                        this,
                        "One group would lose every copy — uncheck at least one file in it",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                val files = sel.map { it.second }
                dupePending = files
                Preview(files.size, files.sumOf { it.sizeBytes }, files.take(5).map { it.path })
            }
            Module.CORPSES -> {
                val sel = selectedIdx.mapNotNull { corpses.getOrNull(it) }
                Preview(sel.size, sel.filter { it.sizeBytes > 0 }.sumOf { it.sizeBytes },
                    sel.take(5).map { it.path })
            }
            Module.SYSTEM -> {
                val sel = selectedIdx.mapNotNull { junkItems.getOrNull(it) }
                Preview(sel.size, 0L, sel.take(5).map { it.path })
            }
        }
        if (preview.count == 0) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }

        val verb = when (module) { Module.CORPSES, Module.DUPLICATES -> "Delete"; else -> "Clean" }
        val what = when (module) {
            Module.APPS ->
                "Clear caches of ${preview.count} app(s)" +
                    (if (preview.bytes > 0) " (${Formatter.formatFileSize(this, preview.bytes)})" else "")
            Module.DUPLICATES ->
                "Permanently delete ${preview.count} duplicate file(s)" +
                    (if (preview.bytes > 0) " (${Formatter.formatFileSize(this, preview.bytes)})" else "") +
                    " — the oldest copy in each group is kept"
            Module.CORPSES ->
                "Permanently delete ${preview.count} corpse area(s) from app data dirs"
            Module.SYSTEM -> "Delete ${preview.count} item(s)"
        }
        val previewText = preview.rows.joinToString("\n") { "• $it" }

        AlertDialog.Builder(this)
            .setTitle(verb)
            .setMessage("$what\n\n$previewText" + if (preview.count > 5) "\n• …" else "")
            .setPositiveButton(verb) { _, _ -> execute(module, selectedIdx, summary) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun execute(module: Module, selectedIdx: List<Int>, summary: TextView) {
        busy = true
        summary.text = "Working…"
        lifecycleScope.launch {
            try {
                val message = when (module) {
                    Module.APPS -> {
                        val targets = selectedIdx.mapNotNull { cacheTargets.getOrNull(it) }
                        val result = withContext(Dispatchers.IO) { appCleaner.clean(targets) }
                        "Cleaned ${result.cleaned}/${targets.size} apps, freed " +
                            Formatter.formatFileSize(this@TroveCleanerActivity, result.bytesFreed) +
                            (if (result.failed.isNotEmpty()) " · failures: ${result.failed.size}" else "")
                    }
                    Module.CORPSES -> {
                        @Suppress("DEPRECATION")
                        val installedNow = packageManager.getInstalledPackages(0)
                            .map { it.packageName }.toSet()
                        val targets = selectedIdx.mapNotNull { corpses.getOrNull(it) }
                        val removed = withContext(Dispatchers.IO) {
                            corpseScanner.delete(targets, installedNow)
                        }
                        "Deleted $removed/${targets.size} corpse areas"
                    }
                    Module.SYSTEM -> {
                        val targets = selectedIdx.mapNotNull { junkItems.getOrNull(it) }
                        val removed = withContext(Dispatchers.IO) { systemScanner.delete(targets) }
                        "Deleted $removed/${targets.size} items"
                    }
                    Module.DUPLICATES -> {
                        val targets = dupePending
                        val removed = withContext(Dispatchers.IO) { dupeScanner.delete(targets) }
                        "Deleted $removed/${targets.size} duplicate files"
                    }
                }
                summary.text = message
                Toast.makeText(this@TroveCleanerActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                summary.text = "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---- diagnostics ---------------------------------------------------------

    /**
     * One-shot probe of every path the Cleaner engines rely on. This device
     * class (GSI + FUSE) can serve different content per access path, so a
     * zero result needs an explanation before it can be trusted.
     */
    private fun runDiagnostics() {
        busy = true
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val b = StringBuilder()
                fun line(s: String) { b.appendLine(s) }

                val ext = android.os.Environment.getExternalStorageDirectory()
                line("extRoot: ${ext.absolutePath}")

                val androidData = java.io.File(ext, "Android/data")
                val entries = androidData.list()?.toList() ?: emptyList()
                line("File(Android/data): ${entries.size} entries" +
                    (if (entries.isNotEmpty()) " e.g. ${entries.take(4)}" else " (EMPTY/UNREADABLE)"))

                val wa = java.io.File(androidData, "com.whatsapp")
                val waEntries = wa.list()?.toList() ?: emptyList()
                line("File(Android/data/com.whatsapp): ${waEntries.size} -> $waEntries")

                val waCache = java.io.File(wa, "cache")
                val waCacheEntries = waCache.list()?.toList() ?: emptyList()
                line("File(.../com.whatsapp/cache): ${waCacheEntries.size} entries, " +
                    "exists=${waCache.exists()} isDir=${waCache.isDirectory}")

                val sdcardAlias = java.io.File("/sdcard/Android/data")
                line("File(/sdcard/Android/data): ${sdcardAlias.list()?.size ?: -1} entries")

                line("pm installed: ${try {
                    @Suppress("DEPRECATION")
                    packageManager.getInstalledPackages(0).size
                } catch (e: Exception) { "ERR ${e.message}" }}")

                line("--- su ---")
                try {
                    val id = shell.exec("id -u", timeoutMs = 8_000)
                    line("su id -u: exit=${id.exitCode} out=${id.stdout.trim()}")
                    val ls = shell.exec("ls -1 /data/data | wc -l", timeoutMs = 8_000)
                    line("su ls /data/data | wc -l: exit=${ls.exitCode} out=${ls.stdout.trim()}")
                    val media = shell.exec("ls -1 /data/media/0/Android/data 2>/dev/null | wc -l", timeoutMs = 8_000)
                    line("su ls /data/media/0/Android/data: exit=${media.exitCode} out=${media.stdout.trim()}")
                    val waSu = shell.exec("du -sk /data/data/com.whatsapp/cache 2>/dev/null; echo RC=\$?", timeoutMs = 15_000)
                    line("su du whatsapp private cache: out=${waSu.stdout.trim().take(120)}")
                    val waPub = shell.exec("du -sk /data/media/0/Android/data/com.whatsapp 2>/dev/null; echo RC=\$?", timeoutMs = 15_000)
                    line("su du whatsapp public: out=${waPub.stdout.trim().take(120)}")
                    val glob = shell.exec(
                        "for d in /data/data/*/cache; do [ -d \"\$d\" ] && echo G \"\$d\"; done | wc -l",
                        timeoutMs = 15_000
                    )
                    line("su cache globs: exit=${glob.exitCode} out=${glob.stdout.trim()}")
                } catch (e: Exception) {
                    line("su FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }
                b.toString()
            }
            busy = false
            AlertDialog.Builder(this@TroveCleanerActivity)
                .setTitle("Storage diagnostics")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---- automation ----------------------------------------------------------

    /** Accessibility-automation clearing for selected targets (no root needed). */
    private fun runAutomation(list: ListView, summary: TextView) {
        val positions = list.checkedItemPositions
        val selected = (0 until positions.size()).filter { positions.valueAt(it) }
            .mapNotNull { cacheTargets.getOrNull(it) }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = TroveAutomationService.start(selected.map { it.packageName }) { progress ->
            runOnUiThread {
                when (progress.stage) {
                    TroveAutomationService.Progress.Stage.STARTED ->
                        summary.text = "Automation: opening ${progress.packageName}…"
                    TroveAutomationService.Progress.Stage.CLEARED ->
                        summary.text = "Automation: cleared ${progress.packageName}"
                    TroveAutomationService.Progress.Stage.SKIPPED ->
                        summary.text = "Automation: skipped ${progress.packageName} (${progress.detail})"
                    TroveAutomationService.Progress.Stage.FINISHED -> {
                        summary.text = "Automation finished"
                        automationStatus.text = automationStatusText()
                    }
                }
            }
        }
        if (!ok) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility service needed")
                .setMessage("Enable \"Trove App Cleaner automation\" in accessibility settings, then retry.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
