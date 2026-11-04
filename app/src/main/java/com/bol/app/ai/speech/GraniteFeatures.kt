package com.bol.app.ai.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Granite Speech's log-mel + delta front-end for 16 kHz audio. */
internal object GraniteFeatures {
    private const val SAMPLE_RATE = 16000
    private const val NFFT = 512
    private const val WIN = 400
    private const val HOP = 160
    private const val MELS = 80
    private val window = FloatArray(NFFT).also { w ->
        val p = (NFFT - WIN) / 2
        for (i in 0 until WIN) w[p + i] = (0.5 - 0.5 * cos(2 * PI * i / WIN)).toFloat()
    }
    private val filters = buildFilters()
    private val cosT = DoubleArray(NFFT / 2) { cos(2 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { -sin(2 * PI * it / NFFT) }

    private fun htk(f: Double) = 2595 * log10(1 + f / 700)
    private fun inv(m: Double) = 700 * (10.0.pow(m / 2595) - 1)

    private fun buildFilters(): Array<FloatArray> {
        val top = htk(SAMPLE_RATE / 2.0)
        val pts = DoubleArray(MELS + 2) { inv(top * it / (MELS + 1)) }
        val bins = NFFT / 2 + 1
        return Array(MELS) { m ->
            FloatArray(bins) { k ->
                val f = k * (SAMPLE_RATE / 2.0) / (bins - 1)
                max(0.0, min((f - pts[m]) / (pts[m + 1] - pts[m]), (pts[m + 2] - f) / (pts[m + 2] - pts[m + 1]))).toFloat()
            }
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until NFFT) {
            var bit = NFFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= NFFT) {
            val step = NFFT / len
            for (i in 0 until NFFT step len) {
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    /** Returns [frames/2 x 320] flattened, for a padded window of 5, 10 or 30 s. */
    fun compute(w: FloatArray): FloatArray {
        val nf = 2 * ((w.size / HOP + 1) / 2)
        val pad = NFFT / 2
        val x = FloatArray(w.size + 2 * pad)
        for (i in w.indices) x[i + pad] = w[i]
        for (i in 0 until pad) { x[pad - 1 - i] = w[i + 1]; x[pad + w.size + i] = w[w.size - 2 - i] }
        val mel = Array(nf) { FloatArray(MELS) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        var top = Float.NEGATIVE_INFINITY
        for (t in 0 until nf) {
            for (i in 0 until NFFT) { re[i] = (x[t * HOP + i] * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            for (m in 0 until MELS) {
                var e = 0.0
                val fb = filters[m]
                for (k in 0..NFFT / 2) if (fb[k] != 0f) e += fb[k] * (re[k] * re[k] + im[k] * im[k])
                val v = log10(max(e, 1e-10)).toFloat()
                mel[t][m] = v
                if (v > top) top = v
            }
        }
        for (t in 0 until nf) for (m in 0 until MELS) mel[t][m] = max(mel[t][m], top - 8f) / 4 + 1
        val out = FloatArray(nf * 160)
        for (t in 0 until nf) {
            val prev = mel[max(0, t - 1)]; val next = mel[min(nf - 1, t + 1)]
            for (m in 0 until MELS) {
                out[t * 160 + m] = mel[t][m]
                out[t * 160 + 80 + m] = (next[m] - prev[m]) / 2
            }
        }
        return out
    }
}
