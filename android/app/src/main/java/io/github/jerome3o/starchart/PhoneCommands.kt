package io.github.jerome3o.starchart

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appfunctions.AppFunctionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes commands queued on the server by MCP clients granted phone:control.
 * Only this fixed list exists; anything else is rejected. Picked up after fix
 * uploads (the server says when some are waiting), by the watchdog, by the
 * hourly sync and when the app is opened. Call [poll] off the main thread.
 */
object PhoneCommands {

    private val polling = AtomicBoolean(false)

    fun poll(context: Context) {
        if (!Sync.isLinked(context) || !polling.compareAndSet(false, true)) return
        try {
            val commands = Sync.call(context, "GET", "/api/commands").optJSONArray("commands") ?: return
            for (i in 0 until commands.length()) {
                val c = commands.getJSONObject(i)
                val id = c.getLong("id")
                val name = c.getString("command")
                val (ok, result) = try {
                    true to run(context, name, c.optJSONObject("args") ?: JSONObject())
                } catch (e: Exception) {
                    false to JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}")
                }
                TrackingLog.log(context, "phone_command", "$name ${if (ok) "ok" else "failed"}")
                Sync.call(context, "POST", "/api/commands/$id/result", JSONObject().put("ok", ok).put("result", result))
            }
        } catch (_: Exception) {
            // Retried on the next poll; the server expires commands after an hour.
        } finally {
            polling.set(false)
        }
    }

    /** [poll] on a background thread. */
    fun pollAsync(context: Context) {
        val app = context.applicationContext
        Thread { poll(app) }.start()
    }

    private fun run(context: Context, name: String, args: JSONObject): Any = when (name) {
        "diagnostics" -> diagnostics(context)
        "restart_tracking" -> restartTracking(context)
        "sync_now" -> JSONObject().put("outcome", Sync.uploadPending(context).name).put("last_error", Sync.lastError(context))
        "fresh_fix" -> freshFix(context)
        "refresh_widget" -> {
            val snapshot = GoalsApi.fetch(context)
            JSONObject().put("goals", snapshot.goals.size)
        }
        "notify" -> notify(context, args)
        else -> throw IllegalArgumentException("unknown command $name")
    }

    private fun diagnostics(context: Context): JSONObject {
        val db = LocationDb(context)
        val now = System.currentTimeMillis()
        val latest = db.latest()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val out = JSONObject()
            .put("app_version", info.versionName)
            .put("installed_or_updated", iso(info.lastUpdateTime))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .put("tracking_enabled", Prefs.get(context).getBoolean(Prefs.KEY_TRACKING_ENABLED, false))
            .put("service_running", LocationService.running)
            .put("last_fix", latest?.let {
                JSONObject().put("time", iso(it.timeMs)).put("age_min", (now - it.timeMs) / 60000).put("accuracy_m", it.accuracyM)
            } ?: JSONObject.NULL)
            .put("local_fixes", db.count())
            .put("sync", JSONObject()
                .put("linked", Sync.isLinked(context))
                .put("last_sync", Sync.lastSyncTime(context).takeIf { it > 0 }?.let { iso(it) } ?: JSONObject.NULL)
                .put("last_error", Sync.lastError(context) ?: JSONObject.NULL)
                .put("unsynced_fixes", db.fixesAfter(Sync.lastSyncedId(context), 100000).size)
                .put("network", Sync.hasNetwork(context)))
            .put("power", TrackingLog.powerState(context))
            .put("permissions", JSONObject()
                .put("location", Permissions.hasLocation(context))
                .put("background_location", Permissions.hasBackgroundLocation(context))
                .put("notifications", Permissions.canNotify(context))
                .put("overlay", Permissions.canDrawOverlays(context))
                .put("battery_optimization_exempt", Permissions.isIgnoringBatteryOptimizations(context))
                .put("exact_alarms", Permissions.canScheduleExactAlarms(context)))
            .put("goals_cache_age_min", GoalsApi.cachedAt(context).takeIf { it > 0 }?.let { (now - it) / 60000 } ?: JSONObject.NULL)
            .put("recent_events", JSONArray().apply {
                db.recentEvents(40).forEach { put("${iso(it.timeMs)} ${it.kind}${it.detail?.let { d -> " — $d" } ?: ""}") }
            })
        if (Build.VERSION.SDK_INT >= 30) {
            out.put("recent_process_exits", JSONArray().apply {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    .forEach { put("${iso(it.timestamp)} reason=${it.reason} ${it.description ?: ""} importance=${it.importance}") }
            })
        }
        out.put("app_functions", appFunctions(context))
        return out
    }

    private fun appFunctions(context: Context): Any = try {
        if (Build.VERSION.SDK_INT < 36) "unsupported (needs Android 16)" else runBlocking {
            val manager = AppFunctionManager.getInstance(context) ?: return@runBlocking "not available on this device"
            JSONObject().apply {
                for (fn in listOf("getGoals", "openGoalSlider")) {
                    val id = "io.github.jerome3o.starchart.BaseStarchartAppFunctionService#$fn"
                    put(fn, try { if (manager.isAppFunctionEnabled(id)) "enabled" else "disabled" } catch (e: Exception) { "error: ${e.message}" })
                }
            }
        }
    } catch (e: Exception) {
        "error: ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun restartTracking(context: Context): JSONObject {
        if (LocationService.restartUpdates()) return JSONObject().put("restarted", "location updates re-registered")
        LocationService.start(context, "phone_command")
        return JSONObject().put("restarted", "service start requested")
    }

    @SuppressLint("MissingPermission")
    private fun freshFix(context: Context): JSONObject {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(30_000)
            .setMaxUpdateAgeMillis(0)
            .build()
        val location = Tasks.await(
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, null),
            35, TimeUnit.SECONDS
        ) ?: return JSONObject().put("fix", JSONObject.NULL).put("note", "no location available")
        LocationDb(context).insert(LocationDb.Fix(location.time, location.latitude, location.longitude, location.accuracy))
        Sync.uploadPending(context)
        return JSONObject().put("fix", JSONObject()
            .put("time", iso(location.time)).put("lat", location.latitude).put("lon", location.longitude)
            .put("accuracy_m", location.accuracy).put("provider", location.provider))
    }

    private fun notify(context: Context, args: JSONObject): JSONObject {
        if (!Notifications.canNotify(context)) return JSONObject().put("shown", false).put("reason", "notifications not allowed")
        Notifications.ensureChannel(context)
        val pkg = Uri.parse("package:${context.packageName}")
        val target = when (args.optString("open")) {
            "app_settings" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            "battery_optimization" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "notification_settings" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            else -> null
        } ?: Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 7, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = args.optString("text")
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle(args.optString("title", "Starchart"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
            JSONObject().put("shown", true)
        } catch (e: SecurityException) {
            JSONObject().put("shown", false).put("reason", e.message)
        }
    }

    private const val NOTIFY_ID = 4242

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
}
