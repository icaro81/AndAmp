package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.example.ui.screens.AppUpdateDialog
import com.example.ui.screens.WinampRackScreen
import com.example.ui.theme.SonoraTheme
import com.example.ui.theme.WinampColors
import com.example.ui.viewmodel.SonoraViewModel

@OptIn(UnstableApi::class)
@Composable
fun SonoraAppShell(viewModel: SonoraViewModel) {
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val displayedTracks by viewModel.displayedTracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val activePlaylist by viewModel.activePlaylist.collectAsStateWithLifecycle()
    val selectedTrackId by viewModel.selectedTrackId.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackSourceMode by viewModel.playbackSourceMode.collectAsStateWithLifecycle()
    val youTubeState by viewModel.youTubePlaybackState.collectAsStateWithLifecycle()
    val spectrumBars by viewModel.spectrumBars.collectAsStateWithLifecycle()
    val outputInfo by viewModel.outputInfo.collectAsStateWithLifecycle()
    val eqSettings by viewModel.eqSettings.collectAsStateWithLifecycle()
    val customEqPresets by viewModel.customEqPresets.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val smbServers by viewModel.smbServers.collectAsStateWithLifecycle()
    val smbBrowserState by viewModel.smbBrowserState.collectAsStateWithLifecycle()
    val lanScanState by viewModel.lanScanState.collectAsStateWithLifecycle()
    val youTubeSearchState by viewModel.youTubeSearchState.collectAsStateWithLifecycle()
    val recentYouTubePlaylists by viewModel.recentYouTubePlaylists.collectAsStateWithLifecycle()
    val appUpdatePrefs by viewModel.appUpdatePrefs.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var askedNotificationPermission by remember { mutableStateOf(false) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Playback continues regardless */ }

    fun ensureNotificationPermissionIfNeeded() {
        if (!askedNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedNotificationPermission = true
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        viewModel.importFiles(uris)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importFolder(treeUri)
        }
    }

    SonoraTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val activeBanner = statusMessage ?: playbackState.errorMessage ?: youTubeState.errorMessage

        LaunchedEffect(activeBanner) {
            if (!activeBanner.isNullOrBlank()) {
                snackbarHostState.showSnackbar(activeBanner)
                viewModel.clearStatusMessage()
            }
        }

        Scaffold(
            containerColor = WinampColors.ChassisBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            WinampRackScreen(
                playbackState = playbackState,
                playbackSourceMode = playbackSourceMode,
                youTubeState = youTubeState,
                spectrumBars = spectrumBars,
                outputInfo = outputInfo,
                eqSettings = eqSettings,
                customEqPresets = customEqPresets,
                allTracks = allTracks,
                displayedTracks = displayedTracks,
                playlists = playlists,
                activePlaylist = activePlaylist,
                selectedTrackId = selectedTrackId,
                searchQuery = searchQuery,
                selectedFilter = selectedFilter,
                isImporting = isImporting,
                smbServers = smbServers,
                smbBrowserState = smbBrowserState,
                lanScanState = lanScanState,
                youTubeSearchState = youTubeSearchState,
                recentYouTubePlaylists = recentYouTubePlaylists,
                appUpdatePrefs = appUpdatePrefs,
                appUpdateState = appUpdateState,
                onPlayExplicit = {
                    ensureNotificationPermissionIfNeeded()
                    viewModel.playExplicit()
                },
                onPauseExplicit = viewModel::pauseExplicit,
                onStopPlayback = viewModel::stopPlayback,
                onSkipPrevious = viewModel::skipPrevious,
                onSkipNext = viewModel::skipNext,
                onSeekTo = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onEqEnabledChange = viewModel::setEqEnabled,
                onEqAutoToggle = viewModel::toggleEqAuto,
                onPreampChange = viewModel::updatePreamp,
                onBandChange = viewModel::updateEqBand,
                onApplyPreset = viewModel::applyEqPreset,
                onResetEqFlat = viewModel::resetEqFlat,
                onSaveCustomPreset = viewModel::saveCustomEqPreset,
                onDeleteCustomPreset = viewModel::deleteCustomEqPreset,
                onPlayTrack = { track ->
                    ensureNotificationPermissionIfNeeded()
                    viewModel.playTrackFromDisplayed(track)
                },
                onSelectTrack = viewModel::selectTrack,
                onRemoveSelectedOrActive = viewModel::removeSelectedOrActiveTrack,
                onSelectPlaylist = viewModel::selectPlaylistView,
                onCreatePlaylist = viewModel::createPlaylist,
                onRenamePlaylist = viewModel::renamePlaylist,
                onDeletePlaylist = viewModel::deletePlaylist,
                onAddTrackToPlaylist = viewModel::addTrackToPlaylist,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleBitPerfectUsb = viewModel::setBitPerfectUsbRequested,
                onSearchQueryChange = viewModel::setSearchQuery,
                onSelectFilter = viewModel::setFormatFilter,
                onPlayAll = { shuffle ->
                    ensureNotificationPermissionIfNeeded()
                    viewModel.playAllDisplayed(shuffle)
                },
                onMoveSelectedInPlaylist = viewModel::moveSelectedTrackInPlaylist,
                onLaunchFilePicker = {
                    filePickerLauncher.launch(arrayOf("audio/*", "*/*"))
                },
                onLaunchFolderPicker = {
                    folderPickerLauncher.launch(null)
                },
                onScanLanSmb = viewModel::scanLanForSmbServers,
                onConnectDiscoveredSmb = viewModel::connectDiscoveredSmbHost,
                onSaveAndConnectSmb = viewModel::saveAndConnectSmbServer,
                onSaveSmbServer = viewModel::saveSmbServer,
                onDeleteSmbServer = viewModel::deleteSmbServer,
                onOpenSmbServer = { server -> viewModel.openSmbServer(server) },
                onNavigateSmbEntry = viewModel::navigateSmbEntry,
                onNavigateSmbUp = viewModel::navigateSmbUp,
                onCloseSmbBrowser = viewModel::closeSmbBrowser,
                onImportAllSmbInFolder = viewModel::importAllAudioInCurrentSmbFolder,
                onYouTubeQueryChange = viewModel::updateYouTubeQuery,
                onSearchYouTube = { viewModel.searchYouTubePlaylists() },
                onPlayYouTubePlaylist = { pl ->
                    ensureNotificationPermissionIfNeeded()
                    viewModel.playYouTubePlaylist(pl)
                },
                onDeleteRecentYouTube = viewModel::deleteRecentYouTubePlaylist,
                onExitYouTube = viewModel::exitYouTubeMode,
                onBindYouTubePlayer = viewModel::bindYouTubePlayer,
                onUnbindYouTubePlayer = viewModel::unbindYouTubePlayer,
                onYouTubeStateChanged = viewModel::onYouTubeStateChanged,
                onYouTubeProgress = viewModel::onYouTubeProgress,
                onYouTubeVideoChanged = viewModel::onYouTubeVideoChanged,
                onYouTubeError = viewModel::onYouTubeError,
                onToggleYouTubePrivacyMode = viewModel::toggleYouTubePrivacyMode,
                onReloadYouTubePlayer = viewModel::reloadYouTubePlayer,
                onSaveUpdateRepo = viewModel::saveGithubUpdateRepo,
                onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                onCheckUpdatesForRepo = { repo -> viewModel.checkForUpdates(manual = true, repoOverride = repo) },
                onOpenUpdateDialog = viewModel::reopenUpdateDialog,
                showUpdateDialog = showUpdateDialog,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }

        if (showUpdateDialog) {
            AppUpdateDialog(
                state = appUpdateState,
                onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
                onInstallReadyApk = { file ->
                    viewModel.installReadyApk(file)
                },
                onOpenPermissionSettings = viewModel::openInstallUnknownAppsSettings,
                onDismiss = viewModel::dismissUpdateDialog
            )
        }
    }
}
