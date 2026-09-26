package io.github.jerome3o.starchart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Slide-to-log control. The dragged region blends from the accent colour into
 * a flowing rainbow as it fills; notches (denser towards the end) report
 * crossings in both directions so the host can play haptic detents; reaching
 * the end completes, and releasing springs the thumb back.
 */
class ChargeSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onChargeStart()
        /** Called every frame while dragging (and when progress changes). */
        fun onChargeProgress(progress: Float)
        fun onNotch(index: Int, progress: Float)
        fun onChargeComplete()
        fun onChargeRelease(completed: Boolean)
    }

    var listener: Listener? = null
    var label: String = "Slide to log"
        set(value) { field = value; invalidate() }

    /** Notch positions in (0, 1): sqrt spacing, so they bunch up near the end. */
    val notches = FloatArray(NOTCH_COUNT) { sqrt((it + 1f) / (NOTCH_COUNT + 1)) }

    private var progress = 0f
    private var dragging = false
    private var completed = false
    private var touchOffset = 0f
    private var notchesPassed = 0
    private var phase = 0f
    private var springBack: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@ChargeSliderView, com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@ChargeSliderView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(3f * density, 0f, 1f * density, 0x55000000)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@ChargeSliderView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f * density
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val hsv = FloatArray(3)
    private val rainbowColors = IntArray(7)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the thumb shadow
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((56 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private val pad get() = 4f * density
    private val radius get() = height / 2f - pad
    private val travel get() = (width - 2 * (pad + radius)).coerceAtLeast(1f)
    private fun thumbX(p: Float) = pad + radius + p * travel

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, corner, corner, trackPaint)

        for (n in notches) {
            val x = thumbX(n)
            notchPaint.alpha = if (n <= progress) 0 else 70
            canvas.drawCircle(x, h / 2, 1.8f * density, notchPaint)
        }

        val labelAlpha = ((1f - progress * 2.5f).coerceIn(0f, 1f) * 255).toInt()
        if (labelAlpha > 0) {
            labelPaint.alpha = labelAlpha
            canvas.drawText(label, w / 2 + radius, h / 2 - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
        }

        val tx = thumbX(progress)
        if (progress > 0.001f || dragging) {
            rect.set(0f, 0f, tx + radius + pad, h)
            fillPaint.color = accent
            fillPaint.alpha = (255 * (1f - progress).coerceIn(0.15f, 1f)).toInt()
            canvas.drawRoundRect(rect, corner, corner, fillPaint)

            // Rainbow flows along the filled area and takes over as you slide.
            for (i in rainbowColors.indices) {
                hsv[0] = ((phase + i * 60f) % 360f + 360f) % 360f
                hsv[1] = 0.85f
                hsv[2] = 1f
                rainbowColors[i] = Color.HSVToColor(hsv)
            }
            rainbowPaint.shader = LinearGradient(0f, 0f, w * 0.6f, 0f, rainbowColors, null, Shader.TileMode.MIRROR)
            rainbowPaint.alpha = (255 * (progress * 1.4f).coerceIn(0f, 1f)).toInt()
            canvas.drawRoundRect(rect, corner, corner, rainbowPaint)

            if (progress > 0.6f) {
                glowPaint.color = Color.WHITE
                glowPaint.alpha = (((progress - 0.6f) / 0.4f) * 90 * (0.6f + 0.4f * kotlin.math.sin(phase / 12f))).toInt()
                canvas.drawRoundRect(rect, corner, corner, glowPaint)
            }
        }

        val scale = 1f + 0.12f * progress
        canvas.drawCircle(tx, h / 2, radius * scale, thumbPaint)
        canvas.drawText(if (completed) "🎉" else "⭐", tx, h / 2 - (starPaint.ascent() + starPaint.descent()) / 2, starPaint)

        if (dragging) {
            phase += 3f + 14f * progress
            listener?.onChargeProgress(progress)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tx = thumbX(progress)
                if (abs(event.x - tx) > radius * 1.6f) return false
                springBack?.cancel()
                dragging = true
                completed = false
                touchOffset = event.x - tx
                notchesPassed = notches.count { it <= progress }
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onChargeStart()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                setProgressFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                listener?.onChargeRelease(completed)
                animateBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setProgressFromTouch(x: Float) {
        val p = ((x - touchOffset - pad - radius) / travel).coerceIn(0f, 1f)
        progress = p
        val passed = notches.count { it <= p }
        if (passed != notchesPassed) {
            // Report every notch crossed, forwards or backwards.
            val range = if (passed > notchesPassed) notchesPassed until passed else passed until notchesPassed
            for (i in range) listener?.onNotch(i, p)
            notchesPassed = passed
        }
        if (p >= 1f && !completed) {
            completed = true
            listener?.onChargeComplete()
        }
        invalidate()
    }

    private fun animateBack() {
        val from = progress
        springBack = ValueAnimator.ofFloat(from, 0f).apply {
            duration = if (completed) 650 else 380
            interpolator = OvershootInterpolator(if (completed) 0.6f else 1.2f)
            addUpdateListener {
                progress = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    completed = false
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        springBack?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val NOTCH_COUNT = 18
    }
}
