package com.trove.feature.cleaner.appcleaner.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility automation for AppCleaner without root.
 *
 * Given a queue of package names it walks the system Settings screens —
 * App info → Storage & cache → Clear cache — clicking only buttons the
 * planner (see [AutomationPlanner]) identified inside com.android.settings.
 * It never touches any other app's UI and stops itself when the queue is
 * empty, a package times out, or Trove asks it to stop.
 */
class TroveAutomationService : AccessibilityService() {

    private val tag = "TroveAutomation"

    data class Progress(val packageName: String?, val stage: Stage, val detail: String? = null) {
        enum class Stage { STARTED, CLEARED, SKIPPED, FINISHED }
    }

    companion object {
        @Volatile private var instance: TroveAutomationService? = null

        /** Packages still to process; null = automation idle. */
        @Volatile private var queue: ArrayDeque<String>? = null
        @Volatile var listener: ((Progress) -> Unit)? = null
            private set

        val isRunning: Boolean get() = queue != null
        val isReady: Boolean get() = instance != null

        /**
         * Queue packages for clearing. Returns false when the service isn't
         * enabled by the system yet — direct the user to accessibility
         * settings first.
         */
        fun start(packages: List<String>, progressListener: (Progress) -> Unit): Boolean {
            val svc = instance ?: return false
            queue = ArrayDeque(packages)
            listener = progressListener
            svc.currentPackage = null
            svc.attempts = 0
            svc.onStorageScreen = false
            svc.handler.post { svc.startNextPackage() }
            return true
        }

        fun stop() {
            queue = null
            listener?.invoke(Progress(null, Progress.Stage.FINISHED, "stopped"))
            listener = null
            instance?.handler?.post { instance?.advanceToNextPackage() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage: String? = null
    private var attempts = 0
    private var onStorageScreen = false
    private var lastActionAt = 0L
    private var pollRunnable: Runnable? = null

    /**
     * Window root recovered from the last event's source node. On some
     * GSI/OEM builds [rootInActiveWindow] stays null forever (the service
     * receives events fine — the active-window lookup just never resolves),
     * so the event path is the reliable way in.
     */
    private var lastEventRoot: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Belt and braces: re-assert the subscription the XML declares. Some
        // OEM/GSI builds have been observed binding accessibility services
        // with eventTypes=0 (connects fine, delivers nothing).
        val info = serviceInfo
        android.util.Log.i(
            tag,
            "connected: eventTypes=${info.eventTypes} feedback=${info.feedbackType} " +
                "canRetrieve=${info.canRetrieveWindowContent} caps=${info.capabilities}"
        )
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info
        android.util.Log.i(
            tag,
            "serviceInfo reasserted: eventTypes=${serviceInfo.eventTypes} feedback=${serviceInfo.feedbackType}"
        )
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() { }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // LIVENESS: the queue empties as soon as the LAST package is popped
        // for processing — "queue empty" ≠ idle. currentPackage is the
        // authoritative "a clean is in flight" signal. (This gate previously
        // swallowed every Settings event when a single package was queued.)
        if (event == null || currentPackage == null) return
        // Only the Settings app is in scope — our own windows and every
        // other app's are ignored outright.
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return

        // Capture the window root from this event's node — the fallback the
        // GSI path depends on (see [lastEventRoot]).
        try {
            val src = event.source
            if (src != null) lastEventRoot = src.window?.root
        } catch (_: Exception) { }

        actOnCurrentWindow()
    }

    // ---- state machine -------------------------------------------------------

    private fun startNextPackage() {
        val q = queue ?: return
        val next = q.removeFirstOrNull()
        if (next == null) {
            listener?.invoke(Progress(null, Progress.Stage.FINISHED))
            // Leave Settings cleanly: bring Trove back to the foreground.
            try {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) startActivity(launch)
            } catch (_: Exception) { }
            stopInternal()
            return
        }
        currentPackage = next
        attempts = 0
        onStorageScreen = false
        lastEventRoot = null
        listener?.invoke(Progress(next, Progress.Stage.STARTED))
        openAppInfo(next)
        armWatchdog()
        // Screens can be static after the first settle — don't rely on
        // Settings to keep firing events; poll instead.
        schedulePoll()
    }

    private fun advanceToNextPackage() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        currentPackage = null
        if (queue != null) startNextPackage()
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            reportSkipped(packageName, "app info screen unavailable")
        }
    }

    private fun actOnCurrentWindow() {
        val pkg = currentPackage ?: return
        // Primary: the active window root. Fallback: the root captured from
        // the last Settings event's source node (GSI builds where the active
        // window lookup never resolves).
        var root = rootInActiveWindow ?: lastEventRoot
        if (root == null) {
            android.util.Log.i(tag, "act: no window root yet (activeWindow=null, no event root)")
            return // window not up yet — poll will retry
        }
        // Settings fires content-changed storms; don't re-evaluate faster
        // than a click can settle, or attempts burn out on one screen.
        if (System.currentTimeMillis() - lastActionAt < 700) return
        val pairs = collectNodes(root, limit = 600)
        val plannerNodes = pairs.map { it.first }

        val decision = AutomationPlanner.decide(plannerNodes, onStorageScreen, attempts)
        attempts++
        lastActionAt = System.currentTimeMillis()
        android.util.Log.i(
            tag,
            "act pkg=$pkg nodes=${pairs.size} decision=${decision.action} attempts=$attempts " +
                "storage=$onStorageScreen rootVia=${if (rootInActiveWindow != null) "active" else "event"}"
        )

        when (decision.action) {
            AutomationPlanner.Action.CLICK_STORAGE_ROW -> {
                // Click the LIVE node captured in the same traversal —
                // re-resolving by a second walk can silently miss.
                val live = pairs.getOrNull(decision.targetIndex)?.second
                val clicked = clickNodeOrAncestor(live)
                android.util.Log.i(tag, "click storage row resolved=${live != null} clicked=$clicked")
                if (clicked) onStorageScreen = true
                schedulePoll()
            }
            AutomationPlanner.Action.CLICK_CLEAR_CACHE -> {
                val live = pairs.getOrNull(decision.targetIndex)?.second
                val clicked = clickNodeOrAncestor(live)
                android.util.Log.i(tag, "click clear cache resolved=${live != null} clicked=$clicked")
                // Give the click a moment to take effect; the next poll then
                // re-checks (a disabled button = DONE).
                schedulePoll()
            }
            AutomationPlanner.Action.DONE -> {
                android.util.Log.i(tag, "cleared $pkg")
                listener?.invoke(Progress(pkg, Progress.Stage.CLEARED))
                advanceToNextPackage()
            }
            AutomationPlanner.Action.GIVE_UP -> {
                reportSkipped(pkg, "no Clear cache button found")
            }
            AutomationPlanner.Action.WAIT -> schedulePoll()
        }
    }

    /**
     * Re-evaluate shortly even without new events: after the first click the
     * screen is static and Settings stops firing, so event-only evaluation
     * starves. Attempts are capped by [AutomationPlanner.MAX_ATTEMPTS].
     */
    private fun schedulePoll() {
        if (currentPackage == null) return // idle — nothing to poll
        pollRunnable?.let { handler.removeCallbacks(it) }
        val poll = Runnable {
            android.util.Log.i(tag, "poll firing, current=$currentPackage")
            actOnCurrentWindow()
        }
        pollRunnable = poll
        handler.postDelayed(poll, 900)
    }

    private fun reportSkipped(pkg: String, reason: String) {
        android.util.Log.w(tag, "skipped $pkg: $reason")
        listener?.invoke(Progress(pkg, Progress.Stage.SKIPPED, reason))
        handler.postDelayed({ advanceToNextPackage() }, 250)
    }

    /** No window events for a while → assume the screen didn't come up. */
    private fun armWatchdog() {
        val pkg = currentPackage ?: return
        handler.postDelayed({
            val q = queue ?: return@postDelayed
            if (currentPackage == pkg && System.currentTimeMillis() - lastActionAt > 10_000) {
                reportSkipped(pkg, "timeout waiting for Settings")
            }
        }, 11_000)
    }

    private fun stopInternal() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        queue = null
        listener = null
    }

    // ---- node helpers --------------------------------------------------------

    /**
     * Flatten the window tree into (planner node, live node) pairs so a
     * decision can act on the exact node it was made from.
     */
    private fun collectNodes(
        root: AccessibilityNodeInfo,
        limit: Int
    ): List<Pair<AutomationPlanner.Node, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<AutomationPlanner.Node, AccessibilityNodeInfo>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && out.size < limit && seen < limit * 3) {
            seen++
            val node = stack.removeFirst()
            out += AutomationPlanner.Node(
                text = node.text?.toString(),
                viewIdResourceName = node.viewIdResourceName,
                clickable = node.isClickable,
                enabled = node.isEnabled
            ) to node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }

    /** Rows expose text on a child TextView; the clickable parent is the hit target. */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        var hops = 0
        while (hops < 6) {
            if (current.isClickable) return current.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
            current = current.parent ?: break
            hops++
        }
        return false
    }
}
