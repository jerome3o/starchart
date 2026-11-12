package io.github.jerome3o.starchart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * The anti-celebration for a logged failure: the screen flushes red and fades
 * to a gloomy blue-grey, rain falls, a big sad face sinks in and droops, and a
 * soggy "wah wah" word sags off the bottom. Touch-transparent; removes itself.
 */
class FailView(context: Context, private val word: String = WORDS.random()) : View(context) {

    private class Drop(var x: Float, var y: Float, val speed: Float, val len: Float)

    private val density = resources.displayMetrics.density
    private val drops = mutableListOf<Drop>()
    private val tintPaint = Paint()
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 170, 200)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.CENTER
        color = Color.rgb(170, 185, 210)
    }
    private val wordStroke = Paint(wordPaint).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(30, 35, 55)
        strokeJoin = Paint.Join.ROUND
    }

    private var elapsed = 0f
    private var lastFrameMs = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TOTAL_MS
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                (parent as? ViewGroup)?.removeView(this@FailView)
            }
        })
    }

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (drops.isEmpty() && w > 0) {
            repeat(DROP_COUNT) {
                drops += Drop(
                    x = Random.nextFloat() * w,
                    y = -Random.nextFloat() * h * 1.2f,
                    speed = h * (0.9f + Random.nextFloat() * 0.6f),
                    len = (10 + Random.nextFloat() * 14) * density,
                )
            }
        }
        facePaint.textSize = w * 0.42f
        wordPaint.textSize = 100f
        val fit = 100f * (w * 0.8f) / wordPaint.measureText(word).coerceAtLeast(1f)
        wordPaint.textSize = minOf(w * 0.2f, fit)
        wordStroke.textSize = wordPaint.textSize
        wordStroke.strokeWidth = wordPaint.textSize * 0.12f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameMs = 0L
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
        lastFrameMs = now
        elapsed += dt
        val t = elapsed
        val total = TOTAL_MS / 1000f
        val fadeOut = ((total - t) / 0.7f).coerceIn(0f, 1f)

        // Red flash that cools into a gloomy blue-grey.
        val flash = (1f - t / 0.5f).coerceIn(0f, 1f)
        val r = (200 * flash + 40 * (1 - flash)).toInt()
        val g = (20 * flash + 48 * (1 - flash)).toInt()
        val b = (20 * flash + 70 * (1 - flash)).toInt()
        tintPaint.color = Color.argb((170 * fadeOut).toInt(), r, g, b)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)

        // Rain.
        dropPaint.alpha = (200 * fadeOut).toInt()
        for (d in drops) {
            d.y += d.speed * dt
            if (d.y > height) d.y -= height * 1.2f
            canvas.drawLine(d.x, d.y, d.x - d.len * 0.15f, d.y + d.len, dropPaint)
        }

        // The sad face sinks in from above and droops, swaying.
        val faceIn = (t / 0.6f).coerceIn(0f, 1f)
        val ease = 1 - (1 - faceIn) * (1 - faceIn)
        val faceY = height * (-0.2f + 0.6f * ease) + (if (t > 1.2f) (t - 1.2f) * 30 * density else 0f)
        canvas.save()
        canvas.translate(width / 2f, faceY)
        canvas.rotate(6f * sin(t * 2.2f))
        facePaint.alpha = (255 * fadeOut).toInt()
        canvas.drawText(FACES[(word.length) % FACES.size], 0f, -(facePaint.ascent() + facePaint.descent()) / 2, facePaint)
        canvas.restore()

        // "wah wah": appears late, then sags and slides off.
        if (t > 0.35f) {
            val wt = t - 0.35f
            val sag = wt * wt * 40 * density
            canvas.save()
            canvas.translate(width / 2f, height * 0.72f + sag)
            canvas.rotate(4f + wt * 6f)
            canvas.scale(1f, 1f + 0.08f * wt) // getting soggy
            val alpha = (255 * (wt / 0.3f).coerceIn(0f, 1f) * fadeOut).toInt()
            wordStroke.alpha = alpha
            wordPaint.alpha = alpha
            val baseline = -(wordPaint.ascent() + wordPaint.descent()) / 2
            canvas.drawText(word, 0f, baseline, wordStroke)
            canvas.drawText(word, 0f, baseline, wordPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val TOTAL_MS = 3400L
        private const val DROP_COUNT = 90
        private val FACES = listOf("😞", "😢", "😔")
        private val WORDS = listOf("wah wah", "wah wah wahhh", "oof", "唉…")

        /** Adds the sad overlay on top of everything in [activityRoot]. */
        fun show(activityRoot: ViewGroup) {
            activityRoot.addView(
                FailView(activityRoot.context),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }
}

/**
 * A red wash over the whole screen that deepens as the slider heads towards
 * failure ("everything goes red"). Touch-transparent; added on demand.
 */
class FailTint(private val root: ViewGroup) {
    private var view: View? = null

    /** [progress] is the slider's signed progress; only the failure side tints. */
    fun update(progress: Float) {
        val p = (-progress).coerceIn(0f, 1f)
        if (p <= 0f) {
            view?.alpha = 0f
            return
        }
        val v = view ?: View(root.context).apply {
            setBackgroundColor(Color.rgb(220, 0, 0))
            isClickable = false
            isFocusable = false
            root.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.also { view = it }
        v.alpha = 0.55f * p * p
    }

    fun clear() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(250).withEndAction { root.removeView(v) }.start()
    }
}
