package io.github.jerome3o.starchart

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Exact, Doze-proof alarm every [INTERVAL_MS] while tracking is on. If the
 * service has died it restarts it (alarms are exempt from the background
 * foreground-service ban); if the service is alive but no fix has landed for
 * a while (Doze starving the balanced-power request) it asks for one fresh
 * high-accuracy fix. Either way the reason is logged to [TrackingLog].
 */
class TrackingWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.get(context).getBoolean(Prefs.KEY_TRACKING_ENABLED, false)) return
        schedule(context)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                check(app)
                // Also the pickup point for phone commands while the phone dozes.
                PhoneCommands.poll(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun check(context: Context) {
        TrackingLog.recordProcessExits(context)

        if (!LocationService.running) {
            TrackingLog.log(context, "watchdog_restart", TrackingLog.powerState(context))
            try {
                LocationService.start(context, "watchdog")
            } catch (e: Exception) {
                TrackingLog.log(context, "start_failed", "watchdog: ${e.javaClass.simpleName}: ${e.message}")
            }
            return
        }

        val latest = LocationDb(context).latest()?.timeMs ?: 0L
        val ageMs = System.currentTimeMillis() - latest
        if (ageMs < STALE_MS) return
        TrackingLog.log(
            context, "watchdog_stale",
            "no fix for ${TimeUnit.MILLISECONDS.toMinutes(ageMs)} min; ${TrackingLog.powerState(context)}"
        )
        requestFix(context)
    }

    @SuppressLint("MissingPermission")
    private fun requestFix(context: Context) {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(STALE_MS / 2)
            .build()
        try {
            val location = Tasks.await(
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, null),
                FIX_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS
            )
            if (location == null) {
                TrackingLog.log(context, "watchdog_no_fix", "no location returned")
                return
            }
            LocationDb(context).insert(
                LocationDb.Fix(location.time, location.latitude, location.longitude, location.accuracy)
            )
            if (Sync.isLinked(context) && Sync.hasNetwork(context)) Sync.uploadPending(context)
        } catch (e: Exception) {
            TrackingLog.log(context, "watchdog_no_fix", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val INTERVAL_MS = 10 * 60_000L
        private const val STALE_MS = 8 * 60_000L
        private const val FIX_TIMEOUT_MS = 30_000L

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, TrackingWatchdog::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }
}
