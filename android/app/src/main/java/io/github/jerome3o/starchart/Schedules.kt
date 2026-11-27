package io.github.jerome3o.starchart

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Alarms, reminders and persistent notifications set through the MCP server.
 * The server holds the list; [sync] fetches it, keeps a local copy (so they
 * survive reboots without network) and mirrors it into AlarmManager. When one
 * goes off, [ScheduleReceiver] shows it and the outcome is reported back.
 */
object Schedules {

    data class Item(val id: Long, val kind: String, val title: String, val text: String?, val fireAt: Long, val status: String) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("title", title)
            .put("text", text ?: JSONObject.NULL).put("fire_at", fireAt).put("status", status)

        companion object {
            fun from(o: JSONObject) = Item(
                id = o.getLong("id"),
                kind = o.getString("kind"),
                title = o.optString("title", "Starchart").take(80),
                text = if (o.isNull("text")) null else o.optString("text").take(400).ifEmpty { null },
                fireAt = o.getLong("fire_at"),
                status = o.optString("status", "scheduled"),
            )
        }
    }

    const val CHANNEL_ALARMS = "alarms"
    const val CHANNEL_REMINDERS = "scheduled_reminders"
    const val CHANNEL_PINNED = "pinned"
    const val EXTRA_ID = "schedule_id"
    const val EXTRA_FIRE_AT = "fire_at"
    const val SNOOZE_MS = 10 * 60_000L

    private const val PREFS = "schedules"
    private const val KEY_ITEMS = "items"
    private const val KEY_FIRED = "fired" // "id@fireAt" already handled on this phone
    private const val ALARM_LATE_LIMIT_MS = 30 * 60_000L
    private const val REMINDER_LATE_LIMIT_MS = 24 * 3600_000L

    fun notificationId(id: Long) = 50_000 + (id % 1_000_000).toInt()

    // --- Local store ------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun items(context: Context): List<Item> = try {
        val arr = JSONArray(prefs(context).getString(KEY_ITEMS, "[]"))
        (0 until arr.length()).map { Item.from(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun find(context: Context, id: Long) = items(context).find { it.id == id }

    @Synchronized
    private fun save(context: Context, items: List<Item>) {
        prefs(context).edit().putString(KEY_ITEMS, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()).apply()
    }

    @Synchronized
    private fun replace(context: Context, item: Item) = save(context, items(context).map { if (it.id == item.id) item else it })

    @Synchronized
    private fun remove(context: Context, id: Long) = save(context, items(context).filter { it.id != id })

    private fun firedKey(item: Item) = "${item.id}@${item.fireAt}"

    @Synchronized
    fun wasFired(context: Context, item: Item) = prefs(context).getStringSet(KEY_FIRED, emptySet())!!.contains(firedKey(item))

    @Synchronized
    fun markFired(context: Context, item: Item) {
        // Keep the set small: only keys for items we still know about, plus this one.
        val known = items(context).map { "${it.id}@" }
        val kept = prefs(context).getStringSet(KEY_FIRED, emptySet())!!.filter { k -> known.any { k.startsWith(it) } }.toMutableSet()
        kept += firedKey(item)
        prefs(context).edit().putStringSet(KEY_FIRED, kept).apply()
    }

    // --- Sync with the server ------------------------------------------------------

    /** Fetches the list and reconciles. Blocking; call off the main thread. Returns a summary for the server. */
    fun sync(context: Context): JSONObject {
        val arr = Sync.call(context, "GET", "/api/schedules").optJSONArray("schedules") ?: JSONArray()
        val fresh = (0 until arr.length()).map { Item.from(arr.getJSONObject(it)) }
        reconcile(context, fresh)
        return status(context).put("items", JSONArray().apply {
            fresh.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind).put("status", it.status)) }
        })
    }

    fun syncAsync(context: Context) {
        val app = context.applicationContext
        if (!Sync.isLinked(app)) return
        Thread { try { sync(app) } catch (_: Exception) {} }.start()
    }

    /** After a reboot or update: re-register from the local copy (no network needed). */
    fun restoreLocal(context: Context) {
        reconcile(context, items(context))
    }

    /** What the phone can do, so a failed alarm isn't a silent surprise. */
    fun status(context: Context): JSONObject {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)
        return JSONObject()
            .put("notifications_allowed", Notifications.canNotify(context))
            .put("exact_alarms_allowed", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            .put("full_screen_alarms_allowed", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
            .put("alarm_channel_enabled", nm.getNotificationChannel(CHANNEL_ALARMS)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    private fun reconcile(context: Context, serverItems: List<Item>) {
        val old = items(context)
        val oldById = old.associateBy { it.id }
        // An alarm snoozed here whose report hasn't reached the server yet: keep the snooze.
        val fresh = serverItems.map { s ->
            val local = oldById[s.id]
            if (local != null && s.kind == "alarm" && s.status == "fired" && local.status == "scheduled" && local.fireAt > s.fireAt) local else s
        }
        val freshIds = fresh.map { it.id }.toSet()
        val nm = NotificationManagerCompat.from(context)
        // Gone from the server (cancelled, dismissed elsewhere): stop and clear.
        // A reminder that already went off leaves the list but its notification stays.
        for (o in old) if (o.id !in freshIds) {
            cancelAlarm(context, o.id)
            if (o.kind != "reminder") nm.cancel(notificationId(o.id))
        }
        save(context, fresh)

        val now = System.currentTimeMillis()
        val showing = showingIds(context)
        for (item in fresh) {
            when (item.status) {
                "scheduled" -> when {
                    item.fireAt > now -> register(context, item)
                    wasFired(context, item) -> {}
                    else -> fireLate(context, item, now - item.fireAt)
                }
                "fired" -> when {
                    // Pinned notifications come back after a reboot or if the system dropped them.
                    item.kind == "persistent" && notificationId(item.id) !in showing -> show(context, item, alert = false)
                    // An alarm that rang out (10-minute timeout) without being handled.
                    item.kind == "alarm" && notificationId(item.id) !in showing && now - item.fireAt > 11 * 60_000L ->
                        dismiss(context, item.id)
                }
            }
        }
    }

    private fun fireLate(context: Context, item: Item, lateMs: Long) {
        val limit = if (item.kind == "alarm") ALARM_LATE_LIMIT_MS else REMINDER_LATE_LIMIT_MS
        if (item.kind != "persistent" && lateMs > limit) {
            markFired(context, item)
            Notifications.show(context, if (item.kind == "alarm") "Missed alarm: ${item.title}" else item.title,
                "Was due ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(item.fireAt)}" +
                    (item.text?.let { " — $it" } ?: ""))
            report(context, item.id, "missed")
            return
        }
        fire(context, item)
    }

    private fun showingIds(context: Context): Set<Int> = try {
        context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    // --- AlarmManager --------------------------------------------------------------

    private fun firePendingIntent(context: Context, id: Long, fireAt: Long, create: Boolean = true): PendingIntent? {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_FIRE)
            .setData(Uri.parse("starchart-schedule://fire/$id"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_FIRE_AT, fireAt)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun register(context: Context, item: Item) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = firePendingIntent(context, item.id, item.fireAt)!!
        try {
            if (item.kind == "alarm") {
                // An alarm clock: exact even in Doze, and shown as the next alarm.
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(item.fireAt, show), pi)
            } else if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.fireAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.fireAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.fireAt, pi)
        }
    }

    private fun cancelAlarm(context: Context, id: Long) {
        firePendingIntent(context, id, 0, create = false)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    // --- Going off ------------------------------------------------------------------

    /** Called when an item's time comes (or on sync, if it came while the phone wasn't listening). */
    fun fire(context: Context, item: Item) {
        if (wasFired(context, item)) return
        markFired(context, item)
        show(context, item, alert = true)
        replace(context, item.copy(status = "fired"))
        report(context, item.id, "fired")
    }

    fun dismiss(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        cancelAlarm(context, id)
        remove(context, id)
        report(context, id, "dismissed")
    }

    fun snooze(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        val item = find(context, id) ?: return
        val next = item.copy(fireAt = System.currentTimeMillis() + SNOOZE_MS, status = "scheduled")
        replace(context, next)
        register(context, next)
        report(context, id, "snoozed", next.fireAt)
    }

    private fun show(context: Context, item: Item, alert: Boolean) {
        if (!Notifications.canNotify(context)) return
        ensureChannels(context)
        val notification = when (item.kind) {
            "alarm" -> alarmNotification(context, item)
            "persistent" -> pinnedNotification(context, item, alert)
            else -> reminderNotification(context, item)
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId(item.id), notification)
        } catch (_: SecurityException) {
        }
    }

    private fun action(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ScheduleReceiver::class.java).setAction(action)
            .setData(Uri.parse("starchart-schedule://$action/$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmNotification(context: Context, item: Item): android.app.Notification {
        val screen = PendingIntent.getActivity(
            context, 0,
            Intent(context, AlarmActivity::class.java)
                .setData(Uri.parse("starchart-schedule://alarm/${item.id}"))
                .putExtra(EXTRA_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle("⏰ ${item.title}")
            .setContentText(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(item.fireAt))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(10 * 60_000L) // stop ringing after 10 minutes
            .addAction(0, context.getString(R.string.alarm_snooze), action(context, ScheduleReceiver.ACTION_SNOOZE, item.id))
            .addAction(0, context.getString(R.string.alarm_dismiss), action(context, ScheduleReceiver.ACTION_DISMISS, item.id))
            .build()
            .apply { flags = flags or android.app.Notification.FLAG_INSISTENT } // loop the sound until handled
    }

    private fun reminderNotification(context: Context, item: Item) =
        NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle(item.title)
            .setContentText(item.text ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text ?: ""))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    private fun pinnedNotification(context: Context, item: Item, alert: Boolean) =
        NotificationCompat.Builder(context, CHANNEL_PINNED)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle("📌 ${item.title}")
            .setContentText(item.text ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text ?: ""))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setContentIntent(openApp(context))
            // Android 14+ lets people swipe even ongoing notifications: treat that as done too.
            .setDeleteIntent(action(context, ScheduleReceiver.ACTION_DISMISS, item.id))
            .addAction(0, context.getString(R.string.pinned_done), action(context, ScheduleReceiver.ACTION_DISMISS, item.id))
            .build()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alarms_description)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_scheduled_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_scheduled_reminders_description)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PINNED, context.getString(R.string.channel_pinned), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_pinned_description)
            }
        )
    }

    // --- Reporting back -----------------------------------------------------------

    private fun report(context: Context, id: Long, status: String, fireAt: Long? = null) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ScheduleReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(EXTRA_ID to id, "status" to status, EXTRA_FIRE_AT to (fireAt ?: 0L)))
                .build()
        )
    }
}

/** Posts what happened to an alarm/reminder/notification; retried until it lands. */
class ScheduleReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getLong(Schedules.EXTRA_ID, -1)
        val status = inputData.getString("status") ?: return Result.failure()
        if (id < 0 || !Sync.isLinked(applicationContext)) return Result.failure()
        return try {
            val body = JSONObject().put("status", status)
            val fireAt = inputData.getLong(Schedules.EXTRA_FIRE_AT, 0)
            if (fireAt > 0) body.put("fire_at", fireAt)
            Sync.call(applicationContext, "POST", "/api/schedules/$id/status", body)
            Result.success()
        } catch (_: Sync.UnauthorizedException) {
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }
}
