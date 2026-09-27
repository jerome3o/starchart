package io.github.jerome3o.starchart

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts location tracking after a reboot or an app update (Android kills
 * the app on update and does not bring foreground services back). Starting a
 * location foreground service from the background is only allowed, and only
 * receives fixes, with "Allow all the time" background location granted, so
 * without it we skip rather than crash. MainActivity also resumes tracking
 * whenever the app is opened, as a fallback.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Prefs.get(context).getBoolean(Prefs.KEY_TRACKING_ENABLED, false)) return
        val reason = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "app_updated"
        TrackingWatchdog.schedule(context)

        val hasBackgroundLocation = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasBackgroundLocation) {
            TrackingLog.log(context, "start_failed", "$reason: no background location permission")
            return
        }

        try {
            LocationService.start(context, reason)
        } catch (e: Exception) {
            // Background FGS start refused (e.g. no battery-optimization
            // exemption); the watchdog retries, and opening the app always works.
            TrackingLog.log(context, "start_failed", "$reason: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
