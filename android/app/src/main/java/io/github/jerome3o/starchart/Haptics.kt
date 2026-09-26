package io.github.jerome3o.starchart

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Notch ticks that strengthen towards the end, and a celebratory thump. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val amplitude get() = vibrator?.hasAmplitudeControl() == true

    /** [strength] in 0..1: later notches are longer and harder. */
    fun tick(strength: Float) {
        val v = vibrator ?: return
        val s = strength.coerceIn(0f, 1f)
        val effect = when {
            amplitude -> VibrationEffect.createOneShot((6 + 14 * s).toLong(), (40 + 215 * s).toInt())
            Build.VERSION.SDK_INT >= 29 && s < 0.6f -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Build.VERSION.SDK_INT >= 29 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    fun celebrate() {
        val v = vibrator ?: return
        val timings = longArrayOf(0, 45, 55, 45, 55, 140)
        val effect = if (amplitude) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 200, 0, 255), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        v.vibrate(effect)
    }
}
