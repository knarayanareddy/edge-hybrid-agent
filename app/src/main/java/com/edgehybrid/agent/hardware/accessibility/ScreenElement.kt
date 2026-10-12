package com.edgehybrid.agent.hardware.accessibility

import android.graphics.Rect
import kotlinx.serialization.Serializable

@Serializable
data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerX: Float = (left + right) / 2f,
    val centerY: Float = (top + bottom) / 2f
) {
    companion object {
        fun fromRect(rect: Rect): ScreenBounds = ScreenBounds(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            centerX = rect.exactCenterX(),
            centerY = rect.exactCenterY()
        )
    }
}

@Serializable
data class ScreenElement(
    val id: Int,
    val className: String,
    val text: String,
    val contentDescription: String? = null,
    val bounds: ScreenBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val viewId: String? = null
)
