package io.github.jerome3o.starchart

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executors

/**
 * Foreground service that records a location fix roughly every minute into
 * the local SQLite database. Balanced-power priority plus batching keeps the
 * battery cost low while the persistent notification keeps the OS from
 * killing it. Starts, stops and Doze transitions go to [TrackingLog], and
 * [TrackingWatchdog] restarts it (or grabs a fix) if it goes quiet.
 */
class LocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var db: LocationDb

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                db.insert(
                    LocationDb.Fix(
                        timeMs = location.time,
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = location.accuracy,
                    )
                )
            }
            pushToServer()
        }
    }

    // Uploads run one at a time on this thread; failures are left for the
    // hourly SyncWorker catch-up.
    private val uploader = Executors.newSingleThreadExecutor()

    private fun pushToServer() {
        if (!Sync.isLinked(this) || !Sync.hasNetwork(this)) return
        uploader.execute {
            Sync.uploadPending(this)
            if (Sync.commandsWaiting) PhoneCommands.poll(this)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pm = getSystemService(PowerManager::class.java)
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                    TrackingLog.log(context, if (pm.isDeviceIdleMode) "doze_on" else "doze_off")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    TrackingLog.log(context, if (pm.isPowerSaveMode) "battery_saver_on" else "battery_saver_off")
            }
        }
    }

    private var request: LocationRequest? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = LocationDb(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        TrackingLog.recordProcessExits(this)
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted us after killing the process.
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: if (intent == null) "sticky_restart" else "unknown"
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                Notifications.serviceNotification(
                    this,
                    getString(R.string.location_notification_title),
                    getString(R.string.location_notification_text)
                ),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background in many
            // cases (including some sticky restarts); the watchdog retries.
            TrackingLog.log(this, "start_failed", "$reason: ${e.javaClass.simpleName}: ${e.message}")
            TrackingWatchdog.schedule(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            TrackingLog.log(this, "start_failed", "$reason: no location permission")
            stopSelf()
            return START_NOT_STICKY
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            // Let the OS batch up to two fixes and deliver them together —
            // fixes keep their real timestamps, the radio wakes up less.
            .setMaxUpdateDelayMillis(INTERVAL_MS * 2)
            .build()
        this.request = request

        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) TrackingLog.log(this, "service_started", "$reason; ${TrackingLog.powerState(this)}")
        running = true
        TrackingWatchdog.schedule(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TrackingLog.log(this, "task_removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        if (running) TrackingLog.log(this, "service_stopped")
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        fused.removeLocationUpdates(callback)
        uploader.shutdown()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val INTERVAL_MS = 60_000L
        private const val EXTRA_REASON = "reason"

        /** Starts (or pokes) tracking; [reason] is logged for diagnostics. */
        fun start(context: Context, reason: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java).putExtra(EXTRA_REASON, reason)
            )
        }

        @Volatile
        var running = false
            private set

        @Volatile
        private var instance: LocationService? = null

        /** Re-registers location updates in the live service; false if none is running. */
        @android.annotation.SuppressLint("MissingPermission")
        fun restartUpdates(): Boolean {
            val service = instance ?: return false
            val request = service.request ?: return false
            service.fused.removeLocationUpdates(service.callback)
            service.fused.requestLocationUpdates(request, service.callback, Looper.getMainLooper())
            TrackingLog.log(service, "updates_restarted")
            return true
        }
    }
}
