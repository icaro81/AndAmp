package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.audio.GraphicEqAudioProcessor
import com.example.domain.model.EqConstants
import com.example.ui.theme.WinampColors
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun LiveSpectrumCanvas(
    bars: FloatArray,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = bars.size.coerceAtLeast(16)
    val peaks = remember(barCount) {
        mutableStateListOf<Float>().apply { repeat(barCount) { add(0f) } }
    }

    LaunchedEffect(bars, isPlaying) {
        for (i in 0 until barCount) {
            val current = if (isPlaying && i < bars.size) bars[i].coerceIn(0f, 1f) else 0f
            val prevPeak = peaks.getOrElse(i) { 0f }
            peaks[i] = if (current >= prevPeak) current else (prevPeak - 0.045f).coerceAtLeast(0f)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(WinampColors.LcdBlack)
    ) {
        val w = size.width
        val h = size.height
        val colGap = 1.5.dp.toPx()
        val colW = ((w - colGap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val segmentCount = 12
        val segGap = 1.dp.toPx()
        val segH = ((h - segGap * (segmentCount - 1)) / segmentCount).coerceAtLeast(1.5f)
        val dimTrack = WinampColors.LcdGreenDim.copy(alpha = 0.14f)

        for (i in 0 until barCount) {
            val x = i * (colW + colGap)
            val level = if (isPlaying && i < bars.size) bars[i].coerceIn(0f, 1f) else 0f
            val litSegments = (level * segmentCount).roundToInt().coerceIn(0, segmentCount)
            val peakSeg = ((peaks.getOrElse(i) { 0f }) * segmentCount).roundToInt()
                .coerceIn(0, segmentCount - 1)

            for (s in 0 until segmentCount) {
                val y = h - (s + 1) * segH - s * segGap
                val segColor = when {
                    s >= segmentCount - 2 -> WinampColors.LcdPeakRed
                    s >= segmentCount - 5 -> WinampColors.LcdAmber
                    else -> WinampColors.LcdGreen
                }
                val isLit = s < litSegments || (isPlaying && peakSeg > 0 && s == peakSeg)
                drawRect(
                    color = if (isLit) segColor else dimTrack,
                    topLeft = Offset(x, y),
                    size = Size(colW, segH)
                )
            }
        }
    }
}

@Composable
fun EqResponseCurveCanvas(
    bandsDb: List<Float>,
    preampDb: Float,
    eqEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val curveColor = if (eqEnabled) Color(0xFFC8FF38) else WinampColors.LcdGreenDim.copy(alpha = 0.45f)
    val glowColor = curveColor.copy(alpha = 0.28f)

    Canvas(
        modifier = modifier.background(WinampColors.LcdBlack)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Zero-dB center hairline matching Winamp's EQ mini graph
        drawLine(
            color = WinampColors.EqScaleText.copy(alpha = 0.45f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx()
        )

        // Subtle vertical ticks at ISO bands
        for (i in 0 until 10) {
            val freq = EqConstants.BAND_FREQUENCIES_HZ[i].toDouble()
            val normX = (kotlin.math.ln(freq / 20.0) / kotlin.math.ln(20000.0 / 20.0)).toFloat()
            val x = normX * w
            drawLine(
                color = WinampColors.EqScaleText.copy(alpha = 0.18f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 0.75.dp.toPx()
            )
        }

        val steps = 72
        val strokePath = Path()
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val freq = 20.0 * (20000.0 / 20.0).pow(t.toDouble())
            val db = if (eqEnabled) {
                GraphicEqAudioProcessor.evaluateResponseDb(freq, bandsDb, preampDb)
            } else {
                0f
            }
            val clampedDb = db.coerceIn(-14f, 14f)
            val x = t * w
            val y = midY - (clampedDb / 14f) * (h * 0.42f)
            if (s == 0) {
                strokePath.moveTo(x, y)
            } else {
                strokePath.lineTo(x, y)
            }
        }

        drawPath(
            path = strokePath,
            color = glowColor,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = strokePath,
            color = curveColor,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
