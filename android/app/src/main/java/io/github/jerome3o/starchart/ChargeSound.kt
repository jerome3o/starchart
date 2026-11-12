package io.github.jerome3o.starchart

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesized "charging" sound for the log slider: a rising two-voice tone
 * whose pitch, loudness and tremolo speed follow [level], plus a bright
 * arpeggio [chime] on completion. A negative level (sliding towards failure)
 * sinks into a dissonant growl, and [wahWah] plays a sad trombone.
 * Streams from its own thread; no audio files.
 */
class ChargeSound {

    @Volatile var level = 0f
    @Volatile private var active = false
    @Volatile private var chimeRequested = false
    @Volatile private var wahRequested = false
    private var thread: Thread? = null

    fun start() {
        active = true
        if (thread?.isAlive != true) {
            thread = Thread(::run, "charge-sound").apply { isDaemon = true; start() }
        }
    }

    /** Fades out; the thread exits once silent (after any chime finishes). */
    fun stop() {
        active = false
    }

    fun chime() {
        chimeRequested = true
        if (thread?.isAlive != true) {
            thread = Thread(::run, "charge-sound").apply { isDaemon = true; start() }
        }
    }

    /** Sad trombone: three falling notes and a long, wobbling last one. */
    fun wahWah() {
        wahRequested = true
        if (thread?.isAlive != true) {
            thread = Thread(::run, "charge-sound").apply { isDaemon = true; start() }
        }
    }

    private fun run() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }
        track.play()

        val buf = ShortArray(512)
        var volume = 0.0
        var freq = BASE_FREQ
        var p1 = 0.0
        var p2 = 0.0
        var trem = 0.0
        var chimeT = -1.0
        var wahT = -1.0
        var wahPhase = 0.0
        val twoPi = 2 * PI

        try {
            while (true) {
                if (chimeRequested) {
                    chimeRequested = false
                    chimeT = 0.0
                }
                if (wahRequested) {
                    wahRequested = false
                    wahT = 0.0
                }
                val chiming = chimeT in 0.0..CHIME_LENGTH
                val wahing = wahT in 0.0..WAH_LENGTH
                if (!active && volume < 0.0005 && !chiming && !wahing) break

                val signed = level.toDouble().coerceIn(-1.0, 1.0)
                val failing = signed < 0
                val lvl = kotlin.math.abs(signed)
                val targetVol = if (active) 0.06 + 0.3 * lvl else 0.0
                // Success climbs; failure sinks towards a low growl.
                val targetFreq = if (failing) BASE_FREQ - 55 * Math.pow(lvl, 0.8)
                    else BASE_FREQ + 620 * Math.pow(lvl, 1.4)
                val tremRate = if (failing) 5 + 9 * lvl else 3 + 24 * lvl
                // A fifth above sounds bright; a minor second above sounds wrong.
                val interval = if (failing) 1.06 else 1.5

                for (i in buf.indices) {
                    volume += (targetVol - volume) * 0.0015
                    freq += (targetFreq - freq) * 0.003
                    p1 += twoPi * freq / RATE
                    p2 += twoPi * freq * interval / RATE
                    trem += twoPi * tremRate / RATE
                    if (p1 > twoPi) p1 -= twoPi
                    if (p2 > twoPi) p2 -= twoPi
                    if (trem > twoPi) trem -= twoPi

                    val saw = (p1 / PI) - 1 // brightness grows with level
                    val grit = if (failing) 0.35 else 0.12
                    var s = (0.6 * sin(p1) + 0.3 * sin(p2) + grit * lvl * saw) * volume *
                        (0.7 + 0.3 * sin(trem))

                    if (wahT >= 0 && wahT <= WAH_LENGTH) {
                        s += wahSample(wahT, wahPhase).also {
                            wahPhase += twoPi * wahFreq(wahT) / RATE
                            if (wahPhase > twoPi) wahPhase -= twoPi
                        }
                        wahT += 1.0 / RATE
                    }

                    if (chimeT >= 0 && chimeT <= CHIME_LENGTH) {
                        for ((n, f) in CHIME_NOTES.withIndex()) {
                            val start = n * 0.07
                            if (chimeT >= start) {
                                val t = chimeT - start
                                s += 0.22 * sin(twoPi * f * t) * exp(-t * 4.5) +
                                    0.06 * sin(twoPi * f * 2 * t) * exp(-t * 7)
                            }
                        }
                        chimeT += 1.0 / RATE
                    }
                    buf[i] = (s.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    /** Pitch of the sad trombone at time [t]: which note, plus vibrato on the last. */
    private fun wahFreq(t: Double): Double {
        var start = 0.0
        for ((n, len) in WAH_LENGTHS.withIndex()) {
            if (t < start + len || n == WAH_LENGTHS.lastIndex) {
                val f = WAH_NOTES[n]
                val local = t - start
                return if (n == WAH_LENGTHS.lastIndex && local > 0.25) {
                    // The droopy wobble, sagging a little as it goes.
                    f * (1 + 0.025 * sin(2 * PI * 6.0 * local)) * (1 - 0.03 * (local - 0.25))
                } else f
            }
            start += len
        }
        return WAH_NOTES.last()
    }

    /** One sample of the muted-brass voice: harmonics that open and close per note ("wah"). */
    private fun wahSample(t: Double, phase: Double): Double {
        var start = 0.0
        var local = t
        var len = WAH_LENGTHS.last()
        for (l in WAH_LENGTHS) {
            if (t < start + l) { local = t - start; len = l; break }
            start += l
        }
        val attack = (local / 0.03).coerceAtMost(1.0)
        val release = ((len - local) / 0.08).coerceIn(0.0, 1.0)
        val env = attack * release
        // The mute opens over the first part of each note, then closes.
        val open = sin(PI * (local / len).coerceIn(0.0, 1.0)).coerceAtLeast(0.15)
        var v = 0.0
        for (h in 1..7) v += sin(phase * h) * Math.pow(open, (h - 1) * 0.6) / h
        return 0.2 * v * env
    }

    companion object {
        private const val RATE = 44100
        // D4, C#4, C4, B3: wah, wah, wah, waaaah.
        private val WAH_NOTES = doubleArrayOf(293.66, 277.18, 261.63, 246.94)
        private val WAH_LENGTHS = doubleArrayOf(0.42, 0.42, 0.42, 1.5)
        private val WAH_LENGTH = WAH_LENGTHS.sum()
        private const val BASE_FREQ = 110.0
        private const val CHIME_LENGTH = 1.4
        private val CHIME_NOTES = doubleArrayOf(1046.5, 1318.5, 1568.0, 2093.0) // C6 E6 G6 C7
    }
}
