package io.github.jerome3o.starchart

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Diagnostics for gaps in tracking: service starts/stops, why Android killed
 * the process (ApplicationExitInfo), Doze and power state. Stored next to the
 * fixes and uploaded with them, so gaps can be explained after the fact
 * (the MCP `find_location_gaps` tool shows them per gap).
 */
object TrackingLog {

    private const val KEY_LAST_EXIT_SEEN = "last_exit_seen"

    fun log(context: Context, kind: String, detail: String? = null, timeMs: Long = System.currentTimeMillis()) {
        try {
            LocationDb(context).insertEvent(timeMs, kind, detail)
        } catch (_: Exception) {}
    }

    /** One-line snapshot of everything that throttles background location. */
    fun powerState(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val parts = mutableListOf(
            "doze=${pm.isDeviceIdleMode}",
            "screenOn=${pm.isInteractive}",
            "batterySaver=${pm.isPowerSaveMode}",
            "batteryOptExempt=${pm.isIgnoringBatteryOptimizations(context.packageName)}",
        )
        if (Build.VERSION.SDK_INT >= 28) {
            val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            parts += "bucket=" + when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
                else -> bucket.toString()
            }
            parts += "bgRestricted=${context.getSystemService(ActivityManager::class.java).isBackgroundRestricted}"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            parts += "bgLocation=" + (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
        }
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            parts += "battery=${level * 100 / scale.coerceAtLeast(1)}%"
            if (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) parts += "charging"
        }
        return parts.joinToString(" ")
    }

    /**
     * Logs every process death Android recorded since the last check, stamped
     * with when it happened. Also covers deaths from before this was added.
     */
    fun recordProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val prefs = Prefs.get(context)
            val lastSeen = prefs.getLong(KEY_LAST_EXIT_SEEN, 0L)
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 32)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            for (e in exits) {
                val detail = buildString {
                    append(reasonName(e.reason))
                    if (!e.description.isNullOrBlank()) append(": ").append(e.description)
                    append(" (importance ").append(e.importance)
                    append(", pss ").append(e.pss / 1024).append("MB)")
                }
                log(context, "process_died", detail, e.timestamp)
            }
            exits.lastOrNull()?.let { prefs.edit().putLong(KEY_LAST_EXIT_SEEN, it.timestamp).apply() }
        } catch (_: Exception) {}
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        else -> "reason_$reason"
    }
}
