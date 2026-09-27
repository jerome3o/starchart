package io.github.jerome3o.starchart

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
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

/** Everything that isn't a core feature: permissions, tracking, sync, tests. */
class SettingsFragment : Fragment() {

    private lateinit var db: LocationDb

    // Set when the location permission request came from the tracking switch,
    // so a grant from the permissions panel doesn't silently start tracking.
    private var startTrackingAfterGrant = false

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(R.string.notifications_denied)
            updatePermissionStatus()
        }

    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted && startTrackingAfterGrant) {
                startLocationTracking()
            } else if (!granted) {
                requireView().findViewById<MaterialSwitch>(R.id.switch_location).isChecked = false
                toast(R.string.location_denied)
            }
            startTrackingAfterGrant = false
            updatePermissionStatus()
        }

    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toast(if (granted) R.string.background_location_granted else R.string.background_location_denied)
            updatePermissionStatus()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = LocationDb(requireContext())
        setUpMapStyle(view)
        setUpPermissions(view)
        setUpLocationTracking(view)
        setUpSync(view)
        setUpNotificationButtons(view)
        setUpDailyReminderSwitch(view)
        setUpOverlaySwitch(view)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refreshAll()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshAll()
    }

    private fun refreshAll() {
        val view = view ?: return
        val context = requireContext()
        updatePermissionStatus()
        val trackingEnabled = Prefs.get(context).getBoolean(Prefs.KEY_TRACKING_ENABLED, false) &&
            Permissions.hasLocation(context)
        view.findViewById<MaterialSwitch>(R.id.switch_location).isChecked = trackingEnabled
        view.findViewById<MaterialSwitch>(R.id.switch_overlay).isChecked = OverlayService.running
        updateLocationStatus()
        updateSyncStatus()
    }

    // --- Map style -----------------------------------------------------------

    private fun setUpMapStyle(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.map_style_spinner)
        spinner.adapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.map_style_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val current = Prefs.get(requireContext()).getString(Prefs.KEY_MAP_STYLE, MapFragment.DEFAULT_STYLE)
        spinner.setSelection(MapFragment.STYLE_KEYS.indexOf(current).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                Prefs.get(requireContext()).edit()
                    .putString(Prefs.KEY_MAP_STYLE, MapFragment.STYLE_KEYS[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    // --- Permissions & reliability -------------------------------------------

    private fun setUpPermissions(view: View) {
        val context = requireContext()
        view.findViewById<Button>(R.id.btn_perm_notifications).setOnClickListener {
            when {
                Permissions.canNotify(context) -> alreadyGranted()
                Build.VERSION.SDK_INT >= 33 ->
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
        view.findViewById<Button>(R.id.btn_perm_location).setOnClickListener {
            if (Permissions.hasLocation(context)) alreadyGranted() else requestLocationPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
        view.findViewById<Button>(R.id.btn_perm_background_location).setOnClickListener {
            when {
                Permissions.hasBackgroundLocation(context) -> alreadyGranted()
                !Permissions.hasLocation(context) -> toast(R.string.background_location_needs_foreground)
                else -> requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        view.findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener {
            if (Permissions.canDrawOverlays(context)) alreadyGranted() else startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())
            )
        }
        view.findViewById<Button>(R.id.btn_perm_battery).setOnClickListener {
            if (Permissions.isIgnoringBatteryOptimizations(context)) alreadyGranted() else startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
            )
        }
        view.findViewById<Button>(R.id.btn_perm_exact_alarms).setOnClickListener {
            if (Permissions.canScheduleExactAlarms(context)) alreadyGranted() else if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
            }
        }
    }

    private fun updatePermissionStatus() {
        val view = view ?: return
        val context = requireContext()
        fun label(button: Int, name: Int, granted: Boolean) {
            view.findViewById<Button>(button).text = getString(
                if (granted) R.string.perm_granted_prefix else R.string.perm_missing_prefix,
                getString(name)
            )
        }
        label(R.id.btn_perm_notifications, R.string.perm_notifications, Permissions.canNotify(context))
        label(R.id.btn_perm_location, R.string.perm_location, Permissions.hasLocation(context))
        label(R.id.btn_perm_background_location, R.string.perm_background_location, Permissions.hasBackgroundLocation(context))
        label(R.id.btn_perm_overlay, R.string.perm_overlay, Permissions.canDrawOverlays(context))
        label(R.id.btn_perm_battery, R.string.perm_battery, Permissions.isIgnoringBatteryOptimizations(context))
        label(R.id.btn_perm_exact_alarms, R.string.perm_exact_alarms, Permissions.canScheduleExactAlarms(context))
    }

    // --- Location tracking ---------------------------------------------------

    private fun setUpLocationTracking(view: View) {
        view.findViewById<MaterialSwitch>(R.id.switch_location).setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Permissions.hasLocation(requireContext())) {
                    startLocationTracking()
                } else {
                    startTrackingAfterGrant = true
                    requestLocationPermissions.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            } else {
                Prefs.get(requireContext()).edit().putBoolean(Prefs.KEY_TRACKING_ENABLED, false).apply()
                requireContext().stopService(Intent(requireContext(), LocationService::class.java))
            }
        }
        view.findViewById<Button>(R.id.btn_export_locations).setOnClickListener { exportLocations() }
    }

    private fun startLocationTracking() {
        val context = requireContext()
        Prefs.get(context).edit().putBoolean(Prefs.KEY_TRACKING_ENABLED, true).apply()
        LocationService.start(context, "settings")
        view?.findViewById<MaterialSwitch>(R.id.switch_location)?.isChecked = true
    }

    private fun updateLocationStatus() {
        val status = view?.findViewById<TextView>(R.id.location_status) ?: return
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
        val context = requireContext()
        if (db.count() == 0L) {
            toast(R.string.export_empty)
            return
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "starchart-locations.csv")
        file.bufferedWriter().use { db.writeCsv(it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_chooser_title)))
    }

    // --- Server sync ---------------------------------------------------------

    private fun setUpSync(view: View) {
        val context = requireContext()
        view.findViewById<Button>(R.id.btn_link_server).setOnClickListener {
            if (Sync.isLinked(context)) {
                Sync.unlink(context)
                SyncWorker.cancelPeriodic(context)
                toast(R.string.sync_unlinked_toast)
                updateSyncStatus()
            } else {
                val label = Uri.encode(Build.MODEL ?: "Android device")
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("${Sync.SERVER_URL}/pair/start?label=$label"))
                )
            }
        }

        view.findViewById<Button>(R.id.btn_sync_now).setOnClickListener {
            if (!Sync.isLinked(context)) {
                toast(R.string.sync_not_linked_toast)
            } else {
                SyncWorker.syncNow(context)
                toast(R.string.sync_started_toast)
            }
        }

        // Refresh the status line whenever a sync run finishes (or fails).
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.SYNC_NOW_WORK)
            .observe(viewLifecycleOwner) { updateSyncStatus() }
    }

    private fun updateSyncStatus() {
        val view = view ?: return
        val context = requireContext()
        val status = view.findViewById<TextView>(R.id.sync_status)
        val linkButton = view.findViewById<Button>(R.id.btn_link_server)
        val error = Sync.lastError(context)
        val base = if (Sync.isLinked(context)) {
            linkButton.setText(R.string.btn_unlink_server)
            val lastSync = Sync.lastSyncTime(context)
            getString(
                R.string.sync_status_linked,
                Sync.uploadedCount(context),
                if (lastSync == 0L) getString(R.string.sync_never)
                else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSync))
            )
        } else {
            linkButton.setText(R.string.btn_link_server)
            getString(R.string.sync_status_unlinked)
        }
        status.text = if (error == null) base else "$base\n⚠️ $error"
    }

    // --- Notification tests & reminders ----------------------------------------

    private fun setUpNotificationButtons(view: View) {
        val context = requireContext()
        view.findViewById<Button>(R.id.btn_test_notification).setOnClickListener {
            if (!Permissions.canNotify(context)) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            Notifications.show(context, getString(R.string.app_name), getString(R.string.test_notification_text))
        }

        view.findViewById<Button>(R.id.btn_notify_in_one_minute).setOnClickListener {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        ReminderWorker.KEY_TITLE to getString(R.string.app_name),
                        ReminderWorker.KEY_TEXT to getString(R.string.scheduled_notification_text),
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
            toast(R.string.scheduled_notification_toast)
        }
    }

    private fun setUpDailyReminderSwitch(view: View) {
        val context = requireContext()
        val prefs = Prefs.get(context)
        val dailySwitch = view.findViewById<MaterialSwitch>(R.id.switch_daily_reminder)
        dailySwitch.isChecked = prefs.getBoolean(Prefs.KEY_DAILY_REMINDER, false)

        dailySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_DAILY_REMINDER, checked).apply()
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
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    DAILY_REMINDER_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
                )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(DAILY_REMINDER_WORK)
            }
        }
    }

    // --- Overlay ---------------------------------------------------------------

    private fun setUpOverlaySwitch(view: View) {
        val context = requireContext()
        view.findViewById<MaterialSwitch>(R.id.switch_overlay).setOnCheckedChangeListener { switch, checked ->
            if (checked) {
                if (Permissions.canDrawOverlays(context)) {
                    ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                } else {
                    switch.isChecked = false
                    toast(R.string.overlay_permission_needed)
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
                }
            } else {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private fun packageUri(): Uri = Uri.parse("package:${requireContext().packageName}")

    private fun alreadyGranted() = toast(R.string.perm_already_granted)

    private fun toast(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DAILY_REMINDER_WORK = "daily-reminder"
    }
}
