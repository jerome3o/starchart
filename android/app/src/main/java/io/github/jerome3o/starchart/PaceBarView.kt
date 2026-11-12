package io.github.jerome3o.starchart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * The phone-side version of the e-ink progress bar: a segmented track that
 * fills one slot per entry in the order logged (accent for done, red for
 * failed), plus a marker showing where you should be by now.
 */
class PaceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var slots = ""
    private var target = 0.0
    private var paceFraction = 0f
    private var segments = 0

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorPrimary)
    }
    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorSurface)
        strokeWidth = 2f * density
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@PaceBarView, com.google.android.material.R.attr.colorOnSurface)
        strokeWidth = 2.5f * density
    }
    private val rect = RectF()
    private val clip = Path()

    /** [slots] is [GoalsApi.Goal.slots]: one char per filled slot, oldest first ('d' / 'f'). */
    fun set(slots: String, target: Double, targetByNow: Double) {
        this.slots = slots
        this.target = target
        paceFraction = if (target > 0) (targetByNow / target).toFloat().coerceIn(0f, 1f) else 0f
        segments = if (target == Math.floor(target) && target <= 40) target.toInt() else 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, track)

        // Each entry fills its own slot, in the order it was logged.
        if (target > 0 && slots.isNotEmpty()) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            for ((i, kind) in slots.withIndex()) {
                val x0 = w * (i / target).toFloat()
                val x1 = minOf(w, w * ((i + 1) / target).toFloat())
                canvas.drawRect(x0, 0f, x1, h, if (kind == 'f') failPaint else donePaint)
            }
            canvas.restore()
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
    }
}
