package com.edgehybrid.agent.systemactions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-specific actions for the Samsung Galaxy S23 Ultra, plus broadly useful Android
 * actions that work well on that handset.
 *
 * Samsung ships several system surfaces that are not part of the public Android SDK and
 * that can change between firmware versions. Every action here is therefore built around
 * an `Intent` with a documented fallback chain, and each returns whether something
 * actually opened. Where a Samsung action may not exist on a given build, the fallback is
 * the stock Android screen, so the tool degrades instead of failing.
 *
 * Samsung does not expose a public S Pen sensor API. Air gestures are not reachable by
 * third-party apps, so nothing here pretends to control them; S Pen *input* is handled
 * through normal `MotionEvent`/`InputDevice` plumbing in the UI layer instead.
 */
@Singleton
class SamsungActions @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private fun launch(vararg intents: Intent): Boolean = intents.any { intent ->
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun samsungIntent(action: String, fallback: Intent? = null): Intent =
        Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ display

    /**
     * Opens the Samsung display settings so the user can set QHD and refresh rate.
     *
     * Reading these requires `WRITE_SETTINGS`, which is signature-level on a normal build,
     * so the app hands off instead. The stock Display settings is the fallback, which is
     * where One UI still exposes resolution and motion smoothness.
     */
    fun openDisplaySettings(): Boolean {
        val samsungDisplay = samsungIntent("com.samsung.android.settings.DISPLAY_SETTINGS")
        val stockDisplay = Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(samsungDisplay, stockDisplay)
    }

    /** Opens motion/smoothness settings (refresh rate) with a Samsung-first chain. */
    fun openRefreshRateSettings(): Boolean {
        val samsungMotion = samsungIntent("com.samsung.android.settings.DISPLAY_MOTION_SCREEN")
        val stockDisplay = Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(samsungMotion, stockDisplay)
    }

    // ------------------------------------------------------------------ performance

