package ch.beachscore.app.voice

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PcmConverterTest {

    @Test
    fun `16 kHz mono is what the recogniser wants, so it is tried first`() {
        assertEquals(CaptureFormat(16_000, 1), captureFormats(intArrayOf(), intArrayOf()).first())
        assertEquals(CaptureFormat(16_000, 1), captureFormats(intArrayOf(48_000), intArrayOf(2)).first())
    }

    @Test
    fun `then the device's own rates, multiples of 16 kHz first, mono before stereo`() {
        val formats = captureFormats(intArrayOf(44_100, 48_000, 8_000), intArrayOf(1, 2))
        assertEquals(
            listOf(
                CaptureFormat(16_000, 1),
                CaptureFormat(48_000, 1), CaptureFormat(48_000, 2),
                CaptureFormat(44_100, 1), CaptureFormat(44_100, 2),
                CaptureFormat(8_000, 1), CaptureFormat(8_000, 2),
            ),
            formats,
        )
    }

    @Test
    fun `a stereo-only device is not offered mono`() {
        val formats = captureFormats(intArrayOf(48_000), intArrayOf(2))
        assertEquals(listOf(CaptureFormat(16_000, 1), CaptureFormat(48_000, 2)), formats)
    }

    @Test
    fun `devices that report nothing get the common rates`() {
        val formats = captureFormats(intArrayOf(), intArrayOf())
        assertEquals(CaptureFormat(16_000, 2), formats[1])
        assertTrue(CaptureFormat(48_000, 1) in formats)
        assertTrue(CaptureFormat(44_100, 2) in formats)
    }

    @Test
    fun `16 kHz mono passes through unchanged`() {
        val c = PcmConverter(16_000, 1)
        val input = ShortArray(1600) { (it * 37 % 2000 - 1000).toShort() }
        assertTrue(c.isPassthrough)
        assertArrayEquals(input.copyOf(800), c.convert(input, 800))
    }

    @Test
    fun `stereo is mixed down to mono`() {
        val c = PcmConverter(16_000, 2)
        val input = ShortArray(2 * 1600) { if (it % 2 == 0) 1000 else 3000 }
        val out = c.convert(input, input.size)
        // Away from the start-up edge every sample is the average.
        out.drop(100).forEach { assertEquals(2000.0, it.toDouble(), 2.0) }
    }

    @Test
    fun `48 kHz stereo becomes 16 kHz mono at the right length`() {
        val c = PcmConverter(48_000, 2)
        var total = 0
        repeat(10) { total += c.convert(tone(1000.0, 48_000, 4800, channels = 2), 2 * 4800).size }
        // One second in, one second out, minus the filter's short delay.
        assertTrue(total in 15_950..16_000, "got $total")
    }

    @Test
    fun `a 1 kHz tone keeps its pitch and level`() {
        val c = PcmConverter(48_000, 1)
        val out = c.convert(tone(1000.0, 48_000, 48_000), 48_000)
        val steady = out.copyOfRange(1000, 15_000)
        assertEquals(rms(tone(1000.0, 16_000, 14_000)), rms(steady), 0.02 * 10_000)
        // Pitch: about 2 zero crossings per period, 1000 periods per second.
        val crossings = (1 until steady.size).count { (steady[it - 1] < 0) != (steady[it] < 0) }
        assertEquals(2 * 1000 * steady.size / 16_000.0, crossings.toDouble(), 4.0)
    }

    @Test
    fun `a tone above 8 kHz is filtered out instead of folding back as a false sound`() {
        val c = PcmConverter(48_000, 1)
        val out = c.convert(tone(12_000.0, 48_000, 48_000), 48_000)
        assertTrue(rms(out.copyOfRange(1000, 15_000)) < 0.05 * 10_000 / sqrt(2.0), "rms ${rms(out)}")
    }

    @Test
    fun `44,1 kHz converted in pieces equals converted at once`() {
        val input = tone(440.0, 44_100, 44_100)
        val whole = PcmConverter(44_100, 1).convert(input, input.size)
        val pieces = PcmConverter(44_100, 1).let { c ->
            input.toList().chunked(1234).flatMap { c.convert(it.toShortArray(), it.size).toList() }
        }
        assertEquals(whole.size, pieces.size)
        whole.indices.forEach { assertEquals(whole[it].toDouble(), pieces[it].toDouble(), 1.0) }
    }

    @Test
    fun `8 kHz is upsampled to 16 kHz`() {
        val c = PcmConverter(8_000, 1)
        val out = c.convert(tone(500.0, 8_000, 8_000), 8_000)
        assertTrue(out.size in 15_950..16_000, "got ${out.size}")
        assertEquals(rms(tone(500.0, 16_000, 14_000)), rms(out.copyOfRange(1000, 15_000)), 0.03 * 10_000)
    }

    @Test
    fun `full-scale input does not wrap around`() {
        val c = PcmConverter(48_000, 1)
        val out = c.convert(ShortArray(4800) { if (it / 24 % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }, 4800)
        // A 1 kHz square wave: about 200 sign changes in 0.1 s. Overshoot that wrapped around
        // to the other sign would add many more.
        val crossings = (1 until out.size).count { (out[it - 1] < 0) != (out[it] < 0) }
        assertTrue(crossings <= 202, "crossings $crossings")
        assertTrue(out.maxOf { abs(it.toInt()) } > 30_000, "still loud")
    }

    private fun tone(freq: Double, rate: Int, frames: Int, channels: Int = 1): ShortArray =
        ShortArray(frames * channels) { i -> (10_000 * sin(2 * PI * freq * (i / channels) / rate)).roundToInt().toShort() }

    private fun rms(s: ShortArray): Double = sqrt(s.sumOf { it.toDouble() * it } / s.size)
}
