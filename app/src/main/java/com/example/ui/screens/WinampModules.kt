package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.AudioOutputInfo
import com.example.audio.PlaybackUiState
import com.example.audio.RepeatModeState
import com.example.data.local.EqSettingsState
import com.example.domain.model.PlaybackSourceMode
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.model.YouTubePlaybackState
import com.example.ui.components.EqResponseCurveCanvas
import com.example.ui.components.LiveSpectrumCanvas
import com.example.ui.components.WinampLcdBox
import com.example.ui.components.WinampMetallicButton
import com.example.ui.components.WinampModulePanel
import com.example.ui.components.WinampModuleTitleBar
import com.example.ui.components.WinampSeekSlider
import com.example.ui.components.WinampVerticalFaderBank
import com.example.ui.components.YouTubeRemotePlayerBox
import com.example.ui.components.formatShortTime
import com.example.ui.components.formatWinampTime
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import com.example.ui.viewmodel.LibraryFormatFilter
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer

data class RackSizing(
    val isLandscape: Boolean,
    val isCompactWidth: Boolean,
    val horizontalPadding: Dp,
    val spectrumHeight: Dp,
    val eqFaderHeight: Dp,
    val playlistMinHeight: Dp,
    val playlistMaxHeight: Dp,
    val transportButtonHeight: Dp,
    val footerButtonHeight: Dp,
    val timerFontSize: TextUnit,
    val youtubePortraitMaxHeight: Dp = 230.dp,
    val youtubeLandscapeMaxHeight: Dp = 200.dp
)

