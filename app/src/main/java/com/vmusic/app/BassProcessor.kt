package com.vmusic.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/** mode: 0 = off, 1 = block bass (high-pass), 2 = only bass (low-pass). amount: 0..1 */
@UnstableApi
class BassProcessor : BaseAudioProcessor() {
    @Volatile var mode = 0
    @Volatile var amount = 0f
    @Volatile var cutoff = 150f

    private var ch = 2
    private var rate = 44100
    private var usedCutoff = -1f
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z = FloatArray(0) // [channel][stage][2]

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        ch = f.channelCount; rate = f.sampleRate
        z = FloatArray(ch * 4); usedCutoff = -1f
        return f
    }

    private fun coefs() {
        val w = (2.0 * Math.PI * cutoff / rate).toFloat()
        val alpha = sin(w) / (2f * 0.7071f)
        val c = cos(w)
        val a0 = 1f + alpha
        b0 = ((1f - c) / 2f) / a0; b1 = (1f - c) / a0; b2 = b0
        a1 = (-2f * c) / a0; a2 = (1f - alpha) / a0
        usedCutoff = cutoff
    }

    override fun queueInput(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        if (mode == 0 || amount <= 0f) {
            out.put(input)
        } else {
            if (usedCutoff != cutoff) coefs()
            val a = amount
            while (input.remaining() >= 2 * ch) {
                for (c in 0 until ch) {
                    val x = input.short.toFloat()
                    var y = x
                    for (s in 0..1) { // two cascaded biquads = steeper 24dB/oct filter
                        val i = c * 4 + s * 2
                        val o = b0 * y + z[i]
                        z[i] = b1 * y - a1 * o + z[i + 1]
                        z[i + 1] = b2 * y - a2 * o
                        y = o
                    }
                    val wet = if (mode == 2) y else x - y
                    val r = x * (1f - a) + wet * a
                    out.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
                }
            }
            out.put(input)
        }
        out.flip()
    }
}
