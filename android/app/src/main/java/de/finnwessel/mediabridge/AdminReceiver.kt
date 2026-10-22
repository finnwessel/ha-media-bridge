package de.finnwessel.mediabridge

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin with the single "force-lock" policy: lets the app switch the
 * screen off (standby). Activated once via ADB:
 *   adb shell dpm set-active-admin de.finnwessel.mediabridge/.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver()
