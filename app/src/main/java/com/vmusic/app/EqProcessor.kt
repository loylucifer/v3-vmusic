package com.vmusic.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

val EQ_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** 10-band peaking EQ + preamp + volume gain (0..300%) + soft limiter. Same sound on every phone. */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {
    @Volatile var enabled = true
    @Volatile var gains = FloatArray(10)      // dB, -12..+12
    @Volatile var preampDb = 0f
    @Volatile var volumePct = 100
    @Volatile private var version = 0
    fun changed() { version++ }

    private var ch = 2; private var rate = 44100; private var usedVer = -1
    private var coef = FloatArray(0)          // per band: b0,b1,b2,a1,a2
    private var active = BooleanArray(10)
    private var z = FloatArray(0)             // [ch][band][2]
    private var lin = 1f

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        ch = f.channelCount; rate = f.sampleRate
        z = FloatArray(ch * 20); usedVer = -1
        return f
    }

    private fun build() {
        val g = gains; coef = FloatArray(50)
        for (b in 0 until 10) {
            val f = EQ_FREQS[b].toDouble()
            active[b] = enabled && abs(g[b]) > 0.05f && f < rate / 2.0
            if (!active[b]) continue
            val a = 10.0.pow(g[b] / 40.0)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * 1.41)
            val c = cos(w)
            val a0 = 1 + alpha / a
            coef[b * 5] = ((1 + alpha * a) / a0).toFloat()
            coef[b * 5 + 1] = ((-2 * c) / a0).toFloat()
            coef[b * 5 + 2] = ((1 - alpha * a) / a0).toFloat()
            coef[b * 5 + 3] = ((-2 * c) / a0).toFloat()
            coef[b * 5 + 4] = ((1 - alpha / a) / a0).toFloat()
        }
        lin = (volumePct / 100f) * (if (enabled) 10f.pow(preampDb / 20f) else 1f)
        usedVer = version
    }

    override fun queueInput(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        if (usedVer != version) build()
        val bypass = lin == 1f && active.none { it }
        if (bypass) { out.put(input) } else {
            val knee = 26000f; val room = 32767f - knee
            while (input.remaining() >= 2 * ch) {
                for (c in 0 until ch) {
                    var y = input.short.toFloat()
                    for (b in 0 until 10) {
                        if (!active[b]) continue
                        val i = c * 20 + b * 2; val k = b * 5
                        val o = coef[k] * y + z[i]
                        z[i] = coef[k + 1] * y - coef[k + 3] * o + z[i + 1]
                        z[i + 1] = coef[k + 2] * y - coef[k + 4] * o
                        y = o
                    }
                    y *= lin
                    val a = abs(y)
                    if (a > knee) y = sign(y) * (knee + room * tanh((a - knee) / room)) // soft limiter, no hard clipping
                    out.putShort(y.coerceIn(-32768f, 32767f).toInt().toShort())
                }
            }
            out.put(input)
        }
        out.flip()
    }
}
