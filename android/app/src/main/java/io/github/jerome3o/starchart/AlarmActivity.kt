package io.github.jerome3o.starchart

import android.app.Activity
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat

/**
 * Full-screen alarm: shows over the lock screen and turns the screen on. The
 * sound comes from the (insistent) alarm notification, so closing this screen
 * without choosing leaves it ringing; Snooze and Dismiss go through [Schedules].
 */
class AlarmActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var id = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        id = intent.getLongExtra(Schedules.EXTRA_ID, -1)
        val item = Schedules.find(this, id)
        if (item == null) {
            finish()
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 16, 32))
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        root.addView(TextView(this).apply {
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(System.currentTimeMillis())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "⏰ ${item.title}"
            setTextColor(Color.rgb(208, 188, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(56))
        })
        fun big(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(32).toFloat() }
            setOnClickListener { onClick(); finish() }
        }
        val params = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(16) } }
        root.addView(big(getString(R.string.alarm_snooze), Color.rgb(70, 64, 100)) { Schedules.snooze(this, id) }, params())
        root.addView(big(getString(R.string.alarm_dismiss), Color.rgb(103, 80, 164)) { Schedules.dismiss(this, id) }, params())
        setContentView(root)
        watch()
    }

    /** Closes itself if the alarm is handled elsewhere (cancelled via MCP, the notification's buttons, timeout). */
    private fun watch() {
        main.postDelayed({
            val showing = try {
                getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == Schedules.notificationId(id) }
            } catch (_: Exception) { true }
            if (!showing) finish() else watch()
        }, 2000)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
