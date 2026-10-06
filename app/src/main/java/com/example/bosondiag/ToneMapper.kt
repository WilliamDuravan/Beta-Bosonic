package com.example.bosondiag

/**
 * Y16 -> palette-colored ARGB.
 * Optional per-pixel offset (software fixed-pattern correction) is subtracted first, then a
 * smoothed 1%-99% percentile stretch is applied (never narrower than [minRange] counts).
 */
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

    fun map(f: ByteArray, lut: IntArray, offset: IntArray?, minRange: Float, out: IntArray) {
        java.util.Arrays.fill(hist, 0)
        var j = 0
        for (p in 0 until total) {
            var v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            if (offset != null) {
                v -= offset[p]
                if (v < 0) v = 0 else if (v > 65535) v = 65535
            }
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
        var base = lo
        var range = hi - lo
        if (range < minRange) {
            base = (lo + hi) / 2f - minRange / 2f
            range = minRange
        }
        val scale = 255f / range
        for (p in 0 until total) {
            var g = ((vals[p] - base) * scale).toInt()
            if (g < 0) g = 0 else if (g > 255) g = 255
            out[p] = lut[g]
        }
    }
}
