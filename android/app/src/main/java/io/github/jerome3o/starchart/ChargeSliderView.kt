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
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two-way slide-to-log control. The thumb rests in the middle:
 * - slide right to log a completion: the fill blends from the accent colour
 *   into a flowing rainbow;
 * - slide left to log a failure: the fill turns an angry, pulsing red.
 * Progress is signed (-1..1). Notches (denser towards each end) report
 * crossings in both directions so the host can play haptic detents; reaching
 * either end completes, and releasing springs the thumb back to the middle.
 */
class ChargeSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onChargeStart()
        /** Every frame while dragging; negative = towards failure. */
        fun onChargeProgress(progress: Float)
        /** [index] counts out from the middle on that side; [progress] is signed. */
        fun onNotch(index: Int, progress: Float)
        fun onChargeComplete(failed: Boolean)
        fun onChargeRelease(completed: Boolean)
    }

    var listener: Listener? = null
    /** Text on the right (success) half. */
    var label: String = "Done ▶"
        set(value) { field = value; invalidate() }
    /** Text on the left (failure) half. */
    var failLabel: String = "◀ Fail"
        set(value) { field = value; invalidate() }

    /** Notch distances from the middle in (0, 1): sqrt spacing, bunching up near each end. */
    val notches = FloatArray(NOTCH_COUNT) { sqrt((it + 1f) / (NOTCH_COUNT + 1)) }

    private var progress = 0f
    private var dragging = false
    private var completed = false
    private var completedFailed = false
    private var touchOffset = 0f
    private var notchesPassed = 0 // signed: positive on the right, negative on the left
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
        textSize = 13f * density
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
    /** Travel from the middle to either end. */
    private val halfTravel get() = ((width - 2 * (pad + radius)) / 2f).coerceAtLeast(1f)
    private fun thumbX(p: Float) = width / 2f + p * halfTravel

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = h / 2
        val mid = w / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, corner, corner, trackPaint)

        for (n in notches) {
            for (side in intArrayOf(-1, 1)) {
                val passed = side * progress >= n
                notchPaint.alpha = if (passed) 0 else 70
                canvas.drawCircle(thumbX(side * n), h / 2, 1.8f * density, notchPaint)
            }
        }

        // Half labels fade as you commit to either side.
        val labelAlpha = ((1f - abs(progress) * 2.5f).coerceIn(0f, 1f) * 255).toInt()
        if (labelAlpha > 0) {
            labelPaint.alpha = labelAlpha
            val baseline = h / 2 - (labelPaint.ascent() + labelPaint.descent()) / 2
            val leftCentre = (pad + mid - radius) / 2
            val rightCentre = (mid + radius + w - pad) / 2
            canvas.drawText(failLabel, leftCentre, baseline, labelPaint)
            canvas.drawText(label, rightCentre, baseline, labelPaint)
        }

        val tx = thumbX(progress)
        val p = abs(progress)
        if (p > 0.001f) {
            // The fill runs from the middle out to (and under) the thumb.
            if (progress > 0) rect.set(mid - radius - pad, 0f, tx + radius + pad, h)
            else rect.set(tx - radius - pad, 0f, mid + radius + pad, h)
            if (progress > 0) drawSuccessFill(canvas, corner, p, w)
            else drawFailFill(canvas, corner, p)
        }

        val scale = 1f + 0.12f * p
        canvas.drawCircle(tx, h / 2, radius * scale, thumbPaint)
        canvas.drawText(thumbFace(), tx, h / 2 - (starPaint.ascent() + starPaint.descent()) / 2, starPaint)

        if (dragging) {
            phase += 3f + 14f * p
            listener?.onChargeProgress(progress)
            postInvalidateOnAnimation()
        }
    }

    private fun thumbFace() = when {
        completed && completedFailed -> "😞"
        completed -> "🎉"
        progress < -0.7f -> "😱"
        progress < -0.25f -> "😬"
        else -> "⭐"
    }

    private fun drawSuccessFill(canvas: Canvas, corner: Float, p: Float, w: Float) {
        fillPaint.color = accent
        fillPaint.alpha = (255 * (1f - p).coerceIn(0.15f, 1f)).toInt()
        canvas.drawRoundRect(rect, corner, corner, fillPaint)

        // Rainbow flows along the filled area and takes over as you slide.
        for (i in rainbowColors.indices) {
            hsv[0] = ((phase + i * 60f) % 360f + 360f) % 360f
            hsv[1] = 0.85f
            hsv[2] = 1f
            rainbowColors[i] = Color.HSVToColor(hsv)
        }
        rainbowPaint.shader = LinearGradient(0f, 0f, w * 0.6f, 0f, rainbowColors, null, Shader.TileMode.MIRROR)
        rainbowPaint.alpha = (255 * (p * 1.4f).coerceIn(0f, 1f)).toInt()
        canvas.drawRoundRect(rect, corner, corner, rainbowPaint)

        if (p > 0.6f) {
            glowPaint.color = Color.WHITE
            glowPaint.alpha = (((p - 0.6f) / 0.4f) * 90 * (0.6f + 0.4f * sin(phase / 12f))).toInt()
            canvas.drawRoundRect(rect, corner, corner, glowPaint)
        }
    }

    private fun drawFailFill(canvas: Canvas, corner: Float, p: Float) {
        // Blood red that darkens and throbs faster the further you go.
        fillPaint.shader = null
        val throb = 0.5f + 0.5f * sin(phase / 6f)
        val red = (200 + 55 * throb * p).toInt().coerceAtMost(255)
        val dark = (40 * (1f - p)).toInt()
        fillPaint.color = Color.argb((120 + 135 * p).toInt().coerceAtMost(255), red, dark, dark)
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        if (p > 0.6f) {
            glowPaint.color = Color.BLACK
            glowPaint.alpha = (((p - 0.6f) / 0.4f) * 70 * throb).toInt()
            canvas.drawRoundRect(rect, corner, corner, glowPaint)
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
                notchesPassed = passedFor(progress)
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

    /** Signed number of notches behind the thumb: +k on the right, -k on the left. */
    private fun passedFor(p: Float): Int = (p.sign * notches.count { it <= abs(p) }).toInt()

    private fun setProgressFromTouch(x: Float) {
        val p = ((x - touchOffset - width / 2f) / halfTravel).coerceIn(-1f, 1f)
        progress = p
        val passed = passedFor(p)
        if (passed != notchesPassed) {
            // Report every notch crossed, in either direction and across the middle.
            // The boundary between signed states i and i+1 is right-hand notch i
            // when i >= 0, otherwise left-hand notch -i-1.
            for (i in minOf(passed, notchesPassed) until maxOf(passed, notchesPassed)) {
                listener?.onNotch(if (i >= 0) i else -i - 1, p)
            }
            notchesPassed = passed
        }
        if (abs(p) >= 1f && !completed) {
            // Either end is the payoff: complete, drop the gesture and spring back.
            completed = true
            completedFailed = p < 0
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            listener?.onChargeComplete(completedFailed)
            animateBack()
        }
        invalidate()
    }

    private fun animateBack() {
        val from = progress
        springBack = ValueAnimator.ofFloat(from, 0f).apply {
            duration = if (completed) 650 else 380
            interpolator = OvershootInterpolator(if (completed) 0.6f else 1.2f)
            addUpdateListener {
                progress = (it.animatedValue as Float).coerceIn(-1f, 1f)
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
        const val NOTCH_COUNT = 10
    }
}
