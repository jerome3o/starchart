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
        requestFix(context, goAsync())
    }

    @SuppressLint("MissingPermission")
    private fun requestFix(context: Context, pending: PendingResult) {
        val app = context.applicationContext
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(STALE_MS / 2)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(app).getCurrentLocation(request, null)
                .addOnCompleteListener { task ->
                    val location = if (task.isSuccessful) task.result else null
                    Thread {
                        try {
                            if (location == null) {
                                TrackingLog.log(app, "watchdog_no_fix", task.exception?.message)
                            } else {
                                LocationDb(app).insert(
                                    LocationDb.Fix(location.time, location.latitude, location.longitude, location.accuracy)
                                )
                                if (Sync.isLinked(app) && Sync.hasNetwork(app)) Sync.uploadPending(app)
                            }
                        } finally {
                            pending.finish()
                        }
                    }.start()
                }
        } catch (e: Exception) {
            TrackingLog.log(app, "watchdog_no_fix", "${e.javaClass.simpleName}: ${e.message}")
            pending.finish()
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
