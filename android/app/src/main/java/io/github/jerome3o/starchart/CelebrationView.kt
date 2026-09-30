package io.github.jerome3o.starchart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen, touch-transparent celebration: a confetti burst that falls
 * across the screen and a big rainbow WordArt "NICE!" that pops in, wobbles
 * and fades. Removes itself when done.
 */
class CelebrationView(context: Context, private val word: String = WORDS.random()) : View(context) {

    private class Piece(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var angle: Float, val spin: Float, val w: Float, val h: Float,
        val color: Int, val round: Boolean, val wobble: Float,
    )

    private val density = resources.displayMetrics.density
    private val pieces = mutableListOf<Piece>()
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSkewX = -0.22f
    }
    private val innerStroke = Paint(fillPaint).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
    }
    private val outerStroke = Paint(fillPaint).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(40, 20, 70)
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(fillPaint).apply { color = Color.argb(110, 0, 0, 0) }

    private var elapsed = 0f
    private var lastFrameMs = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TOTAL_MS
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                (parent as? ViewGroup)?.removeView(this@CelebrationView)
            }
        })
    }

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pieces.isEmpty() && w > 0) spawn(w.toFloat(), h.toFloat())
        // Big, but shrunk to fit longer words within ~88% of the width.
        fillPaint.textSize = 100f
        val fit = 100f * (w * 0.88f) / fillPaint.measureText(word).coerceAtLeast(1f)
        val size = minOf(w * 0.27f, fit)
        listOf(fillPaint, innerStroke, outerStroke, shadowPaint).forEach { it.textSize = size }
        innerStroke.strokeWidth = size * 0.10f
        outerStroke.strokeWidth = size * 0.19f
        fillPaint.shader = LinearGradient(
            0f, h / 2f - size * 0.8f, 0f, h / 2f + size * 0.1f,
            intArrayOf(Color.rgb(255, 236, 64), Color.rgb(255, 140, 26), Color.rgb(255, 64, 129), Color.rgb(170, 60, 255)),
            null, Shader.TileMode.CLAMP,
        )
    }

    private fun spawn(w: Float, h: Float) {
        val colors = intArrayOf(
            0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(),
            0xFFE040FB.toInt(), 0xFFFF6E40.toInt(), 0xFFFFFFFF.toInt(),
        )
        repeat(PIECE_COUNT) {
            // Two cannons from the bottom corners plus a sprinkle from the top.
            val from = Random.nextInt(3)
            val (x, y, angle, speed) = when (from) {
                0 -> listOf(0f, h, (-PI / 2 + Random.nextDouble(0.1, 0.9)).toFloat(), Random.nextFloat() * 1.3f + 1.1f)
                1 -> listOf(w, h, (-PI / 2 - Random.nextDouble(0.1, 0.9)).toFloat(), Random.nextFloat() * 1.3f + 1.1f)
                else -> listOf(Random.nextFloat() * w, -20 * density, (PI / 2).toFloat(), Random.nextFloat() * 0.3f)
            }
            val v = speed * h * 1.25f
            pieces += Piece(
                x = x, y = y,
                vx = cos(angle) * v + (if (from == 2) (Random.nextFloat() - 0.5f) * 60 * density else 0f),
                vy = sin(angle) * v,
                angle = Random.nextFloat() * 360f, spin = (Random.nextFloat() - 0.5f) * 900f,
                w = (5 + Random.nextFloat() * 7) * density, h = (8 + Random.nextFloat() * 10) * density,
                color = colors[Random.nextInt(colors.size)], round = Random.nextFloat() < 0.25f,
                wobble = Random.nextFloat() * 6f,
            )
        }
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

        val gravity = height * 1.6f
        val fadeOut = ((TOTAL_MS / 1000f - elapsed) / 0.6f).coerceIn(0f, 1f)
        for (p in pieces) {
            p.vy += gravity * dt
            p.vx *= (1f - 1.2f * dt)
            p.vy = p.vy.coerceAtMost(height * 0.55f) // flutter at terminal velocity
            p.x += (p.vx + sin(elapsed * 5 + p.wobble) * 30 * density) * dt
            p.y += p.vy * dt
            p.angle += p.spin * dt
            if (p.y > height + 40 * density) continue
            piecePaint.color = p.color
            piecePaint.alpha = (255 * fadeOut).toInt()
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.angle)
            // A flip in 3D, faked by squashing one axis.
            canvas.scale(1f, cos(p.angle * 0.05f))
            if (p.round) canvas.drawCircle(0f, 0f, p.w / 2, piecePaint)
            else canvas.drawRect(-p.w / 2, -p.h / 2, p.w / 2, p.h / 2, piecePaint)
            canvas.restore()
        }

        drawWord(canvas)
    }

    private fun drawWord(canvas: Canvas) {
        val t = elapsed
        if (t > WORD_OUT_S + 0.35f) return
        // Pop in with an overshoot, hold with a gentle pulse, then shrink away.
        val scale = when {
            t < 0.18f -> (t / 0.18f) * 1.25f
            t < 0.32f -> 1.25f - (t - 0.18f) / 0.14f * 0.25f
            t < WORD_OUT_S -> 1f + 0.04f * sin((t - 0.32f) * 9f)
            else -> 1f - (t - WORD_OUT_S) / 0.35f
        }.coerceAtLeast(0f)
        val alpha = if (t < WORD_OUT_S) 255 else (255 * (1f - (t - WORD_OUT_S) / 0.35f)).toInt().coerceIn(0, 255)
        val cx = width / 2f
        val cy = height * 0.42f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-8f + 3f * sin(t * 4f))
        canvas.scale(scale, scale)
        val baseline = -(fillPaint.ascent() + fillPaint.descent()) / 2
        val off = fillPaint.textSize * 0.06f
        listOf(shadowPaint, outerStroke, innerStroke, fillPaint).forEach { it.alpha = alpha }
        canvas.drawText(word, off, baseline + off, shadowPaint)
        canvas.drawText(word, 0f, baseline, outerStroke)
        canvas.drawText(word, 0f, baseline, innerStroke)
        canvas.drawText(word, 0f, baseline, fillPaint)
        canvas.restore()
    }

    companion object {
        private const val PIECE_COUNT = 320
        private val WORDS = listOf(
            "pog", "poggers", "gzgzgz", "great stuff",
            "很好", "太棒了！", "恭喜你",
        )
        private const val TOTAL_MS = 3200L
        private const val WORD_OUT_S = 1.5f

        /** Adds a celebration on top of everything in [activityRoot]. */
        fun show(activityRoot: ViewGroup) {
            val view = CelebrationView(activityRoot.context)
            activityRoot.addView(
                view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }
}