    /**
     * Opens battery settings, preferring the Samsung "battery and power usage" screen
     * over the stock battery saver screen.
     */
    fun openBatterySettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.BATTERY_SETTINGS_PAGE"),
        samsungIntent("com.samsung.android.settings.BATTERY"),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /**
     * Opens the "Modes and Routines" page (One UI 5.1+ scene/automation engine).
     *
     * This is the closest public equivalent to device automation: users can put the phone
     * into Focus, Driving, Sleeping, or a custom routine. The app hands the user to it
     * rather than trying to program routines silently.
     */
    fun openModesAndRoutines(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.MODES_AND_ROUTINES"),
        samsungIntent("com.samsung.android.settings.action.MODES_AND_ROUTINES"),
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens One UI's "Apps to optimize" / battery-protected app list. */
    fun openSleepingAppsSettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.sleeping_apps"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ input

    /** Opens language, gestures, and keyboard settings. */
    fun openLanguageAndInputSettings(): Boolean = launch(
        Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens the S Pen settings page when the device has a stylus. */
    fun openSPenSettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.SPEN_SETTINGS"),
        samsungIntent("com.samsung.android.settings.SPen")
    )

    /** Opens accessibility settings, including touch exploration for stylus use. */
    fun openAccessibilitySettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.accessibility"),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens per-app notification settings so the user can silence a noisy app. */
    fun openNotificationSettings(): Boolean = launch(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ security

    /** Opens app permissions so the user can grant or revoke a capability. */
    fun openPermissionManager(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.PRIVACY_NOTIFICATION"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens device security settings (lock screen, biometric, lockout). */
    fun openSecuritySettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.security"),
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens the device manager so the user can see or disable an app's admin rights. */
    fun openDeviceAdminSettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.device_admin"),
        Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ networking

    /** Opens Wi-Fi settings with a Samsung-first chain (Wi-Fi 7 ready on S23 Ultra). */
    fun openWifiSettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.WifiSettings"),
        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens the enhanced connectivity / data saver screen. */
    fun openNetworkOptimizerSettings(): Boolean = launch(
        samsungIntent("com.samsung.android.settings.OPERATION_MODE"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ media capture

    /**
     * Opens the camera directly to a chosen capture mode when the device's camera app
     * supports it. Falls back to the stock camera intent.
     */
    fun openCameraMode(mode: String): Boolean {
        val packageName = when (mode.lowercase()) {
            "pro" -> "com.sec.android.app.SecCamera"
            "night" -> "com.sec.android.app.SecCamera"
            else -> "com.sec.android.app.camera"
        }
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(
            intent,
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the gallery app. */
    fun openGallery(): Boolean {
        val samsungGallery = Intent("com.sec.android.gallery3d.launcher.action.GALLERY")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val stockView = Intent(Intent.ACTION_VIEW)
            .setType("image/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(samsungGallery, stockView)
    }

    // ------------------------------------------------------------------ voice assistants

    /**
     * Opens the default voice assistant.
     *
     * On the S23 Ultra this is Bixby by default, which handles device-side routines
     * ("remind me in 20 minutes") that no third-party app can reach.
     */
    fun openVoiceAssistant(): Boolean = launch(
        Intent("com.samsung.android.bixby.agent.action.MAIN_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ apps

    /**
     * Launches an app by package name.
     *
     * Only launchable (CATEGORY_LAUNCHER) apps can be started, and the caller is expected
     * to have passed the confirmation gate first — this is a visible, user-facing action
     * but it is still a side effect on the device.
     */
    fun launchApp(packageName: String): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return launch(launchIntent)
    }

    /** Opens a web page in the default browser. */
    fun openUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens an Android-standard share sheet for text. */
    fun shareText(text: String): Boolean = launch(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Opens the dialer with a number ready, for the short codes OEM apps expose. */
    fun openDialer(number: String): Boolean {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isEmpty()) return false
        return launch(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the messaging app. */
    fun openMessages(): Boolean = launch(
        Intent("com.samsung.android.messaging/com.android.messaging.ui.conversationlist.MessageConversationListActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MESSAGING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ------------------------------------------------------------------ capability probe

    /**
     * Reports which Samsung-specific surfaces are actually present on this build.
     *
     * Used by the agent to avoid proposing a gesture that this firmware cannot perform,
     * instead of silently doing nothing.
     */
    fun capabilities(): Map<String, Boolean> = mapOf(
        "samsung_galaxy_store" to isInstalled("com.sec.android.app.samsungapps"),
        "samsung_bixby" to isInstalled("com.samsung.android.bixby.agent"),
        "samsung_health" to isInstalled("com.sec.android.app.shealth"),
        "samsung_notes" to isInstalled("com.samsung.android.app.notes"),
        "samsung_secure_folder" to isInstalled("com.samsung.knox.securefolder"),
        "samsung_gallery" to isInstalled("com.sec.android.gallery3d"),
        "samsung_camera" to isInstalled("com.sec.android.app.camera"),
        "samsung_spen" to hasStylus(),
        "stylus_input" to hasStylus(),
        "android_version" to (Build.VERSION.SDK_INT >= 34)
    )

    /**
     * True when an S Pen is attached.
     *
     * Detected from the public `InputDevice` API rather than a Samsung one, since none is
     * published. `InputDevice.getDeviceIds()` is public; `InputManager.getInputDevices()`
     * is a hidden API and would be blocked by non-SDK-interface enforcement, so it is
     * deliberately not used.
     */
    private fun hasStylus(): Boolean = runCatching {
        android.view.InputDevice.getDeviceIds().any { deviceId ->
            val device = android.view.InputDevice.getDevice(deviceId) ?: return@any false
            // SOURCE_STYLUS (0x4002) is the documented source bit for pen input.
            // `InputDevice.bluetoothDeviceName` is a hidden API, so pen detection relies
            // on the public source bits and the device name only.
            val isStylusSource = (device.sources and 0x4002) == 0x4002
            isStylusSource && device.name?.contains("S Pen", ignoreCase = true) != false
        }
    }.getOrDefault(false)

    /** True when this handset is a Galaxy S23 Ultra. */
    fun isS23Ultra(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.MODEL.contains("S23", ignoreCase = true) &&
            Build.MODEL.contains("Ultra", ignoreCase = true)
}