package com.edgehybrid.agent.systemactions

import android.Manifest
import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-mostly device actions and queries that need no dangerous permission.
 *
 * Everything here is either a **query** (returns JSON to the model) or an **intent that
 * hands off to a system Settings screen** so the user makes the actual change. Nothing in
 * this class silently mutates protected device state, which is what keeps it inside the
 * auto-approve / single-confirmation tiers defined in
 * [com.edgehybrid.agent.agent.ToolConfirmationPolicy].
 *
 * Target hardware is the Samsung Galaxy S23 Ultra (SM-S918B), so the camera, display, and
 * vendor-specific queries are written against that device's actual feature set.
 */
@Singleton
class SystemActionHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // ---------------------------------------------------------------- battery

    /** Battery charge, charging state, energy saver, and remaining time. */
    fun batteryStatus(): JSONObject {
        val bm = batteryManager()
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        // Voltage comes from the sticky battery broadcast, not from a battery property;
        // BATTERY_PROPERTY_CHARGE_COUNTER is the charge counter in microamp-hours and is
        // reported under its own key rather than mislabelled as voltage.
        val broadcast = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = broadcast?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val chargeCounterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        return JSONObject().apply {
            put("percent", level)
            put("is_charging", isCharging)
            put("temperature_c", (broadcast?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            put("health", batteryHealth())
            put("voltage_mv", voltageMv)
            put("charge_counter_uah", chargeCounterUah)
            put("technology", broadcast?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown")
            put("cycle_count", broadcast?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, 0) ?: 0)
            put("energy_saver_enabled", isPowerSaveMode())
        }
    }

    /**
     * Screen brightness as a 0-100 percentage.
     *
     * Read from system settings rather than by writing them, so this stays query-only.
     */
    fun screenBrightnessPercent(): Int {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )
        if (raw < 0) return -1
        return ((raw * 100) / 255).coerceIn(0, 100)
    }

    private fun batteryHealth(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }
    }

    private fun isPowerSaveMode(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true

    // ---------------------------------------------------------------- audio

    /**
     * Reads and sets media volume.
     *
     * Setting volume is a visible, immediately reversible change on the user's own
     * device, so it is exposed as a single-confirmation action rather than a silent one.
     * Values are clamped; `level` is a 0-100 percentage of the media stream.
     */
    fun mediaVolumePercent(): Int {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return -1
        return ((audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max).coerceIn(0, 100)
    }

    fun setMediaVolumePercent(percent: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val clamped = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((clamped * max) / 100f).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return true
    }

    // ---------------------------------------------------------------- connectivity

    /** Active network transport, signal strength, and whether the radio is on. */
    fun connectivityStatus(): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)

        val transport = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        val result = JSONObject().apply {
            put("transport", transport)
            put("is_online", caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            put("is_metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
        }

        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val info = cm.getNetworkCapabilities(network)
                ?.transportInfo as? android.net.wifi.WifiInfo
            result.put("wifi_rssi_dbm", info?.rssi ?: -127)
            result.put("wifi_ssid", info?.ssid?.trim('"') ?: "unknown")
            result.put("wifi_link_speed_mbps", info?.linkSpeed ?: -1)
        }

        return result
    }

    /** True when the SIM can place calls. Does not read the phone number. */
    fun canMakeCalls(): Boolean {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        @Suppress("MissingPermission")
        return telephony.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) != 0

    // ---------------------------------------------------------------- device

    /** Device identity and capability summary; no identifiers that identify a person. */
    fun deviceInfo(): JSONObject {
        val metrics = context.resources.displayMetrics
        val result = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("cpu_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("screen_width_px", metrics.widthPixels)
            put("screen_height_px", metrics.heightPixels)
            put("density_dpi", metrics.densityDpi)
            put("refresh_rate_hz", displayRefreshRate())
        }

        val isS23Ultra = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            (Build.MODEL.contains("S23", ignoreCase = true))
        result.put("is_galaxy_s23_ultra", isS23Ultra)

        return result
    }

    @Suppress("DEPRECATION")
    private fun displayRefreshRate(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate?.toInt() ?: 0
        } else {
            context.resources.displayMetrics.densityDpi
        }

    // ---------------------------------------------------------------- storage

    /** Free and total space for internal and (if present) the SD card. */
    fun storageInfo(): JSONObject {
        val result = JSONObject()

        val internal = StatFs(Environment.getDataDirectory().path)
        result.put("internal_total_gb", bytesToGb(internal.totalBytes))
        result.put("internal_free_gb", bytesToGb(internal.availableBytes))

        val sdRoot = Environment.getExternalStorageState()
        val sdPresent = sdRoot == Environment.MEDIA_MOUNTED
        result.put("sd_card_present", sdPresent)
        if (sdPresent) {
            val sd = StatFs(Environment.getExternalStorageDirectory().path)
            result.put("sd_total_gb", bytesToGb(sd.totalBytes))
            result.put("sd_free_gb", bytesToGb(sd.availableBytes))
        }

        return result
    }

    private fun bytesToGb(bytes: Long): Double =
        Math.round((bytes / 1_073_741_824.0) * 100.0) / 100.0

    // ---------------------------------------------------------------- memory

    /** Foreground memory pressure plus the raw RAM figures. */
    fun memoryInfo(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return JSONObject()
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return JSONObject().apply {
            put("total_ram_gb", round2(memInfo.totalMem / 1_073_741_824.0))
            put("available_ram_gb", round2(memInfo.availMem / 1_073_741_824.0))
            put("low_memory", memInfo.lowMemory)
            put("memory_class_mb", am.memoryClass)
            put("large_memory_class_mb", am.largeMemoryClass)
        }
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    // ---------------------------------------------------------------- camera (S23 Ultra optics)

    /**
     * Camera hardware detail from `camera2`.
     *
     * The S23 Ultra has a 200 MP main, 12 MP ultrawide, 10 MP 3x telephoto and 10 MP 5x
     * periscope, so this reports whatever the actual hardware exposes rather than
     * hardcoded marketing values.
     */
    fun cameraHardware(): JSONArray {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return JSONArray()

        val out = JSONArray()
        try {
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                                    ?: intArrayOf()

                                out.put(JSONObject().apply {
                                    put("camera_id", cameraId)
                                    put("facing", facing)
                                    put(
                                        "megapixels",
                                        round2(
                                            (size?.width?.toDouble() ?: 0.0) *
                                                (size?.height?.toDouble() ?: 0.0) / 1_000_000.0
                                        )
                                    )
                                    put("has_flash", chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                                    // API 35 exposes no REQUEST_AVAILABLE_CAPABILITIES_AUTOFOCUS flag, so
                                    // autofocus is inferred from the AF modes actually advertised.
                                    put("has_autofocus", hasAutoFocusModes(chars))
                                    put("supports_raw", capabilities.contains(
                                        android.hardware.camera2.CameraMetadata
                                            .REQUEST_AVAILABLE_CAPABILITIES_RAW
                                    ))
                                    put(
                                        "ultra_high_resolution",
                                        capabilities.contains(
                                            android.hardware.camera2.CameraMetadata
                                                .REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
                                        )
                                    )
                                })
            }
        } catch (_: Exception) {
            // Camera enumeration can fail while another app holds the camera; report
            // whatever we collected rather than throwing into the agent loop.
        }
        return out
    }

    /**
     * True when the camera advertises any continuous or auto autofocus mode.
     *
     * `REQUEST_AVAILABLE_CAPABILITIES_AUTOFOCUS` does not exist in the public API, so AF
     * support is read from [CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES].
     */
    private fun hasAutoFocusModes(
        chars: android.hardware.camera2.CameraCharacteristics
    ): Boolean {
        val modes = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return false
        val afContinuous = android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val afAuto = android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO
        return modes.contains(afContinuous) || modes.contains(afAuto)
    }

    // ---------------------------------------------------------------- installed apps

    /**
     * Launchable apps on the device.
     *
     * Only apps that expose a `CATEGORY_LAUNCHER` activity are returned, so this cannot
     * be used to enumerate every installed package.
     */
    fun installedApps(limit: Int = 40): JSONArray {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val out = JSONArray()
        resolved.take(limit.coerceIn(1, 200)).forEach { info ->
            out.put(JSONObject().apply {
                put("label", info.loadLabel(pm).toString())
                put("package", info.activityInfo.packageName)
            })
        }
        return out
    }

    /** True when [packageName] is installed and launchable. */
    fun isInstalled(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .any { it.activityInfo.packageName == packageName }
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- system settings screens

    /**
     * Opens a system Settings screen so the user makes the change themselves.
     *
     * This is used instead of writing settings directly: `WRITE_SETTINGS` is a
     * signature-level permission, and handing off respects the platform's own consent
     * flow for things like accessibility, battery optimisation, and overlay settings.
     */
    fun openSettingsScreen(action: String): Boolean = try {
        context.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Settings actions this device is allowed to open. */
    fun openAppInfo(packageName: String): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Opens the default-assist / voice settings page if the device has it. */
    fun openDefaultAssistant(): Boolean =
        openSettingsScreen(Settings.ACTION_VOICE_INPUT_SETTINGS) ||
            openSettingsScreen(Settings.ACTION_HOME_SETTINGS)

    /** Opens the Samsung-specific battery settings page when available. */
    fun openSamsungBatterySettings(): Boolean {
        val intents = listOf(
            Intent("com.samsung.android.lool.provider.action.BATTERY_SETTINGS"),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        )
        return intents.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    // ---------------------------------------------------------------- permissions

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Location permission status, reported without ever reading a location fix.
     *
     * Coarse vs fine is reported so the model can tell the user what the app could read
     * if it were asked, without consuming the grant itself.
     */
    fun locationPermissionState(): String = when {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "precise"
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> "approximate"
        else -> "not_granted"
    }

    // ---------------------------------------------------------------- helpers

    private fun batteryManager(): BatteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
}