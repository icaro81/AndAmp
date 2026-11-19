package com.example.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.SonoraApplication
import com.example.data.repository.AlacUriRegistry

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as SonoraApplication
        val eqProcessor = app.graphicEqAudioProcessor
        val hasNativeAlac = AudioOutputMonitor.hasNativeAlacDecoder()

        val renderersFactory: RenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eqProcessor))
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val defaultFactory = DefaultExtractorsFactory()
        val extractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                val base = defaultFactory.createExtractors()
                return if (hasNativeAlac) {
                    arrayOf(DsdExtractor(), *base)
                } else {
                    arrayOf(DsdExtractor(), AlacExtractor(forceForM4aHint = false), *base)
                }
            }

            override fun createExtractors(
                uri: Uri,
                responseHeaders: MutableMap<String, MutableList<String>>
            ): Array<Extractor> {
                val base = defaultFactory.createExtractors(uri, responseHeaders)
                val uriStr = uri.toString()
                val isMarkedAlac = AlacUriRegistry.isMarkedAlac(uriStr)
                return when {
                    !hasNativeAlac && isMarkedAlac -> arrayOf(AlacExtractor(forceForM4aHint = true), DsdExtractor(), *base)
                    !hasNativeAlac -> arrayOf(DsdExtractor(), AlacExtractor(forceForM4aHint = false), *base)
                    else -> arrayOf(DsdExtractor(), *base)
                }
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val dataSourceFactory = SmbAwareDataSourceFactory(this, app.smbRepository)
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
