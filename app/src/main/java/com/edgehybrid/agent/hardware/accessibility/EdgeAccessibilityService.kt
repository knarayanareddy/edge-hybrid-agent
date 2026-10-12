package com.edgehybrid.agent.hardware.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Mobile equivalent of macOS / Desktop agent computer use (replicating ChatGPT @oai/sky).
 *
 * Runs as an Android AccessibilityService, allowing the agent to observe UI trees across
 * any installed Android app and execute clicks, text inputs, swipes, and system actions.
 */
class EdgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EdgeAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val cachedNodes = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        clearCachedNodes()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        clearCachedNodes()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event stream available for reactive triggers if needed
    }

    override fun onInterrupt() {
        clearCachedNodes()
    }

    private fun clearCachedNodes() {
        cachedNodes.clear()
    }

    /**
     * Traverses the active window and returns a flat list of visible and interactive UI elements.
     */
    fun inspectScreen(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        clearCachedNodes()
        val elements = mutableListOf<ScreenElement>()
        traverseNode(root, elements)
        return elements
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<ScreenElement>) {
        if (node.isVisibleToUser) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isCheckable = node.isCheckable

            // Keep nodes that either have visible text/description or can receive user input
            if (text.isNotBlank() || desc.isNotBlank() || isClickable || isEditable || isCheckable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // Only include elements with valid on-screen dimensions
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val id = list.size + 1
                    cachedNodes[id] = node

                    list.add(
                        ScreenElement(
                            id = id,
                            className = node.className?.toString() ?: "",
                            text = text,
                            contentDescription = desc.takeIf { it.isNotBlank() },
                            bounds = ScreenBounds.fromRect(bounds),
                            isClickable = isClickable,
                            isEditable = isEditable,
                            isCheckable = isCheckable,
                            isChecked = node.isChecked,
                            viewId = node.viewIdResourceName
                        )
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNode(it, list) }
        }
    }

    /**
     * Clicks a specific UI element by its index ID from the last [inspectScreen] call.
     */
    fun clickElement(id: Int): Boolean {
        val node = cachedNodes[id] ?: return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // If node itself is not clickable, check parent containers
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }

        // Fallback: click element's center coordinates via gesture dispatch
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return clickCoordinatesSync(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /**
     * Sets text on an editable element by ID, or the currently focused input if ID is omitted.
     */
    fun typeText(text: String, id: Int? = null): Boolean {
        val targetNode = if (id != null) {
            cachedNodes[id]
        } else {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Dispatches a tap gesture to exact screen pixel coordinates (x, y).
     */
    suspend fun clickCoordinates(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    private fun clickCoordinatesSync(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Swipes from start coordinates to end coordinates over [durationMs].
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Triggers standard Android system buttons (Back, Home, Recents, Notifications).
     */
    fun performSystemAction(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "take_screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }
}
