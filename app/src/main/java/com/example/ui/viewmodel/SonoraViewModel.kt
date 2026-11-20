package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.BuildConfig
import com.example.audio.AudioOutputInfo
import com.example.audio.AudioOutputMonitor
import com.example.audio.GraphicEqAudioProcessor
import com.example.audio.PlaybackService
import com.example.audio.PlaybackUiState
import com.example.audio.PlayerController
import com.example.audio.SystemAudioEffectEq
import com.example.audio.YouTubeBgBridge
import com.example.audio.YouTubeBgCommand
import com.example.data.local.AppUpdatePrefs
import com.example.data.local.EqSettingsState
import com.example.data.local.PreferencesStore
import com.example.data.repository.LanSmbScanner
import com.example.data.repository.LibraryRepository
import com.example.data.repository.SmbRepository
import com.example.data.repository.YouTubeRepository
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.DiscoveredSmbHost
import com.example.domain.model.EqPreset
import com.example.domain.model.PlaybackSourceMode
import com.example.domain.model.Playlist
import com.example.domain.model.SmbBrowserEntry
import com.example.domain.model.SmbServerProfile
import com.example.domain.model.Track
import com.example.domain.model.YouTubePlaybackState
import com.example.domain.model.YouTubePlaylistSummary
import com.example.update.ApkInstallResult
import com.example.update.AppUpdateManager
import com.example.update.AppUpdateUiState
import com.example.update.ReleaseUpdateInfo
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryFormatFilter(val label: String) {
    ALL("Todos"),
    FLAC("FLAC"),
    ALAC("ALAC"),
    DSD("DSD"),
    OTHER("WAV / Otros"),
    FAVORITES("Favoritos")
}

