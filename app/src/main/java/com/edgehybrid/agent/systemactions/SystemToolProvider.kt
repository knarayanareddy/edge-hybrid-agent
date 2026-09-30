package com.edgehybrid.agent.systemactions

import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.tool.SkillExecutionException
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Device and settings tools built on [SystemActionHandler] and [SamsungActions].
 *
 * Kept separate from the general web/knowledge skills because these touch the phone the
 * user is holding. Each tool is either:
 *  - a **query**, returning current state to the model, or
 *  - a **settings handoff**, opening a system screen so the user makes the change.
 *
 * There is deliberately no tool here that writes a protected system setting, starts a
 * screen recording, or reads a location fix. Those either need a signature-level
 * permission or are a privacy intrusion the agent should not make unprompted.
 *
 * Risk tiering lives in [com.edgehybrid.agent.agent.ToolConfirmationPolicy]: everything
 * here that changes something is `CONFIRM`, and the pure queries are `AUTO_APPROVE`.
 */
@Singleton
class SystemToolProvider @Inject constructor(
    private val systemActions: SystemActionHandler,
    private val samsungActions: SamsungActions
) {

    /** Tool name constants, kept in one block so they cannot drift. */
    object Names {
        // ---- queries ----
        const val GET_BATTERY = "get_battery_status"
        const val GET_STORAGE = "get_storage_status"
        const val GET_MEMORY = "get_memory_status"
        const val GET_DISPLAY = "get_display_settings"
        const val GET_VOLUME = "get_media_volume"
        const val GET_CONNECTIVITY = "get_network_status"
        const val GET_CAMERA_HARDWARE = "get_camera_hardware"
        const val LIST_APPS = "list_installed_apps"
        const val GET_DEVICE_DETAILS = "get_device_details"
        const val GET_SAMSUNG_CAPABILITIES = "get_samsung_capabilities"

        // ---- settings handoffs ----
        const val OPEN_DISPLAY_SETTINGS = "open_display_settings"
        const val OPEN_BATTERY_SETTINGS = "open_battery_settings"
        const val OPEN_MODES_ROUTINES = "open_modes_and_routines"
        const val OPEN_PERMISSION_MANAGER = "open_permission_manager"
        const val OPEN_NOTIFICATION_SETTINGS = "open_notification_settings"
        const val OPEN_SPEN_SETTINGS = "open_spen_settings"
        const val OPEN_APP_SETTINGS = "open_app_settings"
        const val OPEN_WIFI_SETTINGS = "open_wifi_settings"
        const val OPEN_SECURITY_SETTINGS = "open_security_settings"
        const val OPEN_STORAGE_SETTINGS = "open_storage_settings"

        // ---- simple actions ----
        const val SET_MEDIA_VOLUME = "set_media_volume"
        const val LAUNCH_APP = "launch_app"
        const val OPEN_GALLERY = "open_gallery"
        const val SHARE_TEXT = "share_text"
    }

    /** Every tool this provider contributes, in catalog order. */
    fun toolDefinitions(): List<ToolDefinition> = buildList {
        add(query(Names.GET_BATTERY, "Get current battery level, charging state, temperature, and battery health."))
        add(query(Names.GET_STORAGE, "Get free and total storage for internal memory and the SD card."))
        add(query(Names.GET_MEMORY, "Get RAM usage and whether the device is under memory pressure."))
        add(query(Names.GET_DISPLAY, "Get screen brightness, resolution, density, and refresh rate."))
        add(query(Names.GET_VOLUME, "Get the current media playback volume as a percentage."))
        add(query(Names.GET_CONNECTIVITY, "Get the active network type, whether the device is online, and Wi-Fi signal strength."))
        add(query(Names.GET_CAMERA_HARDWARE, "List this phone's cameras with megapixels, flash, autofocus, and RAW support. Useful on the S23 Ultra's 200MP main sensor."))
        add(withArg(Names.LIST_APPS, "List apps installed on this phone that can be launched.", "query", "Partial app name to filter by.", "string", required = false))
        add(query(Names.GET_DEVICE_DETAILS, "Get manufacturer, model, Android version, screen size, and whether this is a Galaxy S23 Ultra."))
        add(query(Names.GET_SAMSUNG_CAPABILITIES, "Report which Samsung-specific features are available on this device: S Pen, Bixby, Secure Folder, Modes and Routines, and more."))

        add(handoff(Names.OPEN_DISPLAY_SETTINGS, "Open display settings to change resolution (QHD), brightness, or motion smoothness."))
        add(handoff(Names.OPEN_BATTERY_SETTINGS, "Open battery settings to review power usage and enable battery protection."))
        add(handoff(Names.OPEN_MODES_ROUTINES, "Open Modes and Routines to set up Focus, Driving, Sleeping, or a custom routine."))
        add(handoff(Names.OPEN_PERMISSION_MANAGER, "Open this app's permission settings so you can grant camera, contacts, or calendar access."))
        add(handoff(Names.OPEN_NOTIFICATION_SETTINGS, "Open notification settings to adjust or silence notifications."))
        add(handoff(Names.OPEN_SPEN_SETTINGS, "Open S Pen settings for gestures, shortcuts, and pen behaviour."))
        add(handoff(Names.OPEN_SECURITY_SETTINGS, "Open security settings for lock screen, biometrics, and device protection."))
        add(handoff(Names.OPEN_WIFI_SETTINGS, "Open Wi-Fi settings to join or forget a network."))
        add(handoff(Names.OPEN_STORAGE_SETTINGS, "Open storage settings to review what is using space."))
        add(
            withArg(
                Names.OPEN_APP_SETTINGS, "Open the system settings page for a specific installed app.",
                "package", "App package name, for example com.spotify.music.", "string", required = true
            )
        )

        add(
            withArg(
                Names.SET_MEDIA_VOLUME, "Set the media playback volume as a percentage from 0 to 100.",
                "level", "Target volume percentage, 0 to 100.", "integer", required = true
            )
        )
        add(
            withArg(
                Names.LAUNCH_APP, "Open an installed app by its package name.",
                "package", "App package name, for example com.spotify.music.", "string", required = true
            )
        )
        add(handoff(Names.OPEN_GALLERY, "Open the photo gallery."))
        add(
            withArg(
                Names.SHARE_TEXT, "Open the Android share sheet with some text.",
                "text", "The text to share.", "string", required = true
            )
        )
    }

    /** Tool names this provider can execute. */
    fun toolNames(): Set<String> = toolDefinitions().map { it.function.name }.toSet()

    /**
     * Executes a tool from this provider.
     *
     * @return null when [name] is not one of ours, so the caller can try other providers.
     */
    fun execute(name: String, arguments: JsonObject): ToolExecutionOutcome? {
        if (name !in toolNames()) return null

        return when (name) {
            Names.GET_BATTERY -> ok("battery", systemActions.batteryStatus().toString())
            Names.GET_STORAGE -> ok("storage", systemActions.storageInfo().toString())
            Names.GET_MEMORY -> ok("memory", systemActions.memoryInfo().toString())
            Names.GET_DISPLAY -> executeGetDisplay()
            Names.GET_VOLUME -> ok(
                "volume",
                buildJsonObject {
                    put("volume_percent", systemActions.mediaVolumePercent())
                }.toString()
            )
            Names.GET_CONNECTIVITY -> ok("connectivity", systemActions.connectivityStatus().toString())
            Names.GET_CAMERA_HARDWARE -> {
                val cameras = systemActions.cameraHardware()
                ok(
                    "cameras",
                    buildJsonObject {
                        // org.json array is already valid JSON; embed it as raw text.
                        put("cameras_raw", cameras.toString())
                        put("count", cameras.length())
                        put("is_galaxy_s23_ultra", samsungActions.isS23Ultra())
                    }.toString()
                )
            }

            Names.LIST_APPS -> executeListApps(arguments)
            Names.GET_DEVICE_DETAILS -> ok(
                "device",
                buildJsonObject {
                    put("device_raw", systemActions.deviceInfo().toString())
                    put("location_permission", systemActions.locationPermissionState())
                }.toString()
            )

            Names.GET_SAMSUNG_CAPABILITIES -> executeSamsungCapabilities()

            Names.OPEN_DISPLAY_SETTINGS -> opened(samsungActions.openDisplaySettings(), "display settings")
            Names.OPEN_BATTERY_SETTINGS -> opened(samsungActions.openBatterySettings(), "battery settings")
            Names.OPEN_MODES_ROUTINES -> opened(samsungActions.openModesAndRoutines(), "Modes and Routines")
            Names.OPEN_PERMISSION_MANAGER -> opened(
                samsungActions.openPermissionManager(), "permission settings"
            )
            Names.OPEN_NOTIFICATION_SETTINGS -> opened(
                samsungActions.openNotificationSettings(), "notification settings"
            )
            Names.OPEN_SPEN_SETTINGS -> opened(samsungActions.openSPenSettings(), "S Pen settings")
            Names.OPEN_SECURITY_SETTINGS -> opened(
                samsungActions.openSecuritySettings(), "security settings"
            )
            Names.OPEN_WIFI_SETTINGS -> opened(samsungActions.openWifiSettings(), "Wi-Fi settings")
            Names.OPEN_STORAGE_SETTINGS -> opened(
                systemActions.openSettingsScreen(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                "storage settings"
            )

            Names.OPEN_APP_SETTINGS -> {
                val pkg = arguments.str("package")
                    ?: throw SkillExecutionException("App package name is required")
                val success = systemActions.openAppInfo(pkg)
                buildJsonObject {
                    put("package", pkg)
                    put("settings_opened", success)
                    put(
                        "status",
                        if (success) "Opened settings for $pkg"
                        else "Could not open settings for $pkg"
                    )
                }.let { ToolExecutionOutcome(it.toString(), !success) }
            }

            Names.SET_MEDIA_VOLUME -> {
                val level = arguments["level"]?.jsonPrimitive?.intOrNull
                    ?: throw SkillExecutionException("Volume level is required")
                val clamped = level.coerceIn(0, 100)
                val success = systemActions.setMediaVolumePercent(clamped)
                buildJsonObject {
                    put("volume_percent", clamped)
                    put("applied", success)
                    put(
                        "status",
                        if (success) "Media volume set to $clamped%"
                        else "Could not change the media volume"
                    )
                }.let { ToolExecutionOutcome(it.toString(), !success) }
            }

            Names.LAUNCH_APP -> {
                val pkg = arguments.str("package")
                    ?: throw SkillExecutionException("App package name is required")
                val success = samsungActions.launchApp(pkg)
                buildJsonObject {
                    put("package", pkg)
                    put("launched", success)
                    put(
                        "status",
                        if (success) "Opened $pkg"
                        else "Could not open $pkg — it may not be installed or may have no launcher"
                    )
                }.let { ToolExecutionOutcome(it.toString(), !success) }
            }

            Names.OPEN_GALLERY -> opened(samsungActions.openGallery(), "gallery")
            Names.SHARE_TEXT -> {
                val text = arguments.str("text")
                    ?: throw SkillExecutionException("Text to share is required")
                val success = samsungActions.shareText(text)
                buildJsonObject {
                    put("share_sheet_opened", success)
                    put("status", if (success) "Share sheet is open" else "Could not open the share sheet")
                }.let { ToolExecutionOutcome(it.toString(), !success) }
            }

            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun executeGetDisplay(): ToolExecutionOutcome {
        val info = systemActions.deviceInfo()
        return ok(
            "display",
            buildJsonObject {
                put("width_px", info.getInt("screen_width_px"))
                put("height_px", info.getInt("screen_height_px"))
                put("density_dpi", info.getInt("density_dpi"))
                put("refresh_rate_hz", info.getInt("refresh_rate_hz"))
                put("brightness_percent", systemActions.screenBrightnessPercent())
                put(
                    "note",
                    "Resolution and refresh rate are read-only; use open_display_settings to change them."
                )
            }.toString()
        )
    }

    private fun executeListApps(arguments: JsonObject): ToolExecutionOutcome {
        val query = arguments.str("query")?.trim()?.takeIf { it.isNotBlank() }
        val apps = systemActions.installedApps(limit = 100)
        val filtered = if (query == null) {
            apps
        } else {
            val needle = query.lowercase()
            val out = org.json.JSONArray()
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                val label = app.optString("label").lowercase()
                val pkg = app.optString("package").lowercase()
                if (label.contains(needle) || pkg.contains(needle)) out.put(app)
            }
            out
        }

        return buildJsonObject {
            put("query", query ?: "")
            put("count", filtered.length())
            put("apps_raw", filtered.toString())
            if (filtered.length() == 0 && query != null) {
                put("message", "No apps matched '$query'")
            }
            put(
                "note",
                "Use the 'package' field with launch_app to open an app."
            )
        }.let { ok("apps", it.toString()) }
    }

    private fun executeSamsungCapabilities(): ToolExecutionOutcome {
        val caps = samsungActions.capabilities()
        return buildJsonObject {
            put("is_galaxy_s23_ultra", samsungActions.isS23Ultra())
            caps.forEach { (key, value) -> put(key, value) }
            put(
                "note",
                "Samsung does not expose an S Pen sensor API to third-party apps, so air gestures cannot be triggered programmatically. S Pen settings are opened instead."
            )
        }.let { ok("capabilities", it.toString()) }
    }

    private fun ok(key: String, payload: String): ToolExecutionOutcome =
        ToolExecutionOutcome(payload, false)

    private fun opened(success: Boolean, what: String): ToolExecutionOutcome =
        buildJsonObject {
            put("opened", success)
            put(
                "status",
                if (success) "Opened $what. Make the change there."
                else "This device does not expose a $what screen."
            )
        }.let { ToolExecutionOutcome(it.toString(), !success) }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ schema builders

    private fun query(name: String, description: String): ToolDefinition =
        ToolDefinition(
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                    put("required", buildJsonObject {})
                }
            )
        )

    private fun handoff(name: String, description: String): ToolDefinition =
        query(name, description)

    private fun withArg(
        name: String,
        description: String,
        argName: String,
        argDescription: String,
        argType: String,
        required: Boolean
    ): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(argName, buildJsonObject {
                        put("type", argType)
                        put("description", argDescription)
                    })
                })
                put(
                    "required",
                    if (required) {
                        kotlinx.serialization.json.JsonArray(
                            listOf(kotlinx.serialization.json.JsonPrimitive(argName))
                        )
                    } else {
                        kotlinx.serialization.json.JsonArray(emptyList())
                    }
                )
                put("additionalProperties", false)
            }
        )
    )
}