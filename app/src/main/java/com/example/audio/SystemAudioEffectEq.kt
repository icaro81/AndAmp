package com.example.audio

import android.media.audiofx.Equalizer
import com.example.domain.model.EqConstants
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Hardware/Android system [Equalizer] AudioEffect companion:
 * - Used during YouTube Remote playback (session 0 global output mix) since WebView bypasses Media3 AudioProcessor.
 * - Used as fallback on ExoPlayer's audioSessionId only if DSP AudioProcessor is not handling the stream format.
 * Never double-stacks gain on top of an active GraphicEqAudioProcessor stream.
 */
class SystemAudioEffectEq {
    private var equalizer: Equalizer? = null
    private var boundSessionId: Int = -1
    private var eqEnabled: Boolean = true
    private var bandsDb: List<Float> = List(10) { 0f }
    private var preampDb: Float = 0f

    fun bindSession(audioSessionId: Int, shouldActivate: Boolean) {
        if (audioSessionId < 0) return
        if (!shouldActivate) {
            runCatching { equalizer?.enabled = false }
            return
        }
        if (boundSessionId != audioSessionId || equalizer == null) {
            release()
            boundSessionId = audioSessionId
            runCatching {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = eqEnabled
                }
            }
        }
        applyBandsToHardware()
    }

    fun updateSettings(
        enabled: Boolean,
        gainsDb: List<Float>,
        preamp: Float,
        shouldActivate: Boolean
    ) {
        eqEnabled = enabled
        bandsDb = gainsDb
        preampDb = preamp
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = enabled && shouldActivate
            if (enabled && shouldActivate) {
                applyBandsToHardware()
            }
        }
    }

    fun release() {
        runCatching {
            equalizer?.enabled = false
            equalizer?.release()
        }
        equalizer = null
        boundSessionId = -1
    }

    private fun applyBandsToHardware() {
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = eqEnabled
            if (!eqEnabled) return@runCatching
            val numHwBands = eq.numberOfBands.toInt().coerceAtLeast(0)
            val range = eq.bandLevelRange
            val minMb = range.getOrElse(0) { (-1200).toShort() }.toInt()
            val maxMb = range.getOrElse(1) { 1200.toShort() }.toInt()

            for (hwBand in 0 until numHwBands) {
                val centerHz = (eq.getCenterFreq(hwBand.toShort()) / 1000.0).coerceAtLeast(20.0)
                val interpolatedDb = interpolate10BandDbAt(centerHz) + preampDb * 0.65f
                val targetMb = (interpolatedDb * 100f).roundToInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(hwBand.toShort(), targetMb.toShort())
            }
        }
    }

    private fun interpolate10BandDbAt(freqHz: Double): Float {
        val isoFreqs = EqConstants.BAND_FREQUENCIES_HZ
        val logTarget = ln(freqHz)
        var bestWeightSum = 0.0
        var weightedDb = 0.0
        for (i in isoFreqs.indices) {
            val d = abs(logTarget - ln(isoFreqs[i].toDouble()))
            val weight = 1.0 / (1.0 + d * d * 4.5)
            bestWeightSum += weight
            weightedDb += weight * bandsDb.getOrElse(i) { 0f }
        }
        return if (bestWeightSum > 0.0) (weightedDb / bestWeightSum).toFloat() else 0f
    }
}
