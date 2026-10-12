package com.edgehybrid.agent.hardware.accessibility

import android.graphics.Rect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAccessibilityServiceTest {

    @Test
    fun `ScreenBounds calculates center coordinates accurately`() {
        val bounds = ScreenBounds(left = 100, top = 200, right = 500, bottom = 600)
        assertEquals(300f, bounds.centerX, 0.001f)
        assertEquals(400f, bounds.centerY, 0.001f)
    }

    @Test
    fun `ScreenElement serializes and deserializes correctly via kotlinx serialization`() {
        val element = ScreenElement(
            id = 42,
            className = "android.widget.Button",
            text = "Submit Order",
            contentDescription = "Submit the current shopping cart",
            bounds = ScreenBounds(left = 50, top = 100, right = 450, bottom = 200),
            isClickable = true,
            isEditable = false,
            viewId = "com.example.app:id/btn_submit"
        )

        val jsonString = Json.encodeToString(ScreenElement.serializer(), element)
        val decoded = Json.decodeFromString(ScreenElement.serializer(), jsonString)

        assertEquals(42, decoded.id)
        assertEquals("Submit Order", decoded.text)
        assertTrue(decoded.isClickable)
        assertFalse(decoded.isEditable)
        assertEquals(250f, decoded.bounds.centerX, 0.001f)
        assertEquals(150f, decoded.bounds.centerY, 0.001f)
    }

    @Test
    fun `service isConnected returns false when service is not running`() {
        assertFalse(EdgeAccessibilityService.isConnected())
    }
}
