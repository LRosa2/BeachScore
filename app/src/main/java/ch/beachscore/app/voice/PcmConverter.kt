package ch.beachscore.app.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A recorder format: sample rate and channel count (16-bit PCM always). */
data class CaptureFormat(val sampleRate: Int, val channels: Int)

/**
 * Formats to try when opening a microphone, best first. 16 kHz mono (what Vosk wants, no
 * conversion) always comes first; Android normally resamples to it. A device that refuses it
 * is opened at one of its own rates (as reported by `AudioDeviceInfo`; empty means "any"):
 * exact multiples of 16 kHz first, then the closest to 48 kHz, rates below 16 kHz last.
 */
fun captureFormats(deviceRates: IntArray, deviceChannelCounts: IntArray): List<CaptureFormat> {
    val rates = (if (deviceRates.isEmpty()) intArrayOf(16_000, 48_000, 44_100) else deviceRates)
        .filter { it > 0 }
        .distinct()
        .sortedWith(compareBy({ it != 16_000 }, { it < 16_000 }, { it % 16_000 != 0 }, { abs(it - 48_000) }))
    val channels = (if (deviceChannelCounts.isEmpty()) intArrayOf(1, 2) else deviceChannelCounts)
        .filter { it in 1..8 }
        .distinct()
        .sorted()
    val formats = mutableListOf(CaptureFormat(CommandRecognizer.SAMPLE_RATE, 1))
    for (rate in rates) for (ch in channels) formats += CaptureFormat(rate, ch)
    return formats.distinct()
}

/**
 * Turns interleaved 16-bit PCM at any rate and channel count into 16 kHz mono for Vosk:
 * channels are averaged, then a windowed-sinc low-pass resampler (cut-off just below the
 * lower of the two Nyquist frequencies, so nothing above 8 kHz folds back as a false sound).
 * Streaming: state is kept between calls, so converting in pieces gives the same result.
 */
class PcmConverter(
    private val inRate: Int,
    private val inChannels: Int,
    private val outRate: Int = CommandRecognizer.SAMPLE_RATE,
) {
    val isPassthrough: Boolean = inRate == outRate && inChannels == 1

    private val step = inRate.toDouble() / outRate

    /** Cut-off as a fraction of the input Nyquist frequency. */
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95

    /** Kernel half-width in input samples: eight zero crossings each side. */
    private val halfWidth = 8.0 / cutoff

    private var buf = FloatArray(4096)
    private var len = 0
    private var pos = 0.0

    /** Converts the first [n] samples (all channels counted) of [input]. */
    fun convert(input: ShortArray, n: Int): ShortArray {
        if (isPassthrough) return input.copyOf(n)
        val frames = n / inChannels
        if (inRate == outRate) return ShortArray(frames) { f -> mix(input, f).roundToInt().toShort() }

        if (len + frames > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + frames))
        for (f in 0 until frames) buf[len++] = mix(input, f)

        val out = ShortArray(((len - pos) / step).toInt() + 2)
        var count = 0
        while (pos + halfWidth <= len - 1) {
            val lo = maxOf(0, ceil(pos - halfWidth).toInt())
            val hi = floor(pos + halfWidth).toInt()
            var acc = 0.0
            var weights = 0.0
            for (i in lo..hi) {
                val w = kernel(i - pos)
                acc += buf[i] * w
                weights += w
            }
            out[count++] = (acc / weights).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }

        // Keep only the samples the next outputs still need.
        val drop = floor(pos - halfWidth).toInt() - 1
        if (drop > 0) {
            buf.copyInto(buf, 0, drop, len)
            len -= drop
            pos -= drop
        }
        return out.copyOf(count)
    }

    private fun mix(input: ShortArray, frame: Int): Float {
        var sum = 0
        val base = frame * inChannels
        for (c in 0 until inChannels) sum += input[base + c]
        return sum.toFloat() / inChannels
    }

    private fun kernel(d: Double): Double {
        val x = d / halfWidth
        if (abs(x) >= 1.0) return 0.0
        val window = 0.5 * (1 + cos(PI * x))
        val t = PI * cutoff * d
        val sinc = if (t == 0.0) 1.0 else sin(t) / t
        return sinc * window
    }
}
