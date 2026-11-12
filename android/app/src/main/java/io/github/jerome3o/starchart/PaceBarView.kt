package io.github.jerome3o.starchart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * The phone-side version of the e-ink progress bar: a segmented track that
 * fills with completions, then red for logged failures, plus a marker
 * showing where you should be by now.
 */
class PaceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fillFraction = 0f
    private var failFraction = 0f
    private var paceFraction = 0f
    private var segments = 0
    private var behind = false

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorSurface)
        strokeWidth = 2f * density
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorOnSurface)
        strokeWidth = 2.5f * density
    }
    private val onTrackColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    // Behind is amber so red stays reserved for failures.
    private val behindColor = Color.rgb(0xF5, 0x9E, 0x0B)
    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }
    private val failStripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        strokeWidth = 2f * density
    }
    private val rect = RectF()

    fun set(count: Int, failed: Int, target: Double, targetByNow: Double, behind: Boolean) {
        fillFraction = if (target > 0) (count / target).toFloat().coerceIn(0f, 1f) else 0f
        failFraction = if (target > 0) (failed / target).toFloat().coerceIn(0f, 1f - fillFraction) else 0f
        paceFraction = if (target > 0) (targetByNow / target).toFloat().coerceIn(0f, 1f) else 0f
        segments = if (target == Math.floor(target) && target <= 40) target.toInt() else 0
        this.behind = behind
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, track)

        // Failures first, from 0 to done+failed, so the done fill overlaps
        // their rounded start and red reads as following on from it.
        if (failFraction > 0f) {
            val end = (w * (fillFraction + failFraction)).coerceAtLeast(h)
            rect.set(0f, 0f, end, h)
            canvas.drawRoundRect(rect, radius, radius, failPaint)
            canvas.save()
            canvas.clipRect(w * fillFraction, 0f, end, h)
            var x = w * fillFraction - h
            while (x < end) {
                canvas.drawLine(x, h, x + h, 0f, failStripe)
                x += 5f * density
            }
            canvas.restore()
        }
        fill.color = if (behind) behindColor else onTrackColor
        if (fillFraction > 0f) {
            rect.set(0f, 0f, (w * fillFraction).coerceAtLeast(h), h)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        if (segments > 1) {
            for (i in 1 until segments) {
                val x = w * i / segments
                canvas.drawLine(x, 0f, x, h, divider)
            }
        }
        if (paceFraction > 0f) {
            val x = (w * paceFraction).coerceIn(marker.strokeWidth, w - marker.strokeWidth)
            canvas.drawLine(x, -2f * density, x, h + 2f * density, marker)
        }
    }

    init {
        setWillNotDraw(false)
        if (isInEditMode) fill.color = Color.BLUE
    }
}
