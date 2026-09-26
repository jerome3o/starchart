package io.github.jerome3o.starchart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat

/**
 * Draws a widget goal bar as a bitmap (RemoteViews can't draw custom shapes):
 * the track, a lighter "expected by now" band, the done fill, a notch per
 * unit of target, day ticks underneath, and a bold "now" line marking how far
 * through the period you are.
 */
object WidgetBarRenderer {

    const val HEIGHT_DP = 22f

    fun render(
        context: Context,
        widthPx: Int,
        goal: GoalsApi.Goal,
        periodFraction: Double,
    ): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = widthPx.coerceAtLeast((60 * d).toInt())
        val h = (HEIGHT_DP * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Leave room at both ends so the now-marker's cap never clips.
        val inset = 3 * d
        val left = inset
        val right = w - inset
        val barW = right - left
        val barTop = 5 * d
        val barBottom = barTop + 10 * d
        val radius = 5 * d
        val bar = RectF(left, barTop, right, barBottom)

        val target = goal.target.coerceAtLeast(0.0001)
        val doneFrac = (goal.count / target).coerceIn(0.0, 1.0).toFloat()
        val expectedFrac = (goal.targetByNow / target).coerceIn(0.0, 1.0).toFloat()
        val behind = goal.isBehind && !goal.complete

        // Track, expected band, done fill — clipped to the rounded bar.
        c.save()
        c.clipPath(Path().apply { addRoundRect(bar, radius, radius, Path.Direction.CW) })
        paint.color = color(R.color.widget_track)
        c.drawRect(bar, paint)
        paint.color = color(R.color.widget_pace)
        c.drawRect(left, barTop, left + barW * expectedFrac, barBottom, paint)
        paint.color = color(if (behind) R.color.widget_behind else R.color.widget_accent)
        c.drawRect(left, barTop, left + barW * doneFrac, barBottom, paint)

        // Notches: one per unit of target (every 5th for big targets).
        if (goal.target == Math.floor(goal.target) && goal.target > 1) {
            val n = goal.target.toInt()
            val every = if (n > 30) 5 else 1
            paint.color = color(R.color.widget_bg)
            paint.alpha = 255
            paint.strokeWidth = 1.5f * d
            var i = every
            while (i < n) {
                val x = left + barW * i / n
                c.drawLine(x, barTop, x, barBottom, paint)
                i += every
            }
        }
        c.restore()

        // Day ticks under the bar; longer at week boundaries.
        val days = goal.periodDays
        paint.color = color(R.color.widget_subtext)
        paint.strokeWidth = 1f * d
        for (day in 0..days) {
            val x = left + barW * day / days
            val long = day % 7 == 0
            c.drawLine(x, barBottom + 2 * d, x, barBottom + (if (long) 6 else 4) * d, paint)
        }

        // "Now": a bold line through the bar with a small cap on top.
        val nowX = left + barW * periodFraction.toFloat().coerceIn(0f, 1f)
        paint.color = color(R.color.widget_text)
        paint.strokeWidth = 2.2f * d
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(nowX, 2 * d, nowX, barBottom + 3 * d, paint)
        val cap = Path().apply {
            moveTo(nowX - 3.5f * d, 0f)
            lineTo(nowX + 3.5f * d, 0f)
            lineTo(nowX, 4 * d)
            close()
        }
        paint.style = Paint.Style.FILL
        c.drawPath(cap, paint)
        return bmp
    }
}
