package io.github.jerome3o.starchart

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.DateFormat
import java.util.Date

/**
 * Foreground service that records a location fix roughly every minute into
 * the local SQLite database. Balanced-power priority plus batching keeps the
 * battery cost low while the persistent notification keeps the OS from
 * killing it.
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
            result.lastLocation?.let { updateNotification(it.time) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = LocationDb(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            Notifications.serviceNotification(
                this,
                getString(R.string.location_notification_title),
                getString(R.string.location_notification_starting)
            ),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            // Let the OS batch up to two fixes and deliver them together —
            // fixes keep their real timestamps, the radio wakes up less.
            .setMaxUpdateDelayMillis(INTERVAL_MS * 2)
            .build()

        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateNotification(lastFixTimeMs: Long) {
        if (!Notifications.canNotify(this)) return
        val text = getString(
            R.string.location_notification_status,
            db.count(),
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastFixTimeMs))
        )
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                Notifications.serviceNotification(
                    this,
                    getString(R.string.location_notification_title),
                    text
                )
            )
        } catch (_: SecurityException) {
            // Notification permission revoked mid-flight; tracking continues.
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val INTERVAL_MS = 60_000L

        @Volatile
        var running = false
            private set
    }
}
