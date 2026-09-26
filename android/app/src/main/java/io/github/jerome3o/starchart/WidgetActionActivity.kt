package io.github.jerome3o.starchart

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.util.Random

/**
 * Transparent activity launched from the widget, drawn straight over the home
 * screen: slides up a card with the charge slider for one goal. Finishing the
 * slide is the only way to log from the widget; it then celebrates and closes
 * itself, leaving you on the home screen.
 */
class WidgetActionActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val sound = ChargeSound()
    private lateinit var haptics: Haptics
    private lateinit var root: FrameLayout
    private var done = false
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = Haptics(this)
        root = FrameLayout(this)
        setContentView(root)

        val goalId = intent.getLongExtra(GoalsWidgetProvider.EXTRA_GOAL_ID, -1)
        val goal = GoalsApi.cached(this)?.goals?.find { it.id == goalId }
        if (goal == null) {
            finishQuietly()
            return
        }
        showCharge(goal)
    }

    // --- Completion (only reachable by finishing the slider) ------------------------

    private fun logAndCelebrate(goal: GoalsApi.Goal) {
        if (done) return
        done = true
        GoalsWidgetProvider.logFromWidget(this, goal.id)
        haptics.celebrate()
        sound.chime()
        sound.stop()
        CelebrationView.show(root)
        // Tapping anywhere dismisses early.
        root.setOnClickListener { finishQuietly() }
        main.postDelayed({ finishQuietly() }, 3200)
    }

    // --- Charge card ------------------------------------------------------------------

    private fun showCharge(goal: GoalsApi.Goal) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            alpha = 0f
            setOnClickListener { if (!done) finishQuietly() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrim.animate().alpha(1f).setDuration(200).start()

        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(26))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true // don't dismiss when tapping the card itself
        }
        val title = TextView(this).apply {
            text = "${goal.emoji ?: "⭐"}  ${goal.name}"
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            paint.isFakeBoldText = true
        }
        val target = if (goal.target == Math.floor(goal.target)) goal.target.toLong().toString() else goal.target.toString()
        val subtitle = TextView(this).apply {
            text = getString(R.string.charge_card_progress, goal.count, target, goal.daysLeft)
            setTextColor(onSurface)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(18))
        }
        val slider = ChargeSliderView(this).apply { label = getString(R.string.slide_to_log) }
        card.addView(title)
        card.addView(subtitle)
        card.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        card.post {
            card.translationY = card.height.toFloat()
            card.animate().translationY(0f).setDuration(260).start()
        }

        slider.listener = object : ChargeSliderView.Listener {
            override fun onChargeStart() {
                sound.level = 0f
                sound.start()
                haptics.tick(0.1f)
            }

            override fun onChargeProgress(progress: Float) {
                sound.level = progress
                val amount = 12f * density * progress * progress
                card.translationX = (random.nextFloat() * 2 - 1) * amount
                card.translationY = (random.nextFloat() * 2 - 1) * amount * 0.6f
                card.rotation = (random.nextFloat() * 2 - 1) * 0.8f * progress * progress
            }

            override fun onNotch(index: Int, progress: Float) {
                haptics.tick((index + 1f) / slider.notches.size)
            }

            override fun onChargeComplete() {
                sound.stop()
                card.animate().translationX(0f).rotation(0f)
                    .translationY(card.height.toFloat()).setStartDelay(250).setDuration(300).start()
                ObjectAnimator.ofFloat(scrim, View.ALPHA, 1f, 0f).setDuration(500).start()
                logAndCelebrate(goal)
            }

            override fun onChargeRelease(completed: Boolean) {
                sound.stop()
                card.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(160).start()
            }
        }
    }

    // --- Lifecycle -----------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (done && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            finishQuietly()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        sound.stop()
        if (!isFinishing) finishQuietly()
    }

    private fun finishQuietly() {
        main.removeCallbacksAndMessages(null)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

}
