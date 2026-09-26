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
 * fills with completions, plus a marker showing where you should be by now.
 */
class PaceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fillFraction = 0f
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
    private val behindColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)
    private val rect = RectF()

    fun set(count: Int, target: Double, targetByNow: Double, behind: Boolean) {
        fillFraction = if (target > 0) (count / target).toFloat().coerceIn(0f, 1f) else 0f
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
