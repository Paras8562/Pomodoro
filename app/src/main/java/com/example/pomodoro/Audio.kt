package com.example.pomodoro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Everything is generated in code, so there are no audio files in the app. */
class SoundEngine {
    private val sr = 44100
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, ShortArray>()

    private class Cue(val n: List<Double>, val gap: Double, val d: Double, val v: Double)
    private val cues = mapOf(
        "focus" to Cue(listOf(523.25, 659.25, 783.99, 1046.5), .13, .5, .3),            // bright, rising
        "short" to Cue(listOf(783.99, 587.33), .22, .9, .28),                              // gentle fall
        "long" to Cue(listOf(659.25, 523.25, 392.0, 261.63), .24, 1.6, .3),                // slow warm descent
        "done" to Cue(listOf(523.25, 659.25, 783.99, 1046.5, 1318.5), .12, 1.1, .3),       // celebratory climb
    )

    private fun buf(sec: Double) = FloatArray((sec * sr).toInt())

    /** Soft bell: sine plus two quiet inharmonic partials, quick attack, long decay. */
    private fun tone(b: FloatArray, f: Double, start: Double, d: Double, v: Double, bell: Boolean) {
        val parts = if (bell) listOf(f to v, f * 2.76 to v * .22, f * 5.4 to v * .08) else listOf(f to v)
        val s0 = (start * sr).toInt()
        val n = (d * sr).toInt()
        for ((fr, g) in parts) {
            val w = 2 * PI * fr / sr
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= b.size) break
                val t = i.toDouble() / sr
                val env = if (t < .012) g * t / .012 else g * (1e-4).pow((t - .012) / (d - .012))
                b[idx] += (sin(w * i) * env).toFloat()
            }
        }
    }

    /** Woody click: a short burst of band-passed noise. */
    private fun click(b: FloatArray, f: Double, v: Double) {
        val w0 = 2 * PI * f / sr
        val alpha = sin(w0) / (2 * 3.0)
        val b0 = alpha; val b2 = -alpha
        val a0 = 1 + alpha; val a1 = -2 * cos(w0); val a2 = 1 - alpha
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val rnd = Random(1)
        val n = (.045 * sr).toInt()
        for (i in 0 until n) {
            if (i >= b.size) break
            val x = rnd.nextDouble() * 2 - 1
            val y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            b[i] += (y * v * (1e-4).pow(i.toDouble() / sr / .045)).toFloat()
        }
    }

    private fun make(name: String): ShortArray = cache.getOrPut(name) {
        val b = when (name) {
            "soft" -> buf(.12).also { tone(it, 720.0, 0.0, .07, .16, false) }
            "tick" -> buf(.1).also { click(it, 2300.0, 2.4); tone(it, 760.0, 0.0, .05, .07, false) }
            "tock" -> buf(.1).also { click(it, 1400.0, 1.8); tone(it, 520.0, 0.0, .05, .07, false) }
            else -> {
                val c = cues.getValue(name)
                val last = c.n.lastIndex
                buf(c.n.size * c.gap + c.d * 1.5 + .1).also { out ->
                    c.n.forEachIndexed { i, f -> tone(out, f, i * c.gap, if (i == last) c.d * 1.5 else c.d, c.v, true) }
                }
            }
        }
        ShortArray(b.size) { (b[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }

    fun warm() {
        Thread { listOf("soft", "tick", "tock", "focus", "short", "long", "done").forEach { make(it) } }.start()
    }

    /** vol is 0..100; a squared curve feels more natural than linear. */
    fun play(name: String, vol: Int) {
        if (vol <= 0) return
        try {
            val pcm = make(name)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.setVolume((vol / 100f).pow(2))
            track.play()
            main.postDelayed({ try { track.release() } catch (_: Exception) {} }, pcm.size * 1000L / sr + 250)
        } catch (_: Exception) {}
    }
}
