package io.github.jerome3o.starchart

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // Placeholder goals — the real list will come from the backend later.
    private val goals = listOf(
        Goal("water_plants", "Water the plants"),
        Goal("morning_run", "Go for a morning run"),
        Goal("read_book", "Read for 20 minutes"),
    )

    private lateinit var db: LocationDb

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startLocationTracking()
            } else {
                findViewById<MaterialSwitch>(R.id.switch_location).isChecked = false
                Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.background_location_granted
            } else {
                R.string.background_location_denied
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = LocationDb(this)
        Notifications.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(this)) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setUpGoalList()
        setUpNotificationButtons()
        setUpDailyReminderSwitch()
        setUpOverlaySwitch()
        setUpLocationTracking()
        setUpSync()
        handlePairIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialSwitch>(R.id.switch_overlay).isChecked = OverlayService.running
        findViewById<MaterialSwitch>(R.id.switch_location).isChecked = LocationService.running
        updateLocationStatus()
        updateSyncStatus()
    }

    // --- Server sync ---------------------------------------------------------

    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "starchart" || data.host != "pair") return
        val token = data.getQueryParameter("token")
        if (token.isNullOrEmpty()) return
        Sync.storeToken(this, token)
        SyncWorker.schedulePeriodic(this)
        SyncWorker.syncNow(this)
        Toast.makeText(this, R.string.sync_linked_toast, Toast.LENGTH_LONG).show()
        updateSyncStatus()
    }

    private fun setUpSync() {
        findViewById<Button>(R.id.btn_link_server).setOnClickListener {
            if (Sync.isLinked(this)) {
                Sync.unlink(this)
                SyncWorker.cancelPeriodic(this)
                Toast.makeText(this, R.string.sync_unlinked_toast, Toast.LENGTH_SHORT).show()
                updateSyncStatus()
            } else {
                val label = Uri.encode(Build.MODEL ?: "Android device")
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("${Sync.SERVER_URL}/pair/start?label=$label"))
                )
            }
        }

        findViewById<Button>(R.id.btn_sync_now).setOnClickListener {
            if (!Sync.isLinked(this)) {
                Toast.makeText(this, R.string.sync_not_linked_toast, Toast.LENGTH_SHORT).show()
            } else {
                SyncWorker.syncNow(this)
                Toast.makeText(this, R.string.sync_started_toast, Toast.LENGTH_SHORT).show()
            }
        }

        // Refresh the status line whenever a sync run finishes (or fails).
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.SYNC_NOW_WORK)
            .observe(this) { updateSyncStatus() }
    }

    private fun updateSyncStatus() {
        val status = findViewById<TextView>(R.id.sync_status)
        val linkButton = findViewById<Button>(R.id.btn_link_server)
        val error = Sync.lastError(this)
        if (Sync.isLinked(this)) {
            linkButton.setText(R.string.btn_unlink_server)
            val lastSync = Sync.lastSyncTime(this)
            val base = getString(
                R.string.sync_status_linked,
                Sync.uploadedCount(this),
                if (lastSync == 0L) getString(R.string.sync_never)
                else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSync))
            )
            status.text = if (error == null) base else "$base\n⚠️ $error"
        } else {
            linkButton.setText(R.string.btn_link_server)
            val base = getString(R.string.sync_status_unlinked)
            status.text = if (error == null) base else "$base\n⚠️ $error"
        }
    }

    // --- Overlay -----------------------------------------------------------

    private fun setUpOverlaySwitch() {
        findViewById<MaterialSwitch>(R.id.switch_overlay).setOnCheckedChangeListener { switch, checked ->
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    ContextCompat.startForegroundService(
                        this, Intent(this, OverlayService::class.java)
                    )
                } else {
                    switch.isChecked = false
                    Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                stopService(Intent(this, OverlayService::class.java))
            }
        }
    }

    // --- Location tracking -------------------------------------------------

    private fun setUpLocationTracking() {
        findViewById<MaterialSwitch>(R.id.switch_location).setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (hasLocationPermission()) {
                    startLocationTracking()
                } else {
                    requestLocationPermissions.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            } else {
                prefs().edit().putBoolean(KEY_TRACKING_ENABLED, false).apply()
                stopService(Intent(this, LocationService::class.java))
            }
        }

        findViewById<Button>(R.id.btn_export_locations).setOnClickListener { exportLocations() }

        findViewById<Button>(R.id.btn_background_location).setOnClickListener {
            when {
                Build.VERSION.SDK_INT < 29 -> Toast.makeText(
                    this, R.string.background_location_granted, Toast.LENGTH_SHORT
                ).show()
                !hasLocationPermission() -> Toast.makeText(
                    this, R.string.background_location_needs_foreground, Toast.LENGTH_LONG
                ).show()
                else -> requestBackgroundLocation.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }

        findViewById<Button>(R.id.btn_battery_optimizations).setOnClickListener {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, R.string.battery_already_exempt, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun startLocationTracking() {
        prefs().edit().putBoolean(KEY_TRACKING_ENABLED, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        findViewById<MaterialSwitch>(R.id.switch_location).isChecked = true
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updateLocationStatus() {
        val status = findViewById<TextView>(R.id.location_status)
        val latest = db.latest()
        status.text = if (latest == null) {
            getString(R.string.location_status_empty)
        } else {
            getString(
                R.string.location_status,
                db.count(),
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(latest.timeMs)),
                latest.accuracyM.toInt()
            )
        }
    }

    private fun exportLocations() {
        if (db.count() == 0L) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "starchart-locations.csv")
        file.bufferedWriter().use { db.writeCsv(it) }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_chooser_title)))
    }

    // --- Goals & notification tests (placeholder features) ------------------

    private fun setUpGoalList() {
        val container = findViewById<LinearLayout>(R.id.goal_container)
        val prefs = prefs()
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
        val prefs = prefs()
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

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private data class Goal(val id: String, val name: String)

    companion object {
        private const val PREFS = "starchart"
        private const val KEY_DAILY_REMINDER = "daily_reminder"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val DAILY_REMINDER_WORK = "daily-reminder"
    }
}
