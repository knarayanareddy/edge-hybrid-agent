package com.edgehybrid.agent.agent

import com.edgehybrid.agent.nativeactions.ActionRiskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tiering rules for the device and settings tools.
 *
 * The security-critical property is that a new tool is gated unless it is explicitly
 * allowlisted as read-only, so these assertions guard the allowlist itself as well as the
 * individual tools.
 */
class SystemToolTieringTest {

    private val policy = ToolConfirmationPolicy()

    private val readOnlyDeviceTools = listOf(
        "get_battery_status",
        "get_storage_status",
        "get_memory_status",
        "get_display_settings",
        "get_media_volume",
        "get_network_status",
        "get_camera_hardware",
        "get_device_details",
        "get_samsung_capabilities"
    )

    private val gatedDeviceTools = listOf(
        "open_display_settings",
        "open_battery_settings",
        "open_modes_and_routines",
        "open_permission_manager",
        "open_notification_settings",
        "open_spen_settings",
        "open_security_settings",
        "open_wifi_settings",
        "open_storage_settings",
        "open_app_settings",
        "set_media_volume",
        "launch_app",
        "open_gallery",
        "share_text"
    )

    @Test
    fun `device queries are auto-approved`() {
        readOnlyDeviceTools.forEach { tool ->
            assertTrue("$tool should be auto-approved", policy.isAutoApproved(tool))
        }
    }

    @Test
    fun `device settings handoffs require confirmation`() {
        gatedDeviceTools.forEach { tool ->
            assertFalse("$tool must require confirmation", policy.isAutoApproved(tool))
        }
    }

    @Test
    fun `settings handoffs are CONFIRM not CONFIRM_STRICT`() {
        // They open a system screen rather than contacting a third party, so they do not
        // warrant the stricter "check the recipient and payload" dialog.
        listOf(
            "open_display_settings",
            "open_battery_settings",
            "open_spen_settings",
            "open_wifi_settings"
        ).forEach { tool ->
            assertEquals(
                "$tool tier",
                ActionRiskTier.CONFIRM,
                policy.tierFor(tool)
            )
        }
    }

    @Test
    fun `external sends remain strict and are unaffected by the new tools`() {
        listOf("send_sms", "send_telegram_message", "draft_email", "initiate_phone_call")
            .forEach { tool ->
                assertEquals(tool, ActionRiskTier.CONFIRM_STRICT, policy.tierFor(tool))
            }
    }

    @Test
    fun `an unrecognised future tool still fails closed`() {
        assertFalse(policy.isAutoApproved("some_tool_added_in_2099"))
        assertEquals(ActionRiskTier.CONFIRM, policy.tierFor("some_tool_added_in_2099"))
    }

    @Test
    fun `gated tools never appear in the auto-approve set by accident`() {
        val overlap = gatedDeviceTools intersect setOf(
            "get_battery_status", "get_storage_status", "get_memory_status",
            "get_display_settings", "get_media_volume", "get_network_status",
            "get_camera_hardware", "list_installed_apps", "get_device_details",
            "get_samsung_capabilities"
        )
        assertTrue("tools must not be both read-only and gated: $overlap", overlap.isEmpty())
    }
}