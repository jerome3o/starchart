package io.github.jerome3o.starchart

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat

/**
 * Draws a ball on top of everything; the ball rolls with device tilt
 * (accelerometer gravity vector). The overlay window is non-touchable,
 * so all touches pass straight through to whatever is underneath.
 */
class OverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private var ballView: BallView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            Notifications.serviceNotification(
                this,
                getString(R.string.overlay_notification_title),
                getString(R.string.overlay_notification_text)
            ),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ballView == null) {
            val view = BallView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(view, params)
            ballView = view

            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        ballView?.let { windowManager.removeView(it) }
        ballView = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        ballView?.onGravity(event)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private class BallView(context: Context) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val radius = 24f * density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.star_gold)
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }

        private var x = -1f
        private var y = -1f
        private var vx = 0f
        private var vy = 0f
        private var lastTimestampNs = 0L

        // Pixel acceleration per m/s^2 of tilt; full sideways tilt ≈ 9.8 m/s^2.
        private val accelScale = 260f * density
        private val bounce = 0.55f

        fun onGravity(event: SensorEvent) {
            val now = event.timestamp
            if (lastTimestampNs != 0L && width > 0 && height > 0) {
                val dt = ((now - lastTimestampNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                if (x < 0) {
                    x = width / 2f
                    y = height / 2f
                }
                // Device frame: values[0] is +left tilt, values[1] is +bottom-down.
                vx += -event.values[0] * accelScale * dt
                vy += event.values[1] * accelScale * dt
                // Mild rolling friction so it settles instead of jittering.
                val damping = (1f - 0.6f * dt).coerceAtLeast(0f)
                vx *= damping
                vy *= damping
                x += vx * dt
                y += vy * dt

                if (x < radius) { x = radius; vx = -vx * bounce }
                if (x > width - radius) { x = width - radius; vx = -vx * bounce }
                if (y < radius) { y = radius; vy = -vy * bounce }
                if (y > height - radius) { y = height - radius; vy = -vy * bounce }

                invalidate()
            }
            lastTimestampNs = now
        }

        override fun onDraw(canvas: Canvas) {
            if (x < 0) return
            canvas.drawCircle(x, y, radius, fill)
            canvas.drawCircle(x, y, radius, stroke)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 100

        @Volatile
        var running = false
            private set
    }
}
