package com.example.bosondiag

/** Percentile auto-contrast: 16-bit Y16 samples -> 8-bit gray ARGB, with smoothed range. */
class ToneMapper(private val w: Int, private val h: Int) {
    private val total = w * h
    private val vals = IntArray(total)
    private val hist = IntArray(16384)
    private var haveRange = false
    @Volatile var lo = 0f
    @Volatile var hi = 0f

    fun reset() {
        haveRange = false
    }

    fun map(f: ByteArray, invert: Boolean, out: IntArray) {
        java.util.Arrays.fill(hist, 0)
        var j = 0
        for (p in 0 until total) {
            val v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            vals[p] = v
            hist[v shr 2]++
        }
        val lowTarget = total / 100
        val highTarget = total - total / 100
        var acc = 0
        var loBin = 0
        var hiBin = hist.size - 1
        var gotLo = false
        for (b in hist.indices) {
            acc += hist[b]
            if (!gotLo && acc >= lowTarget) {
                loBin = b
                gotLo = true
            }
            if (acc >= highTarget) {
                hiBin = b
                break
            }
        }
        val loV = loBin * 4f
        val hiV = hiBin * 4f + 3f
        if (!haveRange) {
            lo = loV
            hi = hiV
            haveRange = true
        } else {
            lo += (loV - lo) * 0.1f
            hi += (hiV - hi) * 0.1f
        }
        val range = maxOf(hi - lo, 64f)
        val scale = 255f / range
        val l = lo
        for (p in 0 until total) {
            var g = ((vals[p] - l) * scale).toInt()
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (invert) g = 255 - g
            out[p] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }
}