data class SmbBrowserUiState(
    val activeServer: SmbServerProfile? = null,
    val currentShare: String = "",
    val currentPath: String = "",
    val entries: List<SmbBrowserEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class LanScanUiState(
    val isScanning: Boolean = false,
    val discoveredHosts: List<DiscoveredSmbHost> = emptyList(),
    val hasScannedOnce: Boolean = false,
    val errorMessage: String? = null
)

data class YouTubeSearchUiState(
    val query: String = "",
    val results: List<YouTubePlaylistSummary> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null
)

@UnstableApi
class SonoraViewModel(
    private val appContext: Context,
    private val repository: LibraryRepository,
    private val smbRepository: SmbRepository,
    private val lanSmbScanner: LanSmbScanner,
    private val youTubeRepository: YouTubeRepository,
    private val preferencesStore: PreferencesStore,
    private val playerController: PlayerController,
    private val eqProcessor: GraphicEqAudioProcessor,
    private val systemAudioEffectEq: SystemAudioEffectEq,
    private val outputMonitor: AudioOutputMonitor,
    private val appUpdateManager: AppUpdateManager
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(LibraryFormatFilter.ALL)
    val selectedFilter: StateFlow<LibraryFormatFilter> = _selectedFilter.asStateFlow()

    private val _activePlaylistId = MutableStateFlow<Long?>(null)
    val activePlaylistId: StateFlow<Long?> = _activePlaylistId.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<Long?>(null)
    val selectedTrackId: StateFlow<Long?> = _selectedTrackId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _playbackSourceMode = MutableStateFlow(PlaybackSourceMode.MEDIA3_AUDIO)
    val playbackSourceMode: StateFlow<PlaybackSourceMode> = _playbackSourceMode.asStateFlow()

    private val _smbBrowserState = MutableStateFlow(SmbBrowserUiState())
    val smbBrowserState: StateFlow<SmbBrowserUiState> = _smbBrowserState.asStateFlow()

    private val _lanScanState = MutableStateFlow(LanScanUiState())
    val lanScanState: StateFlow<LanScanUiState> = _lanScanState.asStateFlow()

    private val _youTubeSearchState = MutableStateFlow(YouTubeSearchUiState())
    val youTubeSearchState: StateFlow<YouTubeSearchUiState> = _youTubeSearchState.asStateFlow()

    private val _youTubePlaybackState = MutableStateFlow(YouTubePlaybackState())
    val youTubePlaybackState: StateFlow<YouTubePlaybackState> = _youTubePlaybackState.asStateFlow()

    private var activeYouTubePlayer: YouTubePlayer? = null
    private var lanScanJob: Job? = null
    private var eqSaveJob: Job? = null
    private var hasUserModifiedEq = false

    val allTracks: StateFlow<List<Track>> = repository.allTracksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.allPlaylistsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smbServers: StateFlow<List<SmbServerProfile>> = smbRepository.allServersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentYouTubePlaylists: StateFlow<List<YouTubePlaylistSummary>> = youTubeRepository.recentPlaylistsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activePlaylist: StateFlow<Playlist?> = combine(playlists, _activePlaylistId) { lists, id ->
        if (id == null) null else lists.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val displayedTracks: StateFlow<List<Track>> = combine(
        allTracks,
        activePlaylist,
        _searchQuery,
        _selectedFilter
    ) { library, playlist, query, filter ->
        val baseTracks = playlist?.tracks ?: library
        val q = query.trim().lowercase()
        baseTracks.filter { track ->
            val matchesFilter = when (filter) {
                LibraryFormatFilter.ALL -> true
                LibraryFormatFilter.FLAC -> track.format == AudioFormatBadge.FLAC
                LibraryFormatFilter.ALAC -> track.format == AudioFormatBadge.ALAC
                LibraryFormatFilter.DSD -> track.format == AudioFormatBadge.DSD
                LibraryFormatFilter.OTHER -> track.format !in setOf(
                    AudioFormatBadge.FLAC,
                    AudioFormatBadge.ALAC,
                    AudioFormatBadge.DSD
                )
                LibraryFormatFilter.FAVORITES -> track.isFavorite
            }
            val matchesQuery = q.isEmpty() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q) ||
                track.format.label.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customEqPresets: StateFlow<List<EqPreset>> = repository.customEqPresetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _eqSettings = MutableStateFlow(EqSettingsState())
    val eqSettings: StateFlow<EqSettingsState> = _eqSettings.asStateFlow()

    private val _appUpdateState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    val appUpdatePrefs: StateFlow<AppUpdatePrefs> = preferencesStore.appUpdatePrefsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUpdatePrefs())

    private var autoIntroAttempted = false

    val playbackState: StateFlow<PlaybackUiState> = playerController.playbackState
    val spectrumBars: StateFlow<FloatArray> = playerController.spectrumFlow
    val outputInfo: StateFlow<AudioOutputInfo> = outputMonitor.outputInfo

    init {
        viewModelScope.launch {
            val savedEq = preferencesStore.eqSettingsFlow.first()
            if (!hasUserModifiedEq) {
                _eqSettings.value = savedEq
                pushEqToAudioEngines(savedEq)
            }
        }

        allTracks
            .onEach { tracks ->
                playerController.syncTrackMetadataUpdates(tracks)
                if (!autoIntroAttempted && tracks.isNotEmpty()) {
                    maybePlayFirstLaunchDroidIntro(tracks)
                }
            }
            .launchIn(viewModelScope)

        YouTubeBgBridge.commands
            .onEach { cmd ->
                when (cmd) {
                    YouTubeBgCommand.TOGGLE_PLAY_PAUSE -> {
                        if (_youTubePlaybackState.value.isPlaying) {
                            activeYouTubePlayer?.pause()
                        } else {
                            activeYouTubePlayer?.play()
                        }
                    }
                    YouTubeBgCommand.NEXT -> activeYouTubePlayer?.nextVideo()
                    YouTubeBgCommand.PREVIOUS -> activeYouTubePlayer?.previousVideo()
                    YouTubeBgCommand.STOP -> stopPlayback()
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val prefs = preferencesStore.appUpdatePrefsFlow.first()
            if (!prefs.githubRepo.startsWith("usuario/")) {
                checkForUpdates(manual = false)
            }
        }
    }

    private fun maybePlayFirstLaunchDroidIntro(tracks: List<Track>) {
        if (autoIntroAttempted) return
        val introIdx = tracks.indexOfFirst { it.uriString.contains("andamp_droid_intro.wav") }
        if (introIdx < 0) return
        autoIntroAttempted = true
        viewModelScope.launch {
            val alreadyPlayed = preferencesStore.hasPlayedDroidIntroFlow.first()
            if (!alreadyPlayed) {
                preferencesStore.markDroidIntroPlayed()
                _selectedTrackId.value = tracks[introIdx].id
                playerController.playTracks(tracks, introIdx)
            }
        }
    }

    fun selectPlaylistView(playlistId: Long?) {
        _activePlaylistId.value = playlistId
    }

    fun selectTrack(trackId: Long?) {
        _selectedTrackId.value = trackId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFormatFilter(filter: LibraryFormatFilter) {
        _selectedFilter.value = filter
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
        playerController.clearError()
    }

    fun playTrackFromDisplayed(track: Track) {
        switchToMedia3Mode()
        _selectedTrackId.value = track.id
        val list = displayedTracks.value.ifEmpty { listOf(track) }
        val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playerController.playTracks(list, idx)
    }

    fun playAllDisplayed(shuffle: Boolean = false) {
        val list = displayedTracks.value
        if (list.isEmpty()) {
            _statusMessage.value = "No hay pistas para reproducir"
            return
        }
        switchToMedia3Mode()
        val ordered = if (shuffle) list.shuffled() else list
        _selectedTrackId.value = ordered.first().id
        playerController.playTracks(ordered, 0)
    }

    fun playExplicit() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.play()
            return
        }
        val current = playbackState.value.currentTrack
        if (current == null) {
            val list = displayedTracks.value.ifEmpty { allTracks.value }
            val selected = _selectedTrackId.value?.let { sid -> list.firstOrNull { it.id == sid } } ?: list.firstOrNull()
            if (selected != null) {
                val idx = list.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
                playerController.playTracks(list, idx)
            }
        } else {
            playerController.play()
        }
    }

    fun pauseExplicit() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.pause()
        } else {
            playerController.pause()
        }
    }

    fun stopPlayback() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.pause()
            activeYouTubePlayer?.seekTo(0f)
            _youTubePlaybackState.update { it.copy(isPlaying = false, currentSeconds = 0f) }
            PlaybackService.stopYouTubeBackground(appContext)
        } else {
            playerController.stopPlayback()
        }
    }

    fun seekTo(positionMs: Long) {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.seekTo(positionMs / 1000f)
            _youTubePlaybackState.update { it.copy(currentSeconds = positionMs / 1000f) }
        } else {
            playerController.seekTo(positionMs)
        }
    }

    fun skipNext() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.nextVideo()
        } else {
            playerController.skipNext()
        }
    }

    fun skipPrevious() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.previousVideo()
        } else {
            playerController.skipPrevious()
        }
    }

    fun toggleShuffle() = playerController.toggleShuffle()
    fun cycleRepeatMode() = playerController.cycleRepeatMode()

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
        }
    }

    fun removeSelectedOrActiveTrack() {
        val targetId = _selectedTrackId.value ?: playbackState.value.currentTrack?.id
        if (targetId == null) {
            _statusMessage.value = "Selecciona una pista de la lista primero"
            return
        }
        val activePl = activePlaylist.value
        viewModelScope.launch {
            if (activePl != null) {
                repository.removeTrackFromPlaylist(activePl.id, targetId)
                _statusMessage.value = "Pista quitada de ${activePl.name}"
            } else {
                repository.deleteTrack(targetId)
                _statusMessage.value = "Pista eliminada de la biblioteca"
            }
            if (_selectedTrackId.value == targetId) {
                _selectedTrackId.value = null
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            val imported = repository.importDocumentUris(uris)
            _isImporting.value = false
            _statusMessage.value = if (imported > 0) {
                "$imported pista(s) importada(s)"
            } else {
                "Las pistas seleccionadas ya estaban en la biblioteca"
            }
        }
    }

    fun importFolder(treeUri: Uri?) {
        if (treeUri == null) return
        viewModelScope.launch {
            _isImporting.value = true
            val imported = repository.importFolderTree(treeUri)
            _isImporting.value = false
            _statusMessage.value = if (imported > 0) {
                "$imported pista(s) encontrada(s) en la carpeta"
            } else {
                "No se encontraron pistas nuevas en esa carpeta"
            }
        }
    }

    // --- Samba / NAS LAN Discovery, Profiles & Browser ---
    fun scanLanForSmbServers() {
        lanScanJob?.cancel()
        lanScanJob = viewModelScope.launch {
            _lanScanState.update {
                it.copy(isScanning = true, errorMessage = null)
            }
            runCatching {
                lanSmbScanner.scanLanHosts { incremental ->
                    _lanScanState.update { it.copy(discoveredHosts = incremental) }
                }
            }.fold(
                onSuccess = { hosts ->
                    _lanScanState.value = LanScanUiState(
                        isScanning = false,
                        discoveredHosts = hosts,
                        hasScannedOnce = true,
                        errorMessage = if (hosts.isEmpty()) {
                            "No se detectaron equipos SMB en esta red Wi-Fi"
                        } else null
                    )
                },
                onFailure = { err ->
                    _lanScanState.update {
                        it.copy(
                            isScanning = false,
                            hasScannedOnce = true,
                            errorMessage = err.localizedMessage ?: "Error al escanear la red local"
                        )
                    }
                }
            )
        }
    }

    fun connectDiscoveredSmbHost(
        host: DiscoveredSmbHost,
        defaultShare: String = "",
        isGuest: Boolean = true,
        username: String = "",
        password: String = ""
    ) {
        viewModelScope.launch {
            val existing = smbServers.value.firstOrNull {
                it.host.equals(host.host, ignoreCase = true) && it.port == host.port
            }
            val profile = SmbServerProfile(
                id = existing?.id ?: 0L,
                alias = existing?.alias ?: host.displayName.ifBlank { host.host },
                host = host.host,
                port = host.port,
                defaultShare = defaultShare.trim().ifBlank { existing?.defaultShare.orEmpty() },
                username = if (isGuest) "" else username.trim(),
                password = if (isGuest) "" else password,
                isGuest = isGuest
            )
            val savedId = smbRepository.saveServer(profile)
            val effectiveId = if (savedId > 0L) savedId else profile.id
            val resolved = smbRepository.getServerById(effectiveId) ?: profile.copy(id = effectiveId)
            loadSmbDirectory(resolved, resolved.defaultShare, "")
        }
    }

    fun saveAndConnectSmbServer(profile: SmbServerProfile, connectImmediately: Boolean = true) {
        if (profile.host.isBlank()) return
        viewModelScope.launch {
            val cleaned = profile.copy(
                alias = profile.alias.trim().ifBlank { profile.host.trim() },
                host = profile.host.trim(),
                defaultShare = profile.defaultShare.trim()
            )
            val savedId = smbRepository.saveServer(cleaned)
            val effectiveId = if (savedId > 0L) savedId else cleaned.id
            val saved = smbRepository.getServerById(effectiveId) ?: cleaned.copy(id = effectiveId)
            _statusMessage.value = "Servidor NAS \"${saved.alias}\" guardado"
            if (connectImmediately) {
                loadSmbDirectory(saved, saved.defaultShare, "")
            }
        }
    }

    fun saveSmbServer(profile: SmbServerProfile) {
        saveAndConnectSmbServer(profile, connectImmediately = false)
    }

    fun deleteSmbServer(serverId: Long) {
        viewModelScope.launch {
            smbRepository.deleteServer(serverId)
            if (_smbBrowserState.value.activeServer?.id == serverId) {
                closeSmbBrowser()
            }
            _statusMessage.value = "Servidor NAS eliminado"
        }
    }

    fun openSmbServer(server: SmbServerProfile, customShare: String = server.defaultShare) {
        loadSmbDirectory(server, customShare, "")
    }

    fun closeSmbBrowser() {
        _smbBrowserState.value = SmbBrowserUiState()
    }

    fun navigateSmbEntry(entry: SmbBrowserEntry) {
        val server = _smbBrowserState.value.activeServer ?: return
        if (entry.isDirectory) {
            loadSmbDirectory(server, entry.shareName, entry.relativePath)
        } else {
            viewModelScope.launch {
                _smbBrowserState.update { it.copy(isLoading = true, errorMessage = null) }
                val added = smbRepository.importSmbEntries(server, listOf(entry), _activePlaylistId.value)
                _smbBrowserState.update { it.copy(isLoading = false) }
                val targetUri = SmbRepository.buildSmbUri(server.id, entry.shareName, entry.relativePath, entry.name)
                val track = allTracks.value.firstOrNull { it.uriString == targetUri }
                if (track != null) {
                    playTrackFromDisplayed(track)
                }
                _statusMessage.value = if (added > 0) {
                    "Pista NAS añadida y reproduciendo"
                } else {
                    "Reproduciendo pista desde NAS"
                }
            }
        }
    }

    fun navigateSmbUp() {
        val st = _smbBrowserState.value
        val server = st.activeServer ?: return
        if (st.currentPath.isBlank()) {
            closeSmbBrowser()
        } else {
            val normalized = st.currentPath.trimEnd('\\')
            val parent = if ('\\' in normalized) normalized.substringBeforeLast('\\') else ""
            loadSmbDirectory(server, st.currentShare, parent)
        }
    }

    fun importAllAudioInCurrentSmbFolder() {
        val st = _smbBrowserState.value
        val server = st.activeServer ?: return
        val audioEntries = st.entries.filter { !it.isDirectory }
        if (audioEntries.isEmpty()) {
            _statusMessage.value = "No hay archivos de audio compatibles en esta carpeta"
            return
        }
        viewModelScope.launch {
            _smbBrowserState.update { it.copy(isLoading = true) }
            val count = smbRepository.importSmbEntries(server, audioEntries, _activePlaylistId.value)
            _smbBrowserState.update { it.copy(isLoading = false) }
            _statusMessage.value = if (count > 0) {
                "$count pista(s) del NAS importadas"
            } else {
                "Las pistas de esta carpeta ya estaban en la biblioteca"
            }
        }
    }

    private fun loadSmbDirectory(server: SmbServerProfile, shareName: String, relativePath: String) {
        viewModelScope.launch {
            _smbBrowserState.value = SmbBrowserUiState(
                activeServer = server,
                currentShare = shareName,
                currentPath = relativePath,
                isLoading = true
            )
            val result = smbRepository.listDirectory(server, shareName, relativePath)
            result.fold(
                onSuccess = { (resolvedShare, entries) ->
                    _smbBrowserState.value = SmbBrowserUiState(
                        activeServer = server,
                        currentShare = resolvedShare,
                        currentPath = relativePath,
                        entries = entries,
                        isLoading = false
                    )
                },
                onFailure = { err ->
                    _smbBrowserState.value = SmbBrowserUiState(
                        activeServer = server,
                        currentShare = shareName,
                        currentPath = relativePath,
                        entries = emptyList(),
                        isLoading = false,
                        errorMessage = err.localizedMessage ?: "No se pudo conectar al servidor NAS"
                    )
                }
            )
        }
    }

    // --- YouTube Remote Playlist Search & Background Playback ---
    fun updateYouTubeQuery(query: String) {
        _youTubeSearchState.update { it.copy(query = query) }
    }

    fun searchYouTubePlaylists(query: String = _youTubeSearchState.value.query) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _youTubeSearchState.update { it.copy(query = query, isSearching = true, errorMessage = null) }
            val result = youTubeRepository.searchPlaylists(query)
            result.fold(
                onSuccess = { list ->
                    _youTubeSearchState.update {
                        it.copy(
                            results = list,
                            isSearching = false,
                            errorMessage = if (list.isEmpty()) "No se encontraron playlists para \"$query\"" else null
                        )
                    }
                },
                onFailure = { err ->
                    _youTubeSearchState.update {
                        it.copy(
                            isSearching = false,
                            errorMessage = err.localizedMessage ?: "Error al buscar en YouTube"
                        )
                    }
                }
            )
        }
    }

    fun playYouTubePlaylist(summary: YouTubePlaylistSummary) {
        playerController.pause()
        eqProcessor.clearSpectrum()
        _playbackSourceMode.value = PlaybackSourceMode.YOUTUBE_REMOTE
        _youTubePlaybackState.value = YouTubePlaybackState(
            activePlaylist = summary,
            isPlaying = true,
            currentSeconds = 0f,
            durationSeconds = 0f
        )
        systemAudioEffectEq.bindSession(
            audioSessionId = 0,
            shouldActivate = _eqSettings.value.enabled
        )
        PlaybackService.syncYouTubeBackground(
            context = appContext,
            title = summary.title,
            artist = summary.channelTitle,
            isPlaying = true
        )
        viewModelScope.launch {
            youTubeRepository.saveRecentPlaylist(summary)
        }
        _statusMessage.value = "Reproduciendo playlist de YouTube: ${summary.title}"
    }

    fun deleteRecentYouTubePlaylist(playlistId: String) {
        viewModelScope.launch {
            youTubeRepository.deleteRecentPlaylist(playlistId)
        }
    }

    fun exitYouTubeMode() {
        activeYouTubePlayer?.pause()
        activeYouTubePlayer = null
        _playbackSourceMode.value = PlaybackSourceMode.MEDIA3_AUDIO
        _youTubePlaybackState.value = YouTubePlaybackState()
        PlaybackService.stopYouTubeBackground(appContext)
        pushEqToAudioEngines(_eqSettings.value)
    }

    fun bindYouTubePlayer(player: YouTubePlayer) {
        activeYouTubePlayer = player
    }

    fun unbindYouTubePlayer() {
        activeYouTubePlayer = null
    }

    fun onYouTubeStateChanged(isPlaying: Boolean) {
        _youTubePlaybackState.update { it.copy(isPlaying = isPlaying, errorMessage = null) }
        val activePlaylist = _youTubePlaybackState.value.activePlaylist
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE && activePlaylist != null) {
            PlaybackService.syncYouTubeBackground(
                context = appContext,
                title = activePlaylist.title,
                artist = activePlaylist.channelTitle,
                isPlaying = isPlaying
            )
        }
    }

    fun onYouTubeProgress(currentSec: Float, durationSec: Float?) {
        _youTubePlaybackState.update { state ->
            state.copy(
                currentSeconds = currentSec.coerceAtLeast(0f),
                durationSeconds = durationSec?.takeIf { it > 0f } ?: state.durationSeconds
            )
        }
    }

    fun onYouTubeVideoChanged(videoId: String) {
        _youTubePlaybackState.update { it.copy(currentVideoId = videoId) }
    }

    fun onYouTubeError(message: String) {
        _youTubePlaybackState.update { it.copy(isPlaying = false, errorMessage = message) }
        _statusMessage.value = message
    }

    private fun switchToMedia3Mode() {
        if (_playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE) {
            activeYouTubePlayer?.pause()
            activeYouTubePlayer = null
            _playbackSourceMode.value = PlaybackSourceMode.MEDIA3_AUDIO
            _youTubePlaybackState.value = YouTubePlaybackState()
            PlaybackService.stopYouTubeBackground(appContext)
            pushEqToAudioEngines(_eqSettings.value)
        }
    }

    // --- Playlists CRUD ---
    fun createPlaylist(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val newId = repository.createPlaylist(name, description)
            _activePlaylistId.value = newId
            _statusMessage.value = "Lista \"$name\" creada y seleccionada"
        }
    }

    fun renamePlaylist(playlistId: Long, name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, name, description)
            _statusMessage.value = "Lista actualizada"
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_activePlaylistId.value == playlistId) {
                _activePlaylistId.value = null
            }
            _statusMessage.value = "Lista eliminada"
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
            _statusMessage.value = "Pista añadida a la lista"
        }
    }

    fun moveSelectedTrackInPlaylist(direction: Int) {
        val pl = activePlaylist.value ?: return
        val targetId = _selectedTrackId.value ?: playbackState.value.currentTrack?.id ?: return
        val fromIdx = pl.tracks.indexOfFirst { it.id == targetId }
        val toIdx = fromIdx + direction
        if (fromIdx !in pl.tracks.indices || toIdx !in pl.tracks.indices) return
        val reordered = pl.tracks.toMutableList()
        val item = reordered.removeAt(fromIdx)
        reordered.add(toIdx, item)
        viewModelScope.launch {
            repository.reorderPlaylistTracks(pl.id, reordered.map { it.id })
        }
    }

    // --- 10-Band Equalizer ---
    fun setEqEnabled(enabled: Boolean) {
        val updated = _eqSettings.value.copy(enabled = enabled)
        applyAndSaveEq(updated)
    }

    fun toggleEqAuto() {
        val current = _eqSettings.value
        val nextAuto = !current.autoGain
        val maxBoost = current.bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val adjustedPreamp = if (nextAuto && maxBoost > 0f) (-maxBoost * 0.7f).coerceIn(-12f, 0f) else current.preampDb
        applyAndSaveEq(current.copy(autoGain = nextAuto, preampDb = adjustedPreamp))
    }

    fun updateEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        val current = _eqSettings.value
        val newBands = current.bandsDb.toMutableList().apply {
            this[bandIndex] = gainDb
        }
        val adjustedPreamp = if (current.autoGain) {
            val maxBoost = newBands.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            (-maxBoost * 0.7f).coerceIn(-12f, 0f)
        } else current.preampDb
        applyAndSaveEq(current.copy(bandsDb = newBands, preampDb = adjustedPreamp, activePresetId = 0L))
    }

    fun updatePreamp(preampDb: Float) {
        val updated = _eqSettings.value.copy(preampDb = preampDb, autoGain = false, activePresetId = 0L)
        applyAndSaveEq(updated)
    }

    fun applyEqPreset(preset: EqPreset) {
        val updated = _eqSettings.value.copy(
            enabled = true,
            bandsDb = preset.bandsDb,
            preampDb = preset.preampDb,
            activePresetId = preset.id
        )
        applyAndSaveEq(updated)
    }

    fun resetEqFlat() {
        val updated = _eqSettings.value.copy(
            bandsDb = List(10) { 0f },
            preampDb = 0f,
            activePresetId = -1L
        )
        applyAndSaveEq(updated)
    }

    fun saveCustomEqPreset(name: String) {
        if (name.isBlank()) return
        val current = _eqSettings.value
        viewModelScope.launch {
            val newId = repository.saveCustomEqPreset(name, current.bandsDb, current.preampDb)
            applyAndSaveEq(current.copy(activePresetId = newId))
            _statusMessage.value = "Preset \"$name\" guardado"
        }
    }

    fun deleteCustomEqPreset(presetId: Long) {
        viewModelScope.launch {
            repository.deleteCustomEqPreset(presetId)
        }
    }

    fun setBitPerfectUsbRequested(enabled: Boolean) {
        val updated = _eqSettings.value.copy(bitPerfectUsbRequested = enabled)
        applyAndSaveEq(updated)
        outputMonitor.refresh(enabled)
    }

    // --- Internal GitHub Releases OTA Updater ---
    fun saveGithubUpdateRepo(repo: String) {
        viewModelScope.launch {
            preferencesStore.setGithubUpdateRepo(repo)
            _statusMessage.value = "Repositorio de actualizaciones guardado"
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            val prefs = preferencesStore.appUpdatePrefsFlow.first()
            _appUpdateState.value = AppUpdateUiState.Checking
            val result = appUpdateManager.checkLatestRelease(
                repo = prefs.githubRepo,
                currentVersion = BuildConfig.VERSION_NAME
            )
            result.fold(
                onSuccess = { release ->
                    if (release == null) {
                        _appUpdateState.value = AppUpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                        if (manual) {
                            _statusMessage.value = "AndAmp v${BuildConfig.VERSION_NAME} está actualizado"
                        }
                    } else {
                        _appUpdateState.value = AppUpdateUiState.Available(release)
                        if (manual || release.version != prefs.skippedVersion) {
                            _showUpdateDialog.value = true
                        }
                    }
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "No se pudo comprobar actualizaciones"
                    _appUpdateState.value = AppUpdateUiState.Error(msg)
                    if (manual) {
                        _statusMessage.value = msg
                    }
                }
            )
        }
    }

    fun downloadAndInstallUpdate(info: ReleaseUpdateInfo) {
        viewModelScope.launch {
            _showUpdateDialog.value = true
            _appUpdateState.value = AppUpdateUiState.Downloading(
                version = info.version,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = info.fileSizeBytes
            )
            val dl = appUpdateManager.downloadApk(info) { fraction, done, total ->
                _appUpdateState.value = AppUpdateUiState.Downloading(
                    version = info.version,
                    progress = fraction,
                    downloadedBytes = done,
                    totalBytes = total
                )
            }
            dl.fold(
                onSuccess = { file ->
                    when (appUpdateManager.launchApkInstaller(file)) {
                        ApkInstallResult.LAUNCHED_INSTALLER -> {
                            _appUpdateState.value = AppUpdateUiState.ReadyToInstall(info.version, file)
                        }
                        ApkInstallResult.NEEDS_PERMISSION -> {
                            _appUpdateState.value = AppUpdateUiState.NeedsInstallPermission(info.version, file)
                        }
                        ApkInstallResult.ERROR -> {
                            _appUpdateState.value = AppUpdateUiState.Error("No se pudo iniciar el instalador del paquete")
                        }
                    }
                },
                onFailure = { err ->
                    _appUpdateState.value = AppUpdateUiState.Error(
                        err.localizedMessage ?: "Error al descargar la actualización"
                    )
                }
            )
        }
    }

    fun openInstallUnknownAppsSettings() {
        appUpdateManager.openInstallUnknownAppsSettings()
    }

    fun retryPendingApkInstallIfAllowed() {
        val state = _appUpdateState.value
        if (state is AppUpdateUiState.NeedsInstallPermission) {
            installReadyApk(state.apkFile, state.version)
        }
    }

    fun installReadyApk(apkFile: java.io.File, version: String = BuildConfig.VERSION_NAME) {
        when (appUpdateManager.launchApkInstaller(apkFile)) {
            ApkInstallResult.LAUNCHED_INSTALLER -> {
                _appUpdateState.value = AppUpdateUiState.ReadyToInstall(version, apkFile)
            }
            ApkInstallResult.NEEDS_PERMISSION -> {
                _appUpdateState.value = AppUpdateUiState.NeedsInstallPermission(version, apkFile)
            }
            ApkInstallResult.ERROR -> {
                _appUpdateState.value = AppUpdateUiState.Error("No se pudo abrir el instalador del paquete")
            }
        }
    }

    fun dismissUpdateDialog(skipVersion: Boolean = false) {
        val st = _appUpdateState.value
        if (skipVersion && st is AppUpdateUiState.Available) {
            viewModelScope.launch {
                preferencesStore.setSkippedUpdateVersion(st.info.version)
            }
        }
        _showUpdateDialog.value = false
    }

    fun reopenUpdateDialog() {
        _showUpdateDialog.value = true
    }

    private fun applyAndSaveEq(state: EqSettingsState) {
        hasUserModifiedEq = true
        _eqSettings.value = state
        pushEqToAudioEngines(state)
        eqSaveJob?.cancel()
        eqSaveJob = viewModelScope.launch {
            delay(200L)
            preferencesStore.saveEqSettings(state)
        }
    }

    private fun pushEqToAudioEngines(state: EqSettingsState) {
        eqProcessor.updateSettings(state.enabled, state.bandsDb, state.preampDb)
        val shouldActivateSystemEq =
            _playbackSourceMode.value == PlaybackSourceMode.YOUTUBE_REMOTE ||
                !eqProcessor.isHandlingActiveStream
        systemAudioEffectEq.updateSettings(
            enabled = state.enabled,
            gainsDb = state.bandsDb,
            preamp = state.preampDb,
            shouldActivate = shouldActivateSystemEq
        )
    }

    class Factory(
        private val appContext: Context,
        private val repository: LibraryRepository,
        private val smbRepository: SmbRepository,
        private val lanSmbScanner: LanSmbScanner,
        private val youTubeRepository: YouTubeRepository,
        private val preferencesStore: PreferencesStore,
        private val playerController: PlayerController,
        private val eqProcessor: GraphicEqAudioProcessor,
        private val systemAudioEffectEq: SystemAudioEffectEq,
        private val outputMonitor: AudioOutputMonitor,
        private val appUpdateManager: AppUpdateManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SonoraViewModel(
                appContext,
                repository,
                smbRepository,
                lanSmbScanner,
                youTubeRepository,
                preferencesStore,
                playerController,
                eqProcessor,
                systemAudioEffectEq,
                outputMonitor,
                appUpdateManager
            ) as T
        }
    }
}
