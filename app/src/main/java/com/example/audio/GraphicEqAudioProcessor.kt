package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.EqConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time 10-band Graphic Equalizer + Preamp + Soft Limiter + 24-band Spectrum Analyzer
 * implemented as a Media3 BaseAudioProcessor.
 */
@UnstableApi
class GraphicEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var preampLinear: Float = 1f

    @Volatile
    private var bandGainsDb: FloatArray = FloatArray(10) { 0f }

    private var sampleRate: Int = 48_000
    private var channelCount: Int = 2

    // 10 bands x max 8 channels biquad state
    private val b0 = DoubleArray(10)
    private val b1 = DoubleArray(10)
    private val b2 = DoubleArray(10)
    private val a1 = DoubleArray(10)
    private val a2 = DoubleArray(10)

    private var z1 = Array(10) { DoubleArray(8) }
    private var z2 = Array(10) { DoubleArray(8) }

    // Spectrum analyzer (24 logarithmic bins from 31Hz to 16kHz)
    private val _spectrumFlow = MutableStateFlow(FloatArray(SPECTRUM_BINS) { 0f })
    val spectrumFlow: StateFlow<FloatArray> = _spectrumFlow.asStateFlow()

    private val ringBuffer = FloatArray(FFT_WINDOW)
    private var ringIndex = 0
    private var framesSinceSpectrumUpdate = 0
    private val smoothedBars = FloatArray(SPECTRUM_BINS)

    // Precomputed Goertzel / DFT frequencies for 24 bars
    private var binCoeffs = DoubleArray(SPECTRUM_BINS)

    init {
        recalculateCoefficients()
        recalculateSpectrumCoeffs()
    }

    fun updateSettings(enabled: Boolean, gainsDb: List<Float>, preampDb: Float) {
        this.enabled = enabled
        val clampedPreamp = preampDb.coerceIn(EqConstants.MIN_PREAMP_DB, EqConstants.MAX_PREAMP_DB)
        this.preampLinear = 10.0.pow(clampedPreamp / 20.0).toFloat()
        val updated = FloatArray(10) { idx ->
            gainsDb.getOrElse(idx) { 0f }.coerceIn(EqConstants.MIN_GAIN_DB, EqConstants.MAX_GAIN_DB)
        }
        this.bandGainsDb = updated
        recalculateCoefficients()
    }

    fun clearSpectrum() {
        smoothedBars.fill(0f)
        _spectrumFlow.value = FloatArray(SPECTRUM_BINS) { 0f }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channelCount = inputAudioFormat.channelCount.coerceIn(1, 8)
        z1 = Array(10) { DoubleArray(channelCount) }
        z2 = Array(10) { DoubleArray(channelCount) }
        recalculateCoefficients()
        recalculateSpectrumCoeffs()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val encoding = inputAudioFormat.encoding
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val sampleCount = remaining / bytesPerSample
        val frameCount = sampleCount / channelCount

        val outBuffer = replaceOutputBuffer(remaining)
        val isEqActive = enabled
        val pre = if (isEqActive) preampLinear.toDouble() else 1.0
        val gains = bandGainsDb
        val activeBands = BooleanArray(10) { isEqActive && kotlin.math.abs(gains[it]) > 0.05f }

        for (frame in 0 until frameCount) {
            var monoSum = 0f
            for (ch in 0 until channelCount) {
                var x = if (encoding == C.ENCODING_PCM_FLOAT) {
                    inputBuffer.getFloat().toDouble()
                } else {
                    inputBuffer.getShort().toDouble() / 32768.0
                }

                if (isEqActive) {
                    x *= pre
                    for (band in 0 until 10) {
                        if (!activeBands[band]) continue
                        val y = b0[band] * x + z1[band][ch]
                        z1[band][ch] = b1[band] * x - a1[band] * y + z2[band][ch]
                        z2[band][ch] = b2[band] * x - a2[band] * y
                        x = y
                    }
                    // Soft-knee analog limiter to prevent digital clipping on high gain boosts
                    if (x > 0.92 || x < -0.92) {
                        x = tanh(x * 0.95)
                    }
                }

                monoSum += x.toFloat()

                if (encoding == C.ENCODING_PCM_FLOAT) {
                    outBuffer.putFloat(x.toFloat().coerceIn(-1f, 1f))
                } else {
                    val pcm16 = (x * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    outBuffer.putShort(pcm16)
                }
            }

            val mono = monoSum / channelCount
            ringBuffer[ringIndex] = mono
            ringIndex = (ringIndex + 1) % FFT_WINDOW
            framesSinceSpectrumUpdate++
            if (framesSinceSpectrumUpdate >= SPECTRUM_HOP) {
                framesSinceSpectrumUpdate = 0
                updateSpectrum()
            }
        }

        outBuffer.flip()
    }

    override fun onFlush() {
        for (b in 0 until 10) {
            z1[b].fill(0.0)
            z2[b].fill(0.0)
        }
        ringBuffer.fill(0f)
        ringIndex = 0
        framesSinceSpectrumUpdate = 0
    }

    override fun onReset() {
        onFlush()
        clearSpectrum()
    }

    private fun recalculateCoefficients() {
        val fs = sampleRate.toDouble().coerceAtLeast(8000.0)
        val q = 1.4142135623730951
        val gains = bandGainsDb
        for (i in 0 until 10) {
            val fc = min(EqConstants.BAND_FREQUENCIES_HZ[i].toDouble(), fs * 0.45)
            val gain = gains[i].toDouble()
            val a = 10.0.pow(gain / 40.0)
            val w0 = 2.0 * PI * fc / fs
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val a0 = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0
            b1[i] = (-2.0 * cosW0) / a0
            b2[i] = (1.0 - alpha * a) / a0
            a1[i] = (-2.0 * cosW0) / a0
            a2[i] = (1.0 - alpha / a) / a0
        }
    }

    private fun recalculateSpectrumCoeffs() {
        val fs = sampleRate.toDouble().coerceAtLeast(8000.0)
        for (b in 0 until SPECTRUM_BINS) {
            val t = b.toDouble() / (SPECTRUM_BINS - 1)
            val freq = 35.0 * (16000.0 / 35.0).pow(t)
            val clampedFreq = min(freq, fs * 0.46)
            val omega = 2.0 * PI * clampedFreq / fs
            binCoeffs[b] = 2.0 * cos(omega)
        }
    }

    private fun updateSpectrum() {
        // Compute 24-band Goertzel energy over the current 256-sample Hann-windowed slice
        val n = FFT_WINDOW
        val start = ringIndex
        val newBars = FloatArray(SPECTRUM_BINS)
        for (b in 0 until SPECTRUM_BINS) {
            val coeff = binCoeffs[b]
            var s1 = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val idx = (start + i) % n
                val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
                val sample = ringBuffer[idx] * hann
                val s0 = sample + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val mag = sqrt(power.coerceAtLeast(0.0)) / (n * 0.25)
            val db = 20.0 * log10(mag + 1e-5)
            // Normalize [-58 dB .. 0 dB] -> [0f .. 1f]
            val norm = ((db + 58.0) / 58.0).toFloat().coerceIn(0f, 1f)
            val prev = smoothedBars[b]
            val smooth = if (norm > prev) prev * 0.35f + norm * 0.65f else prev * 0.78f + norm * 0.22f
            smoothedBars[b] = smooth
            newBars[b] = smooth
        }
        _spectrumFlow.value = newBars
    }

    companion object {
        const val SPECTRUM_BINS = 24
        private const val FFT_WINDOW = 256
        private const val SPECTRUM_HOP = 1024

        /**
         * Analytical magnitude response in dB at [freqHz] for UI frequency curve visualization.
         */
        fun evaluateResponseDb(
            freqHz: Double,
            bandsDb: List<Float>,
            preampDb: Float,
            sampleRate: Double = 48_000.0
        ): Float {
            val q = 1.4142135623730951
            val w = 2.0 * PI * min(freqHz, sampleRate * 0.45) / sampleRate
            val cosW = cos(w)
            val sinW = sin(w)
            val cos2W = cos(2.0 * w)
            val sin2W = sin(2.0 * w)
            var totalDb = preampDb.toDouble()

            for (i in 0 until 10) {
                val gain = bandsDb.getOrElse(i) { 0f }.toDouble()
                if (kotlin.math.abs(gain) < 0.01) continue
                val fc = min(EqConstants.BAND_FREQUENCIES_HZ[i].toDouble(), sampleRate * 0.45)
                val a = 10.0.pow(gain / 40.0)
                val w0 = 2.0 * PI * fc / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha / a
                val b0 = (1.0 + alpha * a) / a0
                val b1 = (-2.0 * cos(w0)) / a0
                val b2 = (1.0 - alpha * a) / a0
                val a1 = (-2.0 * cos(w0)) / a0
                val a2 = (1.0 - alpha / a) / a0

                val numRe = b0 + b1 * cosW + b2 * cos2W
                val numIm = -b1 * sinW - b2 * sin2W
                val denRe = 1.0 + a1 * cosW + a2 * cos2W
                val denIm = -a1 * sinW - a2 * sin2W
                val magSq = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm).coerceAtLeast(1e-12)
                totalDb += 10.0 * log10(magSq)
            }
            return totalDb.toFloat()
        }
    }
}
