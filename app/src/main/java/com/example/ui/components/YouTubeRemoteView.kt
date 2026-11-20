package com.example.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.WinampColors
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun YouTubeRemotePlayerBox(
    playlistId: String,
    initialSeekSeconds: Float = 0f,
    onBindPlayer: (YouTubePlayer) -> Unit,
    onUnbindPlayer: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    onProgress: (Float, Float?) -> Unit,
    onVideoChanged: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(WinampColors.LcdBlack)
            .winampBevel(raised = false, strokeWidth = 1.4.dp)
            .testTag("youtube_remote_deck")
    ) {
        key(playlistId) {
            var playerViewRef: YouTubePlayerView? = null
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    YouTubePlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        enableAutomaticInitialization = false
                        // Keep playing continuously when app is minimized or screen turns off
                        enableBackgroundPlayback(true)

                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(1)
                            .listType("playlist")
                            .list(playlistId)
                            .build()

                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                onBindPlayer(youTubePlayer)
                                if (initialSeekSeconds > 1.5f) {
                                    youTubePlayer.seekTo(initialSeekSeconds)
                                }
                            }

                            override fun onStateChange(
                                youTubePlayer: YouTubePlayer,
                                state: PlayerConstants.PlayerState
                            ) {
                                when (state) {
                                    PlayerConstants.PlayerState.PLAYING -> onPlayingChanged(true)
                                    PlayerConstants.PlayerState.PAUSED,
                                    PlayerConstants.PlayerState.ENDED -> onPlayingChanged(false)
                                    else -> Unit
                                }
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                onProgress(second, null)
                            }

                            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                                onProgress(0f, duration)
                            }

                            override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) {
                                onVideoChanged(videoId)
                            }

                            override fun onError(
                                youTubePlayer: YouTubePlayer,
                                error: PlayerConstants.PlayerError
                            ) {
                                onError("Error de reproducción YouTube: ${error.name}")
                            }
                        }, options)
                        playerViewRef = this
                    }
                }
            )

            DisposableEffect(playlistId) {
                onDispose {
                    onUnbindPlayer()
                    playerViewRef?.release()
                }
            }
        }
    }
}