@Composable
fun PlayerDeckModule(
    track: Track?,
    trackIndex: Int,
    playbackState: PlaybackUiState,
    playbackSourceMode: PlaybackSourceMode,
    youTubeState: YouTubePlaybackState,
    spectrumBars: FloatArray,
    outputInfo: AudioOutputInfo,
    eqEnabled: Boolean,
    sizing: RackSizing,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onEjectImport: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleEq: () -> Unit,
    onFocusPlaylist: () -> Unit,
    onExitYouTube: () -> Unit,
    onBindYouTubePlayer: (YouTubePlayer) -> Unit,
    onUnbindYouTubePlayer: () -> Unit,
    onYouTubeStateChanged: (Boolean) -> Unit,
    onYouTubeProgress: (Float, Float?) -> Unit,
    onYouTubeVideoChanged: (String) -> Unit,
    onYouTubeError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isYouTube = playbackSourceMode == PlaybackSourceMode.YOUTUBE_REMOTE &&
        youTubeState.activePlaylist != null
    val activeYtPlaylist = youTubeState.activePlaylist

    val activePosMs = if (isYouTube) {
        (youTubeState.currentSeconds * 1000f).toLong()
    } else {
        playbackState.positionMs
    }
    val activeDurMs = if (isYouTube) {
        (youTubeState.durationSeconds * 1000f).toLong().coerceAtLeast(1L)
    } else {
        playbackState.durationMs.takeIf { it > 0L } ?: (track?.durationMs ?: 0L)
    }
    val activePlaying = if (isYouTube) youTubeState.isPlaying else playbackState.isPlaying

    val displayNumber = if (trackIndex >= 0) trackIndex + 1 else 1
    val marqueeText = when {
        isYouTube && activeYtPlaylist != null ->
            "YOUTUBE REMOTE · ${activeYtPlaylist.title} — ${activeYtPlaylist.channelTitle}"
        track != null ->
            "$displayNumber. ${track.artist} - ${track.title} (${formatShortTime(activeDurMs)})   ***   ${track.album}"
        else ->
            "ANDAMP HI-FI DECK — IMPORTA LOCAL, NAS O YOUTUBE"
    }

    WinampModulePanel(modifier = modifier) {
        WinampModuleTitleBar(
            title = stringResource(R.string.rack_title_player),
            rightBadge = when {
                isYouTube -> "YT REMOTE"
                track?.uriString?.startsWith("smb://") == true -> "NAS SMB"
                outputInfo.isUsbDacConnected -> "USB DAC"
                else -> null
            }
        )

        if (isYouTube && activeYtPlaylist != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WinampLcdBox(modifier = Modifier.weight(1f)) {
                    Text(
                        text = marqueeText,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        color = WinampColors.LcdGreen,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .basicMarquee()
                            .testTag("player_marquee_lcd")
                    )
                }
                WinampMetallicButton(
                    onClick = onExitYouTube,
                    testTag = "btn_exit_youtube",
                    contentDesc = "Volver a audio local o NAS",
                    minHeight = 28.dp
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = WinampColors.EqGoldBright, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("HI-RES", fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold, fontSize = 8.5.sp, color = WinampColors.TitleGold)
                }
            }

            if (sizing.isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        modifier = Modifier.weight(0.54f),
                        contentAlignment = Alignment.Center
                    ) {
                        val targetH = (maxWidth * 9f / 16f).coerceIn(110.dp, sizing.youtubeLandscapeMaxHeight)
                        val targetW = (targetH * 16f / 9f).coerceAtMost(maxWidth)
                        YouTubeRemotePlayerBox(
                            playlistId = activeYtPlaylist.playlistId,
                            initialSeekSeconds = youTubeState.currentSeconds,
                            onBindPlayer = onBindYouTubePlayer,
                            onUnbindPlayer = onUnbindYouTubePlayer,
                            onPlayingChanged = onYouTubeStateChanged,
                            onProgress = onYouTubeProgress,
                            onVideoChanged = onYouTubeVideoChanged,
                            onError = onYouTubeError,
                            modifier = Modifier
                                .width(targetW)
                                .height(targetH)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(0.46f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WinampLcdBox(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (activePlaying) "▶ REMOTE" else "❚❚ PAUSA",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    color = WinampColors.LcdAmber
                                )
                                Text(
                                    text = formatWinampTime(activePosMs),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = sizing.timerFontSize,
                                    color = WinampColors.LcdGreen,
                                    modifier = Modifier.testTag("player_timer_lcd")
                                )
                            }
                        }
                        WinampSeekSlider(
                            positionMs = activePosMs,
                            durationMs = activeDurMs,
                            enabled = true,
                            onSeekTo = onSeekTo
                        )
                        DeckTransportBar(
                            isPlaying = activePlaying,
                            isPausedPosition = !activePlaying && activePosMs > 0L,
                            shuffleEnabled = false,
                            repeatMode = RepeatModeState.OFF,
                            isYouTube = true,
                            buttonHeight = sizing.transportButtonHeight,
                            onPrev = onPrev,
                            onPlay = onPlay,
                            onPause = onPause,
                            onStop = onStop,
                            onNext = onNext,
                            onEjectImport = onEjectImport,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat
                        )
                    }
                }
            } else {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val targetH = (maxWidth * 9f / 16f).coerceIn(135.dp, sizing.youtubePortraitMaxHeight)
                    val targetW = (targetH * 16f / 9f).coerceAtMost(maxWidth)
                    YouTubeRemotePlayerBox(
                        playlistId = activeYtPlaylist.playlistId,
                        initialSeekSeconds = youTubeState.currentSeconds,
                        onBindPlayer = onBindYouTubePlayer,
                        onUnbindPlayer = onUnbindYouTubePlayer,
                        onPlayingChanged = onYouTubeStateChanged,
                        onProgress = onYouTubeProgress,
                        onVideoChanged = onYouTubeVideoChanged,
                        onError = onYouTubeError,
                        modifier = Modifier
                            .width(targetW)
                            .height(targetH)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LcdMetricChip("YOUTUBE", "lcd_format_badge")
                    LcdMetricChip("STREAM", "lcd_bit_depth")
                    LcdMetricChip(formatWinampTime(activePosMs), "player_timer_lcd")
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (activePlaying) "▶ LIVE" else "❚❚",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = WinampColors.LcdGreen
                    )
                }
                Spacer(Modifier.height(4.dp))
                WinampSeekSlider(
                    positionMs = activePosMs,
                    durationMs = activeDurMs,
                    enabled = true,
                    onSeekTo = onSeekTo
                )
                Spacer(Modifier.height(4.dp))
                DeckTransportBar(
                    isPlaying = activePlaying,
                    isPausedPosition = !activePlaying && activePosMs > 0L,
                    shuffleEnabled = false,
                    repeatMode = RepeatModeState.OFF,
                    isYouTube = true,
                    buttonHeight = sizing.transportButtonHeight,
                    onPrev = onPrev,
                    onPlay = onPlay,
                    onPause = onPause,
                    onStop = onStop,
                    onNext = onNext,
                    onEjectImport = onEjectImport,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat
                )
            }
        } else {
            // Standard Local / Samba NAS Hi-Res Audio Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WinampLcdBox(
                    modifier = Modifier
                        .weight(0.41f)
                        .fillMaxHeight()
                        .padding(1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when {
                                    activePlaying -> "▶"
                                    activePosMs > 0L -> "❚❚"
                                    else -> "■"
                                },
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                color = if (activePlaying) WinampColors.LcdGreen else WinampColors.LcdAmber
                            )
                            Text(
                                text = formatWinampTime(activePosMs),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = sizing.timerFontSize,
                                letterSpacing = 1.sp,
                                color = WinampColors.LcdGreen,
                                modifier = Modifier.testTag("player_timer_lcd")
                            )
                        }

                        LiveSpectrumCanvas(
                            bars = spectrumBars,
                            isPlaying = activePlaying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(sizing.spectrumHeight)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(0.59f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    WinampLcdBox(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = marqueeText,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdGreen,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .basicMarquee()
                                .testTag("player_marquee_lcd")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LcdMetricChip(
                            text = track?.shortBadge ?: "--",
                            testTag = "lcd_format_badge"
                        )
                        LcdMetricChip(
                            text = track?.bitDepthDisplay ?: "--",
                            testTag = "lcd_bit_depth"
                        )
                        LcdMetricChip(
                            text = track?.sampleRateDisplay ?: "--",
                            testTag = "lcd_sample_rate"
                        )
                        Spacer(Modifier.weight(1f))
                        val isStereo = (track?.channels ?: 2) >= 2
                        Text(
                            text = if (isStereo) "stereo" else "mono",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = WinampColors.LcdGreen
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = buildString {
                                append(track?.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "Hi-Res PCM")
                                append(" · ")
                                append(outputInfo.routeName)
                            },
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            color = WinampColors.TitleSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MiniTogglePill(
                                label = "EQ",
                                active = eqEnabled,
                                testTag = "deck_eq_indicator",
                                onClick = onToggleEq
                            )
                            MiniTogglePill(
                                label = "PL",
                                active = true,
                                testTag = "deck_pl_indicator",
                                onClick = onFocusPlaylist
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            WinampSeekSlider(
                positionMs = activePosMs,
                durationMs = activeDurMs,
                enabled = track != null,
                onSeekTo = onSeekTo
            )

            Spacer(Modifier.height(4.dp))

            DeckTransportBar(
                isPlaying = activePlaying,
                isPausedPosition = !activePlaying && activePosMs > 0L,
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode,
                isYouTube = false,
                buttonHeight = sizing.transportButtonHeight,
                onPrev = onPrev,
                onPlay = onPlay,
                onPause = onPause,
                onStop = onStop,
                onNext = onNext,
                onEjectImport = onEjectImport,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat
            )
        }
    }
}

@Composable
private fun DeckTransportBar(
    isPlaying: Boolean,
    isPausedPosition: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatModeState,
    isYouTube: Boolean,
    buttonHeight: Dp,
    onPrev: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onEjectImport: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        WinampMetallicButton(
            onClick = onPrev,
            testTag = "btn_previous",
            contentDesc = stringResource(R.string.cd_previous_track),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = WinampColors.TitleSilver, modifier = Modifier.size(18.dp))
        }
        WinampMetallicButton(
            onClick = onPlay,
            testTag = "btn_play",
            contentDesc = stringResource(R.string.cd_play),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (isPlaying) WinampColors.LcdGreen else WinampColors.TitleSilver,
                modifier = Modifier.size(18.dp)
            )
        }
        WinampMetallicButton(
            onClick = onPause,
            testTag = "btn_pause",
            contentDesc = stringResource(R.string.cd_pause),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = null,
                tint = if (isPausedPosition) WinampColors.EqGoldBright else WinampColors.TitleSilver,
                modifier = Modifier.size(17.dp)
            )
        }
        WinampMetallicButton(
            onClick = onStop,
            testTag = "btn_stop",
            contentDesc = stringResource(R.string.cd_stop),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, tint = WinampColors.TitleSilver, modifier = Modifier.size(17.dp))
        }
        WinampMetallicButton(
            onClick = onNext,
            testTag = "btn_next",
            contentDesc = stringResource(R.string.cd_next_track),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = WinampColors.TitleSilver, modifier = Modifier.size(18.dp))
        }
        WinampMetallicButton(
            onClick = onEjectImport,
            testTag = "btn_eject_import",
            contentDesc = stringResource(R.string.cd_eject_import),
            minHeight = buttonHeight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Eject, contentDescription = null, tint = WinampColors.EqGoldBright, modifier = Modifier.size(16.dp))
        }

        if (!isYouTube) {
            Spacer(Modifier.width(2.dp))
            WinampMetallicButton(
                onClick = onToggleShuffle,
                testTag = "btn_shuffle",
                contentDesc = stringResource(R.string.cd_shuffle),
                showLed = true,
                ledActive = shuffleEnabled,
                minHeight = buttonHeight
            ) {
                Text(
                    text = "SHUF",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = if (shuffleEnabled) WinampColors.LcdGreen else WinampColors.TitleSilver
                )
            }
            WinampMetallicButton(
                onClick = onCycleRepeat,
                testTag = "btn_repeat",
                contentDesc = stringResource(R.string.cd_repeat),
                showLed = true,
                ledActive = repeatMode != RepeatModeState.OFF,
                minHeight = buttonHeight
            ) {
                Icon(
                    imageVector = if (repeatMode == RepeatModeState.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = null,
                    tint = if (repeatMode != RepeatModeState.OFF) WinampColors.LcdGreen else WinampColors.TitleSilver,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun LcdMetricChip(text: String, testTag: String) {
    WinampLcdBox(modifier = Modifier.testTag(testTag)) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = WinampColors.LcdGreen,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun MiniTogglePill(
    label: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    WinampMetallicButton(
        onClick = onClick,
        testTag = testTag,
        contentDesc = label,
        minHeight = 22.dp
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            color = if (active) WinampColors.LcdGreen else WinampColors.TextSecondary
        )
    }
}

@Composable
fun EqualizerModule(
    eqSettings: EqSettingsState,
    sizing: RackSizing,
    onToggleEnabled: () -> Unit,
    onToggleAuto: () -> Unit,
    onOpenPresets: () -> Unit,
    onPreampChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    WinampModulePanel(modifier = modifier) {
        WinampModuleTitleBar(title = stringResource(R.string.rack_title_eq))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            WinampMetallicButton(
                onClick = onToggleEnabled,
                testTag = "eq_toggle_on",
                contentDesc = "Activar o desactivar ecualizador",
                showLed = true,
                ledActive = eqSettings.enabled,
                minHeight = 34.dp
            ) {
                Text(
                    text = "ON",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = if (eqSettings.enabled) WinampColors.LcdGreen else WinampColors.TitleSilver
                )
            }

            WinampMetallicButton(
                onClick = onToggleAuto,
                testTag = "eq_toggle_auto",
                contentDesc = "Preamp automático anticlipping",
                showLed = true,
                ledActive = eqSettings.autoGain,
                minHeight = 34.dp
            ) {
                Text(
                    text = "AUTO",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = if (eqSettings.autoGain) WinampColors.LcdGreen else WinampColors.TitleSilver
                )
            }

            WinampLcdBox(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
            ) {
                EqResponseCurveCanvas(
                    bandsDb = eqSettings.bandsDb,
                    preampDb = eqSettings.preampDb,
                    eqEnabled = eqSettings.enabled,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            }

            WinampMetallicButton(
                onClick = onOpenPresets,
                testTag = "eq_presets_button",
                contentDesc = "Abrir presets de ecualización",
                minHeight = 34.dp
            ) {
                Text(
                    text = stringResource(R.string.btn_presets),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = WinampColors.TitleGold
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        WinampVerticalFaderBank(
            preampDb = eqSettings.preampDb,
            bandsDb = eqSettings.bandsDb,
            eqEnabled = eqSettings.enabled,
            onPreampChange = onPreampChange,
            onBandChange = onBandChange,
            faderHeight = sizing.eqFaderHeight
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistTerminalModule(
    tracks: List<Track>,
    currentTrackId: Long?,
    selectedTrackId: Long?,
    activePlaylist: Playlist?,
    selectedFilter: LibraryFormatFilter,
    positionMs: Long,
    fillListHeight: Boolean,
    sizing: RackSizing,
    onPlayTrack: (Track) -> Unit,
    onSelectTrack: (Long?) -> Unit,
    onAdd: () -> Unit,
    onRem: () -> Unit,
    onSel: () -> Unit,
    onMisc: () -> Unit,
    onListOpts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalPlaylistMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    val headerBadge = buildString {
        append(activePlaylist?.name ?: "BIBLIOTECA")
        if (selectedFilter != LibraryFormatFilter.ALL) {
            append(" · ${selectedFilter.label}")
        }
    }

    WinampModulePanel(modifier = modifier) {
        WinampModuleTitleBar(
            title = stringResource(R.string.rack_title_playlist),
            rightBadge = headerBadge
        )

        val listModifier = if (fillListHeight) {
            Modifier
                .fillMaxWidth()
                .weight(1f)
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(min = sizing.playlistMinHeight, max = sizing.playlistMaxHeight)
        }

        WinampLcdBox(modifier = listModifier) {
            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sin pistas. Pulsa ADD para importar audio local, navegar un servidor NAS/Samba o buscar listas de YouTube.",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        color = WinampColors.LcdGreenDim,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .testTag("playlist_terminal_list")
                ) {
                    itemsIndexed(tracks, key = { _, item -> item.id }) { idx, item ->
                        val isPlayingItem = item.id == currentTrackId
                        val isSelectedItem = item.id == selectedTrackId ||
                            (selectedTrackId == null && isPlayingItem)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelectedItem) WinampColors.PlaylistSelectBg else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = { onPlayTrack(item) },
                                    onLongClick = { onSelectTrack(item.id) }
                                )
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("playlist_row_${item.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${idx + 1}. ${item.artist} - ${item.title}",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = if (sizing.isCompactWidth) 10.5.sp else 11.5.sp,
                                fontWeight = if (isPlayingItem) FontWeight.Bold else FontWeight.Normal,
                                color = if (isPlayingItem) WinampColors.TextPrimary else WinampColors.LcdGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (item.uriString.startsWith("smb://")) {
                                Text(
                                    text = "NAS",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 8.sp,
                                    color = WinampColors.TitleSilver
                                )
                            }

                            Text(
                                text = item.shortBadge,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                color = if (item.isHiRes) WinampColors.EqGoldBright else WinampColors.LcdGreenDim,
                                maxLines = 1
                            )

                            Text(
                                text = formatShortTime(item.durationMs),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                color = if (isPlayingItem) WinampColors.TextPrimary else WinampColors.LcdGreen,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            WinampMetallicButton(
                onClick = onAdd,
                testTag = "btn_playlist_add",
                contentDesc = "Añadir pistas, servidor NAS o YouTube",
                minHeight = sizing.footerButtonHeight
            ) {
                Text(
                    text = stringResource(R.string.btn_add),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = WinampColors.TitleSilver
                )
            }

            WinampMetallicButton(
                onClick = onRem,
                testTag = "btn_playlist_rem",
                contentDesc = "Quitar pista seleccionada",
                minHeight = sizing.footerButtonHeight
            ) {
                Text(
                    text = stringResource(R.string.btn_rem),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = WinampColors.TitleSilver
                )
            }

            WinampMetallicButton(
                onClick = onSel,
                testTag = "btn_playlist_sel",
                contentDesc = "Buscar y filtrar formato",
                showLed = selectedFilter != LibraryFormatFilter.ALL,
                ledActive = selectedFilter != LibraryFormatFilter.ALL,
                minHeight = sizing.footerButtonHeight
            ) {
                Text(
                    text = stringResource(R.string.btn_sel),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = WinampColors.TitleSilver
                )
            }

            WinampMetallicButton(
                onClick = onMisc,
                testTag = "btn_playlist_misc",
                contentDesc = "Gestionar listas de reproducción y salida",
                minHeight = sizing.footerButtonHeight
            ) {
                Text(
                    text = stringResource(R.string.btn_misc),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = WinampColors.TitleSilver
                )
            }

            WinampLcdBox(
                modifier = Modifier
                    .weight(1f)
                    .height(sizing.footerButtonHeight.coerceAtMost(38.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "${formatShortTime(positionMs)}/${formatShortTime(totalPlaylistMs)}",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = WinampColors.LcdGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${tracks.size} pistas",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 8.sp,
                        color = WinampColors.LcdGreenDim,
                        maxLines = 1
                    )
                }
            }

            WinampMetallicButton(
                onClick = onListOpts,
                testTag = "btn_playlist_opts",
                contentDesc = "Opciones de reproducción de lista",
                minHeight = sizing.footerButtonHeight
            ) {
                Text(
                    text = "LIST\nOPTS",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    color = WinampColors.TitleGold
                )
            }
        }
    }
}
