package com.jarvis.assistant.accessibility

import android.content.Intent
import com.jarvis.assistant.security.AppLockManager
import com.jarvis.assistant.security.LockScreenActivity

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class JarvisAccessibilityService : AccessibilityService() {

    private val appLockManager by lazy { AppLockManager(this) }


    private val handler = Handler(Looper.getMainLooper())

    private val sendButtonHints = listOf(
        "send", "भेजें", "com.whatsapp:id/send", "org.telegram.messenger:id/chat_send_button"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return

        // Jarvis app-lock: PIN gate over locked packages
        try {
            if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                val pkg = ev.packageName?.toString()
                if (!pkg.isNullOrBlank()
                    && pkg != packageName
                    && appLockManager.isLocked(pkg)
                    && !appLockManager.isSessionUnlocked(pkg)
                ) {
                    val i = Intent(this, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(LockScreenActivity.EXTRA_PACKAGE, pkg)
                    }
                    startActivity(i)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return "No screen content available."
        val seen = LinkedHashSet<String>()
        collectText(root, seen, 0)
        if (seen.isEmpty()) return "Screen appears empty."
        return seen.joinToString(" ").trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Collect visible text + content descriptions, de-duplicated, depth-limited.
     */
    private fun collectText(node: AccessibilityNodeInfo, seen: MutableSet<String>, depth: Int) {
        if (depth > 28) return
        fun add(s: CharSequence?) {
            val t = s?.toString()?.trim() ?: return
            if (t.length < 2) return
            // Skip pure numeric noise / single symbols
            if (t.length == 1 && !t[0].isLetterOrDigit()) return
            seen.add(t)
        }
        add(node.text)
        add(node.contentDescription)
        // Some apps put primary label in hint
        try { add(node.hintText) } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { collectText(it, seen, depth + 1) }
            } catch (_: Exception) { }
        }
    }

    /** Scroll the active app's accessibility tree. Returns false when the screen cannot scroll. */
    fun scrollScreen(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                     else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return try {
            if (root.performAction(action)) return true
            findScrollable(root)?.performAction(action) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    /** Click a visible, non-sensitive label only when the user explicitly names it. */
    fun clickVisibleText(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 2) return false
        val root = rootInActiveWindow ?: return false
        val node = findTextNode(root, q, 0) ?: return false
        var candidate: AccessibilityNodeInfo? = node
        var depth = 0
        while (candidate != null && depth < 8) {
            if (candidate.isClickable) {
                return try { candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            }
            candidate = try { candidate.parent } catch (_: Exception) { null }
            depth++
        }
        return false
    }

    private fun findTextNode(node: AccessibilityNodeInfo, query: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 28 || node.isPassword) return null
        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").lowercase().trim()
        if (label.isNotBlank() && (label == query || label.contains(query))) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            findTextNode(child, query, depth + 1)?.let { return it }
        }
        return null
    }

    fun autoTapSend(attemptsLeft: Int = 6) {
        if (attemptsLeft <= 0) {
            Log.w("JarvisAccessibility", "autoTapSend: gave up, no Send button found")
            return
        }
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                autoTapSend(attemptsLeft - 1)
                return@postDelayed
            }
            val target = findSendButton(root)
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("JarvisAccessibility", "autoTapSend: tapped send button")
            } else {
                autoTapSend(attemptsLeft - 1)
            }
        }, 700)
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (sendButtonHints.any { desc.contains(it, ignoreCase = true) || id.contains(it, ignoreCase = true) }) {
            if (node.isClickable) return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findSendButton(child)?.let { return it }
            }
        }
        return null
    }

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
