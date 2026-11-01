package com.navon.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The "eyes and hands" of Navon.
 *
 * Reads the on-screen view tree (every button, label and text field the
 * current app exposes) and performs taps, typing, swipes and system-button
 * presses on the user's behalf. One live instance is kept in [instance] so the
 * agent loop (AgentService) can drive it.
 */
class NavonAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NavonAccessibilityService? = null
            private set

        /** True when the user has enabled the service in Accessibility settings. */
        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }
    override fun onInterrupt() {}

    // ---------------------------------------------------------------------
    // Reading the screen
    // ---------------------------------------------------------------------

    private data class Element(
        val index: Int,
        val text: String,
        val clickable: Boolean,
        val editable: Boolean,
        val bounds: Rect
    )

    private val lastElements = ArrayList<Element>()

    /**
     * Produces a compact, numbered description of the interactive / textual
     * elements visible right now. This is what gets sent to Claude so it can
     * decide what to tap.
     */
    fun dumpScreen(): String {
        lastElements.clear()
        val root = rootInActiveWindow ?: return "המסך ריק או לא נגיש כרגע."
        val pkg = root.packageName?.toString() ?: "?"
        val collected = ArrayList<Element>()
        collect(root, collected)

        val sb = StringBuilder()
        sb.append("אפליקציה נוכחית: ").append(pkg).append("\n")
        sb.append("אלמנטים על המסך:\n")
        if (collected.isEmpty()) {
            sb.append("(לא נמצאו אלמנטים עם טקסט)\n")
        }
        for (e in collected) {
            lastElements.add(e)
            val tags = buildString {
                if (e.clickable) append(" [ניתן ללחיצה]")
                if (e.editable) append(" [שדה טקסט]")
            }
            sb.append("[").append(e.index).append("] \"")
                .append(e.text.take(120)).append("\"").append(tags).append("\n")
        }
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<Element>) {
        if (node == null) return
        if (out.size >= 150) return
        if (node.isVisibleToUser) {
            val raw = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = when {
                !raw.isNullOrEmpty() -> raw
                !desc.isNullOrEmpty() -> desc
                else -> null
            }
            val editable = node.isEditable
            if (label != null || node.isClickable || editable) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0) {
                    out.add(
                        Element(
                            index = out.size,
                            text = label ?: "(ללא טקסט)",
                            clickable = node.isClickable,
                            editable = editable,
                            bounds = b
                        )
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out)
        }
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    /** Tap the first visible element whose text/description contains [query]. */
    fun tapByText(query: String): String {
        val root = rootInActiveWindow ?: return "אין מסך פעיל."
        val q = query.trim().lowercase()
        val match = findNode(root) { n ->
            val t = (n.text?.toString() ?: "").lowercase()
            val d = (n.contentDescription?.toString() ?: "").lowercase()
            (t.contains(q) || d.contains(q)) && n.isVisibleToUser
        } ?: return "לא נמצא אלמנט עם הטקסט \"$query\"."

        // Climb to a clickable ancestor if the matched node itself isn't clickable.
        var target: AccessibilityNodeInfo? = match
        while (target != null && !target.isClickable) target = target.parent
        return if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "לחצתי על \"$query\"."
        } else {
            val b = Rect(); match.getBoundsInScreen(b)
            tapAt(b.exactCenterX(), b.exactCenterY())
        }
    }

    /** Tap an element by its [index] from the last [dumpScreen]. */
    fun tapByIndex(index: Int): String {
        val e = lastElements.getOrNull(index) ?: return "אין אלמנט במספר $index."
        return tapAt(e.bounds.exactCenterX(), e.bounds.exactCenterY())
    }

    fun tapAt(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
        return "לחצתי על המסך בנקודה (${x.toInt()}, ${y.toInt()})."
    }

    /** Type [text] into the focused editable field (or the first one found). */
    fun inputText(text: String): String {
        val root = rootInActiveWindow ?: return "אין מסך פעיל."
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNode(root) { it.isEditable && it.isVisibleToUser }
            ?: return "לא נמצא שדה טקסט להקלדה."
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "הקלדתי: \"$text\"."
        } else {
            "ההקלדה נכשלה."
        }
    }

    fun swipe(direction: String): String {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        when (direction.lowercase()) {
            "up", "למעלה"   -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "down", "למטה"  -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left", "שמאלה" -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            "right", "ימינה"-> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            else -> return "כיוון לא מוכר: $direction"
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
        return "גללתי $direction."
    }

    fun pressButton(button: String): String {
        val action = when (button.lowercase()) {
            "back", "חזור"         -> GLOBAL_ACTION_BACK
            "home", "בית"          -> GLOBAL_ACTION_HOME
            "recents", "אחרונים"   -> GLOBAL_ACTION_RECENTS
            "notifications", "התראות" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "כפתור לא מוכר: $button"
        }
        performGlobalAction(action)
        return "לחצתי על כפתור $button."
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
