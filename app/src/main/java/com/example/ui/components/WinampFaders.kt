package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.EqConstants
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import kotlin.math.roundToInt

@Composable
fun WinampSeekSlider(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val playbackFrac = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(playbackFrac) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }

    val displayFrac = if (isDragging) dragFrac else playbackFrac

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .testTag("player_seek_bar")
            .semantics { contentDescription = "Barra de progreso de reproducción" }
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val frac = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                    onSeekTo((frac * safeDuration).toLong())
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        isDragging = true
                        dragFrac = (startOffset.x / trackWidthPx).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFrac = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeekTo((dragFrac * safeDuration).toLong())
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val grooveH = 7.dp.toPx()
            val grooveY = (h - grooveH) / 2f

            // Recessed track slot
            drawRect(
                color = WinampColors.EqSlotDark,
                topLeft = Offset(0f, grooveY),
                size = Size(w, grooveH)
            )
            // Warm played progress bar inside groove
            val thumbW = 24.dp.toPx()
            val thumbH = 14.dp.toPx()
            val usableW = (w - thumbW).coerceAtLeast(1f)
            val thumbX = displayFrac * usableW
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(WinampColors.EqGoldMid, WinampColors.EqGoldBright)
                ),
                topLeft = Offset(1.dp.toPx(), grooveY + 1.dp.toPx()),
                size = Size((thumbX + thumbW * 0.4f).coerceAtLeast(0f), (grooveH - 2.dp.toPx()).coerceAtLeast(1f))
            )

            // Raised metallic/gold Winamp slider cap
            val thumbY = (h - thumbH) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFDE69B),
                        WinampColors.EqGoldBright,
                        WinampColors.EqGoldMid
                    ),
                    startY = thumbY,
                    endY = thumbY + thumbH
                ),
                topLeft = Offset(thumbX, thumbY),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
            // Center notch
            drawLine(
                color = WinampColors.BevelShadow,
                start = Offset(thumbX + thumbW / 2f, thumbY + 2.dp.toPx()),
                end = Offset(thumbX + thumbW / 2f, thumbY + thumbH - 2.dp.toPx()),
                strokeWidth = 1.5.dp.toPx()
            )
        }
    }
}

@Composable
fun WinampVerticalFaderBank(
    preampDb: Float,
    bandsDb: List<Float>,
    eqEnabled: Boolean,
    onPreampChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
    faderHeight: androidx.compose.ui.unit.Dp = 126.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(faderHeight),
        verticalAlignment = Alignment.Bottom
    ) {
        // Preamp slider
        WinampVerticalSlider(
            label = "PRE",
            valueDb = preampDb,
            minDb = EqConstants.MIN_PREAMP_DB,
            maxDb = EqConstants.MAX_PREAMP_DB,
            eqEnabled = eqEnabled,
            testTag = "eq_preamp_fader",
            contentDesc = "Preamp ${preampDb.roundToInt()} dB",
            onValueChange = onPreampChange,
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight()
        )

        // +12 / 0 / -12 dB Scale column
        Column(
            modifier = Modifier
                .width(34.dp)
                .fillMaxHeight()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "+12 db",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.5.sp,
                color = WinampColors.EqScaleText,
                textAlign = TextAlign.Center
            )
            Text(
                text = "0 db",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.5.sp,
                color = WinampColors.TitleGold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "-12 db",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.5.sp,
                color = WinampColors.EqScaleText,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.width(2.dp))

        // 10 ISO Graphic EQ Sliders
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (index in 0 until 10) {
                val gain = bandsDb.getOrElse(index) { 0f }
                val freqLabel = EqConstants.BAND_LABELS[index]
                WinampVerticalSlider(
                    label = freqLabel,
                    valueDb = gain,
                    minDb = EqConstants.MIN_GAIN_DB,
                    maxDb = EqConstants.MAX_GAIN_DB,
                    eqEnabled = eqEnabled,
                    testTag = "eq_band_slider_$index",
                    contentDesc = "Banda $freqLabel Hz a ${gain.roundToInt()} decibelios",
                    onValueChange = { next -> onBandChange(index, next) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun WinampVerticalSlider(
    label: String,
    valueDb: Float,
    minDb: Float,
    maxDb: Float,
    eqEnabled: Boolean,
    testTag: String,
    contentDesc: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val normalized = ((valueDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    val zeroNorm = ((0f - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(testTag)
                .semantics { contentDescription = contentDesc }
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(minDb, maxDb) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - lastTapTimeMs) < 300L
                        lastTapTimeMs = now

                        fun applyY(y: Float) {
                            val h = heightPx.coerceAtLeast(1f)
                            val frac = (1f - y / h).coerceIn(0f, 1f)
                            val next = minDb + frac * (maxDb - minDb)
                            currentOnValueChange((next * 2f).roundToInt() / 2f)
                        }

                        if (isDoubleTap) {
                            currentOnValueChange(0f)
                        } else {
                            applyY(down.position.y)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            applyY(change.position.y)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val slotW = 7.dp.toPx().coerceAtMost(w * 0.48f)
                val slotX = (w - slotW) / 2f
                val padY = 6.dp.toPx()
                val trackH = (h - padY * 2).coerceAtLeast(10f)

                // Dark recessed channel
                drawRect(
                    color = WinampColors.EqSlotDark,
                    topLeft = Offset(slotX, padY),
                    size = Size(slotW, trackH)
                )

                // Center zero tick
                val zeroY = padY + (1f - zeroNorm) * trackH
                drawLine(
                    color = WinampColors.EqScaleText.copy(alpha = 0.55f),
                    start = Offset(slotX - 3.dp.toPx(), zeroY),
                    end = Offset(slotX + slotW + 3.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx()
                )

                // Colored vertical energy bar inside slot
                val thumbH = 10.dp.toPx()
                val thumbW = (w - 3.dp.toPx()).coerceIn(13.dp.toPx(), 18.dp.toPx())
                val thumbY = padY + (1f - normalized) * (trackH - thumbH)
                val energyColor = when {
                    !eqEnabled -> WinampColors.EqScaleText.copy(alpha = 0.4f)
                    valueDb > 4f -> WinampColors.LcdPeakRed
                    valueDb >= 0f -> WinampColors.EqGoldBright
                    else -> WinampColors.LcdGreen
                }
                val barBottom = padY + trackH
                val barTop = (thumbY + thumbH / 2f).coerceIn(padY, barBottom)
                drawRect(
                    color = energyColor.copy(alpha = 0.85f),
                    topLeft = Offset(slotX + 1.5.dp.toPx(), barTop),
                    size = Size((slotW - 3.dp.toPx()).coerceAtLeast(2f), (barBottom - barTop).coerceAtLeast(0f))
                )

                // Metallic / Gold Winamp fader thumb cap
                val thumbX = (w - thumbW) / 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFDF0B5),
                            WinampColors.EqGoldBright,
                            WinampColors.EqGoldMid
                        ),
                        startY = thumbY,
                        endY = thumbY + thumbH
                    ),
                    topLeft = Offset(thumbX, thumbY),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
                drawLine(
                    color = WinampColors.BevelShadow,
                    start = Offset(thumbX + 2.dp.toPx(), thumbY + thumbH / 2f),
                    end = Offset(thumbX + thumbW - 2.dp.toPx(), thumbY + thumbH / 2f),
                    strokeWidth = 1.2.dp.toPx()
                )
            }
        }

        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 8.sp,
            color = WinampColors.TitleSilver,
            maxLines = 1
        )
    }
}
