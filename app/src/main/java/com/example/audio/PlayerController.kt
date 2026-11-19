package com.example.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.domain.model.Track
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatModeState { OFF, ALL, ONE }

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val errorMessage: String? = null
)

@UnstableApi
class PlayerController(
    private val context: Context,
    private val eqProcessor: GraphicEqAudioProcessor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    val spectrumFlow: StateFlow<FloatArray> = eqProcessor.spectrumFlow

    private var trackQueue: List<Track> = emptyList()
    private var pendingPlay: Pair<List<Track>, Int>? = null

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = mc
            attachListener(mc)
            syncStateFromPlayer(mc)
            startProgressLoop()
            pendingPlay?.let { (tracks, idx) ->
                pendingPlay = null
                playTracks(tracks, idx)
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        progressJob?.cancel()
        progressJob = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        trackQueue = tracks
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        val mc = controller ?: run {
            pendingPlay = tracks to safeIdx
            connect()
            return
        }
        pendingPlay = null

        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(Uri.parse(track.uriString))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .build()
        }

        mc.setMediaItems(mediaItems, safeIdx, 0L)
        mc.prepare()
        mc.play()
        _playbackState.update {
            it.copy(
                queue = tracks,
                currentIndex = safeIdx,
                currentTrack = tracks[safeIdx],
                durationMs = tracks[safeIdx].durationMs,
                positionMs = 0L,
                errorMessage = null
            )
        }
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) {
            mc.pause()
        } else {
            if (mc.playbackState == Player.STATE_IDLE && trackQueue.isNotEmpty()) {
                playTracks(trackQueue, _playbackState.value.currentIndex.coerceAtLeast(0))
            } else {
                mc.play()
            }
        }
    }

    fun play() {
        val mc = controller ?: return
        if (mc.playbackState == Player.STATE_IDLE && trackQueue.isNotEmpty()) {
            playTracks(trackQueue, _playbackState.value.currentIndex.coerceAtLeast(0))
        } else {
            mc.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun stopPlayback() {
        val mc = controller ?: return
        mc.pause()
        mc.seekTo(0L)
        eqProcessor.clearSpectrum()
        _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
    }

    fun seekTo(positionMs: Long) {
        val mc = controller ?: return
        mc.seekTo(positionMs.coerceAtLeast(0L))
        _playbackState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun skipNext() {
        val mc = controller ?: return
        if (mc.hasNextMediaItem()) {
            mc.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        val mc = controller ?: return
        if (mc.currentPosition > 3_000L) {
            mc.seekTo(0L)
        } else if (mc.hasPreviousMediaItem()) {
            mc.seekToPreviousMediaItem()
        }
    }

    fun jumpToQueueIndex(index: Int) {
        val mc = controller ?: return
        if (index in trackQueue.indices) {
            mc.seekToDefaultPosition(index)
            mc.play()
        }
    }

    fun toggleShuffle() {
        val mc = controller ?: return
        val next = !mc.shuffleModeEnabled
        mc.shuffleModeEnabled = next
        _playbackState.update { it.copy(shuffleEnabled = next) }
    }

    fun cycleRepeatMode() {
        val mc = controller ?: return
        val next = when (_playbackState.value.repeatMode) {
            RepeatModeState.OFF -> RepeatModeState.ALL
            RepeatModeState.ALL -> RepeatModeState.ONE
            RepeatModeState.ONE -> RepeatModeState.OFF
        }
        mc.repeatMode = when (next) {
            RepeatModeState.OFF -> Player.REPEAT_MODE_OFF
            RepeatModeState.ALL -> Player.REPEAT_MODE_ALL
            RepeatModeState.ONE -> Player.REPEAT_MODE_ONE
        }
        _playbackState.update { it.copy(repeatMode = next) }
    }

    fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    fun syncTrackMetadataUpdates(updatedTracks: List<Track>) {
        if (trackQueue.isEmpty()) return
        val byId = updatedTracks.associateBy { it.id }
        val refreshedQueue = trackQueue.map { byId[it.id] ?: it }
        trackQueue = refreshedQueue
        val idx = _playbackState.value.currentIndex
        _playbackState.update {
            it.copy(
                queue = refreshedQueue,
                currentTrack = refreshedQueue.getOrNull(idx) ?: it.currentTrack
            )
        }
    }

    private fun attachListener(mc: MediaController) {
        mc.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) eqProcessor.clearSpectrum()
                syncStateFromPlayer(mc)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncStateFromPlayer(mc)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    eqProcessor.clearSpectrum()
                }
                syncStateFromPlayer(mc)
            }

            override fun onPlayerError(error: PlaybackException) {
                eqProcessor.clearSpectrum()
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        errorMessage = "Error de reproducción: ${error.localizedMessage ?: error.errorCodeName}"
                    )
                }
            }
        })
    }

    private fun syncStateFromPlayer(mc: MediaController) {
        val idx = mc.currentMediaItemIndex
        val track = trackQueue.getOrNull(idx)
        val playerDur = mc.duration.takeIf { it > 0L } ?: (track?.durationMs ?: 0L)
        val repeatState = when (mc.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatModeState.ALL
            Player.REPEAT_MODE_ONE -> RepeatModeState.ONE
            else -> RepeatModeState.OFF
        }
        _playbackState.update {
            it.copy(
                currentTrack = track ?: it.currentTrack,
                queue = trackQueue,
                currentIndex = idx,
                isPlaying = mc.isPlaying,
                positionMs = mc.currentPosition.coerceAtLeast(0L),
                durationMs = playerDur,
                shuffleEnabled = mc.shuffleModeEnabled,
                repeatMode = repeatState
            )
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                controller?.let { mc ->
                    if (mc.isPlaying) {
                        val dur = mc.duration.takeIf { it > 0L }
                            ?: _playbackState.value.currentTrack?.durationMs
                            ?: 0L
                        _playbackState.update {
                            it.copy(
                                positionMs = mc.currentPosition.coerceAtLeast(0L),
                                durationMs = dur
                            )
                        }
                    }
                }
                delay(200L)
            }
        }
    }
}
