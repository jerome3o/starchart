package io.github.jerome3o.starchart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager and notification-action entry point for [Schedules]. Not exported. */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Schedules.EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            ACTION_FIRE -> {
                val item = Schedules.find(context, id) ?: return
                // A stale alarm for a time that has since moved (snoozed, edited) is ignored.
                if (item.fireAt != intent.getLongExtra(Schedules.EXTRA_FIRE_AT, item.fireAt)) return
                Schedules.fire(context, item)
            }
            ACTION_DISMISS -> Schedules.dismiss(context, id)
            ACTION_SNOOZE -> Schedules.snooze(context, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "io.github.jerome3o.starchart.SCHEDULE_FIRE"
        const val ACTION_DISMISS = "io.github.jerome3o.starchart.SCHEDULE_DISMISS"
        const val ACTION_SNOOZE = "io.github.jerome3o.starchart.SCHEDULE_SNOOZE"
    }
}
