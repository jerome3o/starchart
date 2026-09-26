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
        val prefs = context.getSharedPreferences("starchart", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("tracking_enabled", false)) return

        val hasBackgroundLocation = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasBackgroundLocation) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java)
            )
        } catch (_: Exception) {
            // Background FGS start refused (e.g. no battery-optimization
            // exemption); the app resumes tracking next time it's opened.
        }
    }
}
