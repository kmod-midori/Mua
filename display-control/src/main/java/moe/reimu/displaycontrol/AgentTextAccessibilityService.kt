package moe.reimu.displaycontrol

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Resolves the focused editor from the system on each call; never reads or changes the clipboard. */
class AgentTextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() = synchronized(Companion) {
        if (sessionOwner != null) instance = this else disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        synchronized(Companion) { if (instance === this) instance = null }
        super.onDestroy()
    }

    companion object {
        private var sessionOwner: Any? = null
        private var instance: AgentTextAccessibilityService? = null

        @Synchronized
        internal fun setActive(owner: Any, active: Boolean) {
            if (!active && sessionOwner != null && sessionOwner !== owner) return
            sessionOwner = if (active) owner else null
            if (!active) {
                val old = instance
                instance = null
                runCatching { old?.disableSelf() }
                    .onFailure { android.util.Log.w("AccessibilityAccess", "Could not disable text service", it) }
            }
        }

        fun setText(displayId: Int, text: String) {
            val result = withFocusedNode(displayId) { focused ->
                check(focused.isEditable) { "The focused field is not editable" }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                check(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    "The focused field does not support setting text"
                }
                true
            }
            check(result == true) { "No focused editable field on display $displayId" }
        }

        fun focusedField(displayId: Int) = FocusedFieldResult(displayId, withFocusedNode(displayId) { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            FocusedFieldInfo(
                node.packageName?.toString(), node.className?.toString(), node.viewIdResourceName,
                FieldBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                node.isFocused, node.isEditable, node.isEnabled, node.isVisibleToUser, node.isPassword,
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
            )
        })

        private fun <T> withFocusedNode(displayId: Int, action: (AccessibilityNodeInfo) -> T): T? {
            require(displayId >= 0) { "Display ID must be nonnegative" }
            val service = synchronized(this) {
                check(sessionOwner != null) { "Accessibility text input is inactive" }
                checkNotNull(instance) { "Accessibility service is connecting; retry shortly" }
            }
            val windowsByDisplay = service.windowsOnAllDisplays
            try {
                for (window in windowsByDisplay[displayId].orEmpty()) {
                    val root = window.root ?: continue
                    try {
                        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                        try {
                            if (!focused.refresh() || !focused.isFocused) continue
                            return action(focused)
                        } finally {
                            @Suppress("DEPRECATION")
                            focused.recycle()
                        }
                    } finally {
                        @Suppress("DEPRECATION")
                        root.recycle()
                    }
                }
                return null
            } finally {
                for (index in 0 until windowsByDisplay.size()) {
                    @Suppress("DEPRECATION")
                    windowsByDisplay.valueAt(index).forEach { it.recycle() }
                }
            }
        }
    }
}
