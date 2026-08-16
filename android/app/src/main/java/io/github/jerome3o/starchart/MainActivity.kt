package io.github.jerome3o.starchart

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // Placeholder goals — the real list will come from the backend later.
    private val goals = listOf(
        Goal("water_plants", "Water the plants"),
        Goal("morning_run", "Go for a morning run"),
        Goal("read_book", "Read for 20 minutes"),
    )

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Notifications.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(this)) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setUpGoalList()
        setUpNotificationButtons()
        setUpDailyReminderSwitch()
    }

    private fun setUpGoalList() {
        val container = findViewById<LinearLayout>(R.id.goal_container)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val inflater = LayoutInflater.from(this)

        goals.forEach { goal ->
            val row = inflater.inflate(R.layout.item_goal, container, false)
            val stars = row.findViewById<TextView>(R.id.goal_stars)

            fun render(count: Int) {
                row.findViewById<TextView>(R.id.goal_name).text = goal.name
                stars.text = if (count == 0) {
                    getString(R.string.no_stars_yet)
                } else {
                    "⭐".repeat(count.coerceAtMost(10)) + if (count > 10) " ×$count" else ""
                }
            }

            render(prefs.getInt(goal.id, 0))

            row.findViewById<Button>(R.id.goal_add_star).setOnClickListener {
                val count = prefs.getInt(goal.id, 0) + 1
                prefs.edit().putInt(goal.id, count).apply()
                render(count)
            }

            container.addView(row)
        }
    }

    private fun setUpNotificationButtons() {
        findViewById<Button>(R.id.btn_test_notification).setOnClickListener {
            if (!Notifications.canNotify(this)) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            Notifications.show(
                this,
                getString(R.string.app_name),
                getString(R.string.test_notification_text)
            )
        }

        findViewById<Button>(R.id.btn_notify_in_one_minute).setOnClickListener {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        ReminderWorker.KEY_TITLE to getString(R.string.app_name),
                        ReminderWorker.KEY_TEXT to getString(R.string.scheduled_notification_text),
                    )
                )
                .build()
            WorkManager.getInstance(this).enqueue(request)
            Toast.makeText(this, R.string.scheduled_notification_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpDailyReminderSwitch() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val dailySwitch = findViewById<MaterialSwitch>(R.id.switch_daily_reminder)
        dailySwitch.isChecked = prefs.getBoolean(KEY_DAILY_REMINDER, false)

        dailySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_DAILY_REMINDER, checked).apply()
            if (checked) {
                val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(24, TimeUnit.HOURS)
                    .setInputData(
                        workDataOf(
                            ReminderWorker.KEY_TITLE to getString(R.string.app_name),
                            ReminderWorker.KEY_TEXT to getString(R.string.default_reminder_text),
                        )
                    )
                    .build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    DAILY_REMINDER_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            } else {
                WorkManager.getInstance(this).cancelUniqueWork(DAILY_REMINDER_WORK)
            }
        }
    }

    private data class Goal(val id: String, val name: String)

    companion object {
        private const val PREFS = "starchart"
        private const val KEY_DAILY_REMINDER = "daily_reminder"
        private const val DAILY_REMINDER_WORK = "daily-reminder"
    }
}
