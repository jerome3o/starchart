package io.github.jerome3o.starchart

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts location tracking after a reboot if it was enabled. A location
 * foreground service started from the background is only allowed (and only
 * receives fixes) with "Allow all the time" background location granted,
 * so without it we skip the restart rather than crash.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("starchart", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("tracking_enabled", false)) return

        val hasBackgroundLocation = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (hasBackgroundLocation) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java)
            )
        }
    }
}
