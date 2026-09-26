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
 * arpeggio [chime] on completion. Streams from its own thread; no audio files.
 */
class ChargeSound {

    @Volatile var level = 0f
    @Volatile private var active = false
    @Volatile private var chimeRequested = false
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
        val twoPi = 2 * PI

        try {
            while (true) {
                if (chimeRequested) {
                    chimeRequested = false
                    chimeT = 0.0
                }
                val chiming = chimeT in 0.0..CHIME_LENGTH
                if (!active && volume < 0.0005 && !chiming) break

                val lvl = level.toDouble().coerceIn(0.0, 1.0)
                val targetVol = if (active) 0.06 + 0.3 * lvl else 0.0
                val targetFreq = BASE_FREQ + 620 * Math.pow(lvl, 1.4)
                val tremRate = 3 + 24 * lvl

                for (i in buf.indices) {
                    volume += (targetVol - volume) * 0.0015
                    freq += (targetFreq - freq) * 0.003
                    p1 += twoPi * freq / RATE
                    p2 += twoPi * freq * 1.5 / RATE // a fifth above
                    trem += twoPi * tremRate / RATE
                    if (p1 > twoPi) p1 -= twoPi
                    if (p2 > twoPi) p2 -= twoPi
                    if (trem > twoPi) trem -= twoPi

                    val saw = (p1 / PI) - 1 // brightness grows with level
                    var s = (0.6 * sin(p1) + 0.3 * sin(p2) + 0.12 * lvl * saw) * volume *
                        (0.7 + 0.3 * sin(trem))

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

    companion object {
        private const val RATE = 44100
        private const val BASE_FREQ = 110.0
        private const val CHIME_LENGTH = 1.4
        private val CHIME_NOTES = doubleArrayOf(1046.5, 1318.5, 1568.0, 2093.0) // C6 E6 G6 C7
    }
}
