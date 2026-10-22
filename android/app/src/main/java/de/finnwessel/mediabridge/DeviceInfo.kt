package de.finnwessel.mediabridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Output format, Bluetooth devices and diagnostics, read on demand. */
object DeviceInfo {

    /** Current display mode, e.g. 3840×2160 @ 24 Hz, plus the HDR formats the output supports. */
    fun display(context: Context): JSONObject? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        val types: IntArray = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull() ?: IntArray(0)
        val hdr: List<String> = types.toList().mapNotNull { type -> HDR_TYPES[type] }
        return JSONObject()
            .put("width", mode.physicalWidth)
            .put("height", mode.physicalHeight)
            .put("refresh_rate", (mode.refreshRate * 100).roundToInt() / 100.0)
            .put("hdr_types", JSONArray(hdr))
    }

    /** Short key for change detection: a mode switch (e.g. 60 → 24 Hz) is pushed at once. */
    fun displayKey(info: JSONObject?) =
        info?.let { "${it.optInt("width")}x${it.optInt("height")}@${it.optDouble("refresh_rate")}" }

    fun hasBluetoothPermission(context: Context) =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Connected Bluetooth devices (paired and currently connected), with battery level when reported. */
    @SuppressLint("MissingPermission")
    fun bluetooth(context: Context): JSONArray? {
        if (!hasBluetoothPermission(context)) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return JSONArray()
        val list = JSONArray()
        runCatching { adapter.bondedDevices }.getOrNull()?.forEach { device ->
            if (!isConnected(device)) return@forEach
            list.put(
                JSONObject()
                    .put("name", runCatching { (if (Build.VERSION.SDK_INT >= 30) device.alias else null) ?: device.name }.getOrNull() ?: device.address)
                    .put("address", device.address)
                    .put("type", kind(device))
                    .put("battery", battery(device) ?: JSONObject.NULL),
            )
        }
        return list
    }

    fun bluetoothKey(devices: JSONArray?): String? = devices?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it).optString("address") }.sorted().joinToString(",")
    }

    // Hidden but stable APIs (used by Settings since Android 8); no public alternative for classic devices.
    private fun isConnected(device: BluetoothDevice): Boolean =
        runCatching { device.javaClass.getMethod("isConnected").invoke(device) as Boolean }.getOrDefault(false)

    private fun battery(device: BluetoothDevice): Int? =
        runCatching { device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int }
            .getOrNull()?.takeIf { it in 0..100 }

    @SuppressLint("MissingPermission")
    private fun kind(device: BluetoothDevice): String {
        val cls = runCatching { device.bluetoothClass }.getOrNull() ?: return "other"
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "headphones"
                else -> "speaker"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> "remote"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            else -> "other"
        }
    }

    /** Network, storage, boot time and granted permissions; sent with every push, not a reason to push. */
    @Suppress("DEPRECATION")
    fun diagnostics(service: MediaBridgeService): JSONObject {
        val json = JSONObject()
        val cm = service.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val network = when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "other"
        }
        json.put("network", network ?: JSONObject.NULL)
        if (network == "wifi") {
            runCatching { service.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }
                .getOrNull()?.let { info ->
                    json.put("wifi_rssi", info.rssi.takeIf { it in -127..0 } ?: JSONObject.NULL)
                    json.put("wifi_link_mbps", info.linkSpeed.takeIf { it > 0 } ?: JSONObject.NULL)
                    json.put("wifi_frequency", info.frequency.takeIf { it > 0 } ?: JSONObject.NULL)
                }
        }
        runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()?.let {
            json.put("storage_free", it.availableBytes)
            json.put("storage_total", it.totalBytes)
        }
        json.put("booted_at", System.currentTimeMillis() - SystemClock.elapsedRealtime())
        json.put("permissions", permissions(service))
        return json
    }

    fun permissions(service: MediaBridgeService): JSONObject = JSONObject()
        .put("overlay", Settings.canDrawOverlays(service))
        .put("accessibility", KeyService.instance != null)
        .put("device_admin", service.adminActive())
        .put(
            "secure_settings",
            service.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
        )
        .put("bluetooth", hasBluetoothPermission(service))
        .put("install", Build.VERSION.SDK_INT < 26 || service.packageManager.canRequestPackageInstalls())

    private val HDR_TYPES = mapOf(
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to "dolby_vision",
        Display.HdrCapabilities.HDR_TYPE_HDR10 to "hdr10",
        Display.HdrCapabilities.HDR_TYPE_HLG to "hlg",
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to "hdr10_plus",
    )
}
