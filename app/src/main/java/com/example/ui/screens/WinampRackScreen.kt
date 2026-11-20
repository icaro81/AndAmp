package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.AudioOutputInfo
import com.example.audio.PlaybackUiState
import com.example.data.local.AppUpdatePrefs
import com.example.data.local.EqSettingsState
import com.example.domain.model.DiscoveredSmbHost
import com.example.domain.model.EqPreset
import com.example.domain.model.PlaybackSourceMode
import com.example.domain.model.Playlist
import com.example.domain.model.SmbBrowserEntry
import com.example.domain.model.SmbServerProfile
import com.example.domain.model.Track
import com.example.domain.model.YouTubePlaybackState
import com.example.domain.model.YouTubePlaylistSummary
import com.example.ui.components.WinampMetallicButton
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import com.example.ui.viewmodel.LanScanUiState
import com.example.ui.viewmodel.LibraryFormatFilter
import com.example.ui.viewmodel.SmbBrowserUiState
import com.example.ui.viewmodel.YouTubeSearchUiState
import com.example.update.AppUpdateUiState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import kotlinx.coroutines.launch

@Composable
fun WinampRackScreen(
    playbackState: PlaybackUiState,
    playbackSourceMode: PlaybackSourceMode,
    youTubeState: YouTubePlaybackState,
    spectrumBars: FloatArray,
    outputInfo: AudioOutputInfo,
    eqSettings: EqSettingsState,
    customEqPresets: List<EqPreset>,
    allTracks: List<Track>,
    displayedTracks: List<Track>,
    playlists: List<Playlist>,
    activePlaylist: Playlist?,
    selectedTrackId: Long?,
    searchQuery: String,
    selectedFilter: LibraryFormatFilter,
    isImporting: Boolean,
    smbServers: List<SmbServerProfile>,
    smbBrowserState: SmbBrowserUiState,
    lanScanState: LanScanUiState = LanScanUiState(),
    youTubeSearchState: YouTubeSearchUiState,
    recentYouTubePlaylists: List<YouTubePlaylistSummary>,
    appUpdatePrefs: AppUpdatePrefs = AppUpdatePrefs(),
    appUpdateState: AppUpdateUiState = AppUpdateUiState.Idle,
    onPlayExplicit: () -> Unit,
    onPauseExplicit: () -> Unit,
    onStopPlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onEqEnabledChange: (Boolean) -> Unit,
    onEqAutoToggle: () -> Unit,
    onPreampChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onApplyPreset: (EqPreset) -> Unit,
    onResetEqFlat: () -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (Long) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onSelectTrack: (Long?) -> Unit,
    onRemoveSelectedOrActive: () -> Unit,
    onSelectPlaylist: (Long?) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onRenamePlaylist: (Long, String, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onAddTrackToPlaylist: (Long, Long) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleBitPerfectUsb: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (LibraryFormatFilter) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onMoveSelectedInPlaylist: (Int) -> Unit,
    onLaunchFilePicker: () -> Unit,
    onLaunchFolderPicker: () -> Unit,
    onScanLanSmb: () -> Unit = {},
    onConnectDiscoveredSmb: (DiscoveredSmbHost, String, Boolean, String, String) -> Unit = { _, _, _, _, _ -> },
    onSaveAndConnectSmb: ((SmbServerProfile) -> Unit)? = null,
    onSaveSmbServer: (SmbServerProfile) -> Unit,
    onDeleteSmbServer: (Long) -> Unit,
    onOpenSmbServer: (SmbServerProfile) -> Unit,
    onNavigateSmbEntry: (SmbBrowserEntry) -> Unit,
    onNavigateSmbUp: () -> Unit,
    onCloseSmbBrowser: () -> Unit,
    onImportAllSmbInFolder: () -> Unit,
    onToggleSmbFolderSelection: (SmbBrowserEntry) -> Unit = {},
    onSelectAllSmbFolders: () -> Unit = {},
    onClearSmbFolderSelection: () -> Unit = {},
    onImportSelectedSmbFolders: () -> Unit = {},
    onYouTubeQueryChange: (String) -> Unit,
    onSearchYouTube: () -> Unit,
    onPlayYouTubePlaylist: (YouTubePlaylistSummary) -> Unit,
    onDeleteRecentYouTube: (String) -> Unit,
    onExitYouTube: () -> Unit,
    onBindYouTubePlayer: (YouTubePlayer) -> Unit,
    onUnbindYouTubePlayer: () -> Unit,
    onYouTubeStateChanged: (Boolean) -> Unit,
    onYouTubeProgress: (Float, Float?) -> Unit,
    onYouTubeVideoChanged: (String) -> Unit,
    onYouTubeError: (String) -> Unit,
    onToggleYouTubePrivacyMode: () -> Unit = {},
    onReloadYouTubePlayer: () -> Unit = {},
    onSaveUpdateRepo: (String) -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    onCheckUpdatesForRepo: ((String) -> Unit)? = null,
    onOpenUpdateDialog: () -> Unit = {},
    showUpdateDialog: Boolean = false,
    isLandscapeFullscreen: Boolean = false,
    onSetLandscapeFullscreen: (Boolean) -> Unit = {},
    isCarModeLandscape: Boolean = false,
    onToggleCarModeLandscape: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var showSelSheet by remember { mutableStateOf(false) }
    var showMiscSheet by remember { mutableStateOf(false) }
    var showPresetsSheet by remember { mutableStateOf(false) }
    var showListOptsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(showUpdateDialog) {
        if (showUpdateDialog) {
            showMiscSheet = false
        }
    }

    val activeTrack = playbackState.currentTrack ?: displayedTracks.firstOrNull() ?: allTracks.firstOrNull()
    val selectedOrActiveTrack = remember(selectedTrackId, displayedTracks, activeTrack) {
        selectedTrackId?.let { id -> displayedTracks.firstOrNull { it.id == id } } ?: activeTrack
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(WinampColors.ChassisBg),
        contentAlignment = Alignment.TopCenter
    ) {
        val isLandscape = maxWidth > maxHeight
        val isCompactWidth = maxWidth < 370.dp
        val isWidePortraitTablet = !isLandscape && maxWidth >= 700.dp

        LaunchedEffect(isLandscape) {
            if (!isLandscape && isLandscapeFullscreen) {
                onSetLandscapeFullscreen(false)
            }
        }

        val availableLandscapeHeight = (maxHeight - 48.dp).coerceAtLeast(220.dp)
        val sizing = RackSizing(
            isLandscape = isLandscape,
            isCompactWidth = isCompactWidth,
            horizontalPadding = when {
                isLandscape -> if (isLandscapeFullscreen) 6.dp else 8.dp
                maxWidth >= 700.dp -> 14.dp
                isCompactWidth -> 4.dp
                else -> 6.dp
            },
            spectrumHeight = when {
                isLandscape && isCarModeLandscape -> (availableLandscapeHeight * 0.25f).coerceIn(48.dp, 136.dp)
                isLandscape -> (availableLandscapeHeight * 0.22f).coerceIn(36.dp, 120.dp)
                else -> (maxHeight * 0.052f).coerceIn(32.dp, 50.dp)
            },
            eqFaderHeight = when {
                isLandscape -> (availableLandscapeHeight - 106.dp).coerceIn(104.dp, 320.dp)
                else -> (maxHeight * 0.16f).coerceIn(106.dp, 162.dp)
            },
            playlistMinHeight = (maxHeight * 0.25f).coerceIn(170.dp, 260.dp),
            playlistMaxHeight = (maxHeight * 0.34f).coerceIn(220.dp, 380.dp),
            transportButtonHeight = when {
                isLandscape && isCarModeLandscape -> 56.dp
                isLandscape || maxWidth >= 700.dp -> 44.dp
                else -> 38.dp
            },
            footerButtonHeight = when {
                isLandscape && isCarModeLandscape -> 48.dp
                isLandscape || maxWidth >= 700.dp -> 42.dp
                else -> 38.dp
            },
            timerFontSize = when {
                isLandscape && isCarModeLandscape -> 28.sp
                isLandscape && maxWidth >= 820.dp -> 24.sp
                isLandscape -> 20.sp
                maxWidth >= 700.dp -> 24.sp
                isCompactWidth -> 18.sp
                else -> 21.sp
            },
            youtubePortraitMaxHeight = (maxHeight * 0.31f).coerceIn(165.dp, 320.dp),
            youtubeLandscapeMaxHeight = (availableLandscapeHeight - 62.dp).coerceIn(135.dp, 460.dp),
            isCarMode = isLandscape && isCarModeLandscape
        )

        val currentPosMs = if (playbackSourceMode == PlaybackSourceMode.YOUTUBE_REMOTE) {
            (youTubeState.currentSeconds * 1000f).toLong()
        } else {
            playbackState.positionMs
        }

        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = sizing.horizontalPadding, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val tabTitles = listOf(
                    stringResource(R.string.tab_player),
                    stringResource(R.string.tab_equalizer),
                    stringResource(R.string.tab_playlist)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabTitles.forEachIndexed { idx, label ->
                        val selected = pagerState.currentPage == idx
                        WinampMetallicButton(
                            onClick = {
                                coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                            },
                            testTag = "landscape_tab_$idx",
                            contentDesc = label,
                            showLed = true,
                            ledActive = selected,
                            minHeight = 36.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = if (selected) WinampColors.LcdGreen else WinampColors.TitleSilver
                            )
                        }
                    }

                    WinampMetallicButton(
                        onClick = onToggleCarModeLandscape,
                        testTag = "landscape_car_mode_toggle",
                        contentDesc = if (isCarModeLandscape) {
                            "Desactivar modo coche"
                        } else {
                            "Activar modo coche horizontal"
                        },
                        showLed = true,
                        ledActive = isCarModeLandscape,
                        minHeight = if (isCarModeLandscape) 44.dp else 36.dp
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DirectionsCar,
                            contentDescription = null,
                            tint = if (isCarModeLandscape) WinampColors.LcdGreen else WinampColors.EqGoldBright,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    WinampMetallicButton(
                        onClick = { onSetLandscapeFullscreen(!isLandscapeFullscreen) },
                        testTag = "landscape_fullscreen_toggle",
                        contentDesc = if (isLandscapeFullscreen) {
                            "Salir de pantalla completa"
                        } else {
                            "Pantalla completa horizontal"
                        },
                        showLed = true,
                        ledActive = isLandscapeFullscreen,
                        minHeight = if (isCarModeLandscape) 44.dp else 36.dp
                    ) {
                        Icon(
                            imageVector = if (isLandscapeFullscreen) {
                                Icons.Filled.FullscreenExit
                            } else {
                                Icons.Filled.Fullscreen
                            },
                            contentDescription = null,
                            tint = if (isLandscapeFullscreen) WinampColors.LcdGreen else WinampColors.EqGoldBright,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            PlayerDeckModule(
                                track = activeTrack,
                                trackIndex = displayedTracks.indexOfFirst { it.id == activeTrack?.id },
                                playbackState = playbackState,
                                playbackSourceMode = playbackSourceMode,
                                youTubeState = youTubeState,
                                spectrumBars = spectrumBars,
                                outputInfo = outputInfo,
                                eqEnabled = eqSettings.enabled,
                                sizing = sizing,
                                onPlay = onPlayExplicit,
                                onPause = onPauseExplicit,
                                onStop = onStopPlayback,
                                onPrev = onSkipPrevious,
                                onNext = onSkipNext,
                                onSeekTo = onSeekTo,
                                onEjectImport = { showAddSheet = true },
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onToggleEq = { onEqEnabledChange(!eqSettings.enabled) },
                                onFocusPlaylist = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(2) }
                                },
                                onToggleFavorite = { activeTrack?.let(onToggleFavorite) },
                                onExitYouTube = onExitYouTube,
                                onBindYouTubePlayer = onBindYouTubePlayer,
                                onUnbindYouTubePlayer = onUnbindYouTubePlayer,
                                onYouTubeStateChanged = onYouTubeStateChanged,
                                onYouTubeProgress = onYouTubeProgress,
                                onYouTubeVideoChanged = onYouTubeVideoChanged,
                                onYouTubeError = onYouTubeError,
                                onToggleYouTubePrivacyMode = onToggleYouTubePrivacyMode,
                                onReloadYouTubePlayer = onReloadYouTubePlayer
                            )
                        }

                        1 -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            EqualizerModule(
                                eqSettings = eqSettings,
                                sizing = sizing,
                                onToggleEnabled = { onEqEnabledChange(!eqSettings.enabled) },
                                onToggleAuto = onEqAutoToggle,
                                onOpenPresets = { showPresetsSheet = true },
                                onPreampChange = onPreampChange,
                                onBandChange = onBandChange
                            )
                        }

                        else -> PlaylistTerminalModule(
                            tracks = displayedTracks,
                            currentTrackId = playbackState.currentTrack?.id,
                            selectedTrackId = selectedTrackId,
                            activePlaylist = activePlaylist,
                            selectedFilter = selectedFilter,
                            positionMs = currentPosMs,
                            fillListHeight = true,
                            sizing = sizing,
                            onPlayTrack = onPlayTrack,
                            onSelectTrack = onSelectTrack,
                            onAdd = { showAddSheet = true },
                            onRem = onRemoveSelectedOrActive,
                            onSel = { showSelSheet = true },
                            onMisc = { showMiscSheet = true },
                            onListOpts = { showListOptsSheet = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else if (isWidePortraitTablet) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1040.dp)
                    .fillMaxSize()
                    .padding(horizontal = sizing.horizontalPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PlayerDeckModule(
                        track = activeTrack,
                        trackIndex = displayedTracks.indexOfFirst { it.id == activeTrack?.id },
                        playbackState = playbackState,
                        playbackSourceMode = playbackSourceMode,
                        youTubeState = youTubeState,
                        spectrumBars = spectrumBars,
                        outputInfo = outputInfo,
                        eqEnabled = eqSettings.enabled,
                        sizing = sizing,
                        onPlay = onPlayExplicit,
                        onPause = onPauseExplicit,
                        onStop = onStopPlayback,
                        onPrev = onSkipPrevious,
                        onNext = onSkipNext,
                        onSeekTo = onSeekTo,
                        onEjectImport = { showAddSheet = true },
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onToggleEq = { onEqEnabledChange(!eqSettings.enabled) },
                        onFocusPlaylist = { showListOptsSheet = true },
                        onToggleFavorite = { activeTrack?.let(onToggleFavorite) },
                        onExitYouTube = onExitYouTube,
                        onBindYouTubePlayer = onBindYouTubePlayer,
                        onUnbindYouTubePlayer = onUnbindYouTubePlayer,
                        onYouTubeStateChanged = onYouTubeStateChanged,
                        onYouTubeProgress = onYouTubeProgress,
                        onYouTubeVideoChanged = onYouTubeVideoChanged,
                        onYouTubeError = onYouTubeError,
                        onToggleYouTubePrivacyMode = onToggleYouTubePrivacyMode,
                        onReloadYouTubePlayer = onReloadYouTubePlayer
                    )
                    EqualizerModule(
                        eqSettings = eqSettings,
                        sizing = sizing,
                        onToggleEnabled = { onEqEnabledChange(!eqSettings.enabled) },
                        onToggleAuto = onEqAutoToggle,
                        onOpenPresets = { showPresetsSheet = true },
                        onPreampChange = onPreampChange,
                        onBandChange = onBandChange
                    )
                }

                PlaylistTerminalModule(
                    tracks = displayedTracks,
                    currentTrackId = playbackState.currentTrack?.id,
                    selectedTrackId = selectedTrackId,
                    activePlaylist = activePlaylist,
                    selectedFilter = selectedFilter,
                    positionMs = currentPosMs,
                    fillListHeight = true,
                    sizing = sizing,
                    onPlayTrack = onPlayTrack,
                    onSelectTrack = onSelectTrack,
                    onAdd = { showAddSheet = true },
                    onRem = onRemoveSelectedOrActive,
                    onSel = { showSelSheet = true },
                    onMisc = { showMiscSheet = true },
                    onListOpts = { showListOptsSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 580.dp)
                    .fillMaxWidth()
                    .padding(horizontal = sizing.horizontalPadding, vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                PlayerDeckModule(
                    track = activeTrack,
                    trackIndex = displayedTracks.indexOfFirst { it.id == activeTrack?.id },
                    playbackState = playbackState,
                    playbackSourceMode = playbackSourceMode,
                    youTubeState = youTubeState,
                    spectrumBars = spectrumBars,
                    outputInfo = outputInfo,
                    eqEnabled = eqSettings.enabled,
                    sizing = sizing,
                    onPlay = onPlayExplicit,
                    onPause = onPauseExplicit,
                    onStop = onStopPlayback,
                    onPrev = onSkipPrevious,
                    onNext = onSkipNext,
                    onSeekTo = onSeekTo,
                    onEjectImport = { showAddSheet = true },
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onToggleEq = { onEqEnabledChange(!eqSettings.enabled) },
                    onFocusPlaylist = { showListOptsSheet = true },
                    onToggleFavorite = { activeTrack?.let(onToggleFavorite) },
                    onExitYouTube = onExitYouTube,
                    onBindYouTubePlayer = onBindYouTubePlayer,
                    onUnbindYouTubePlayer = onUnbindYouTubePlayer,
                    onYouTubeStateChanged = onYouTubeStateChanged,
                    onYouTubeProgress = onYouTubeProgress,
                    onYouTubeVideoChanged = onYouTubeVideoChanged,
                    onYouTubeError = onYouTubeError,
                    onToggleYouTubePrivacyMode = onToggleYouTubePrivacyMode,
                    onReloadYouTubePlayer = onReloadYouTubePlayer
                )

                EqualizerModule(
                    eqSettings = eqSettings,
                    sizing = sizing,
                    onToggleEnabled = { onEqEnabledChange(!eqSettings.enabled) },
                    onToggleAuto = onEqAutoToggle,
                    onOpenPresets = { showPresetsSheet = true },
                    onPreampChange = onPreampChange,
                    onBandChange = onBandChange
                )

                PlaylistTerminalModule(
                    tracks = displayedTracks,
                    currentTrackId = playbackState.currentTrack?.id,
                    selectedTrackId = selectedTrackId,
                    activePlaylist = activePlaylist,
                    selectedFilter = selectedFilter,
                    positionMs = currentPosMs,
                    fillListHeight = false,
                    sizing = sizing,
                    onPlayTrack = onPlayTrack,
                    onSelectTrack = onSelectTrack,
                    onAdd = { showAddSheet = true },
                    onRem = onRemoveSelectedOrActive,
                    onSel = { showSelSheet = true },
                    onMisc = { showMiscSheet = true },
                    onListOpts = { showListOptsSheet = true }
                )
            }
        }
    }

    if (showAddSheet) {
        AddSourceSheet(
            activePlaylist = activePlaylist,
            allTracks = allTracks,
            isImporting = isImporting,
            smbServers = smbServers,
            smbBrowserState = smbBrowserState,
            lanScanState = lanScanState,
            youTubeSearchState = youTubeSearchState,
            recentYouTubePlaylists = recentYouTubePlaylists,
            onDismiss = { showAddSheet = false },
            onImportFiles = {
                showAddSheet = false
                onLaunchFilePicker()
            },
            onImportFolder = {
                showAddSheet = false
                onLaunchFolderPicker()
            },
            onAddTrackToPlaylist = onAddTrackToPlaylist,
            onScanLanSmb = onScanLanSmb,
            onConnectDiscoveredSmb = onConnectDiscoveredSmb,
            onSaveAndConnectSmb = { profile ->
                (onSaveAndConnectSmb ?: onSaveSmbServer)(profile)
            },
            onDeleteSmbServer = onDeleteSmbServer,
            onOpenSmbServer = onOpenSmbServer,
            onNavigateSmbEntry = onNavigateSmbEntry,
            onNavigateSmbUp = onNavigateSmbUp,
            onCloseSmbBrowser = onCloseSmbBrowser,
            onImportAllSmbInFolder = onImportAllSmbInFolder,
            onToggleSmbFolderSelection = onToggleSmbFolderSelection,
            onSelectAllSmbFolders = onSelectAllSmbFolders,
            onClearSmbFolderSelection = onClearSmbFolderSelection,
            onImportSelectedSmbFolders = onImportSelectedSmbFolders,
            onYouTubeQueryChange = onYouTubeQueryChange,
            onSearchYouTube = onSearchYouTube,
            onPlayYouTubePlaylist = { pl ->
                onPlayYouTubePlaylist(pl)
                if (pagerState.currentPage != 0) {
                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                }
            },
            onDeleteRecentYouTube = onDeleteRecentYouTube
        )
    }

    if (showSelSheet) {
        SelectFilterSheet(
            searchQuery = searchQuery,
            selectedFilter = selectedFilter,
            onSearchQueryChange = onSearchQueryChange,
            onSelectFilter = onSelectFilter,
            onDismiss = { showSelSheet = false }
        )
    }

    if (showMiscSheet) {
        MiscManagerSheet(
            playlists = playlists,
            activePlaylistId = activePlaylist?.id,
            selectedOrActiveTrack = selectedOrActiveTrack,
            outputInfo = outputInfo,
            bitPerfectRequested = eqSettings.bitPerfectUsbRequested,
            appUpdatePrefs = appUpdatePrefs,
            appUpdateState = appUpdateState,
            onSelectPlaylist = onSelectPlaylist,
            onCreatePlaylist = onCreatePlaylist,
            onRenamePlaylist = onRenamePlaylist,
            onDeletePlaylist = onDeletePlaylist,
            onToggleFavorite = onToggleFavorite,
            onToggleBitPerfectUsb = onToggleBitPerfectUsb,
            onSaveUpdateRepo = onSaveUpdateRepo,
            onCheckUpdates = { repo ->
                if (onCheckUpdatesForRepo != null) {
                    onCheckUpdatesForRepo(repo)
                } else {
                    onSaveUpdateRepo(repo)
                    onCheckUpdates()
                }
            },
            onOpenUpdateDialog = {
                showMiscSheet = false
                onOpenUpdateDialog()
            },
            onDismiss = { showMiscSheet = false }
        )
    }

    if (showPresetsSheet) {
        EqPresetsSheet(
            activePresetId = eqSettings.activePresetId,
            customPresets = customEqPresets,
            onApplyPreset = onApplyPreset,
            onResetFlat = onResetEqFlat,
            onSaveCustomPreset = onSaveCustomPreset,
            onDeleteCustomPreset = onDeleteCustomPreset,
            onDismiss = { showPresetsSheet = false }
        )
    }

    if (showListOptsSheet) {
        ListOptsSheet(
            activePlaylist = activePlaylist,
            canReorderSelected = selectedTrackId != null || playbackState.currentTrack != null,
            onPlayAll = onPlayAll,
            onMoveSelected = onMoveSelectedInPlaylist,
            onDismiss = { showListOptsSheet = false }
        )
    }
}
