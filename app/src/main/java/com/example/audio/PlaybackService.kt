package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.SonoraApplication
import com.example.data.repository.AlacUriRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class YouTubeBgCommand {
    TOGGLE_PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    STOP
}

object YouTubeBgBridge {
    private val _commands = MutableSharedFlow<YouTubeBgCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<YouTubeBgCommand> = _commands.asSharedFlow()

    fun dispatch(command: YouTubeBgCommand) {
        _commands.tryEmit(command)
    }
}

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var ytWakeLock: PowerManager.WakeLock? = null
    private var ytWifiLock: WifiManager.WifiLock? = null
    private var isYouTubeForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(MEDIA_NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.notif_channel_playback)
                .build()
        )

        val app = application as SonoraApplication
        val eqProcessor = app.graphicEqAudioProcessor
        val systemEq = app.systemAudioEffectEq
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
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Never allow hardware audio offload to bypass GraphicEqAudioProcessor
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                systemEq.bindSession(
                    audioSessionId = audioSessionId,
                    shouldActivate = !eqProcessor.isHandlingActiveStream
                )
            }
        })

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_YOUTUBE_SYNC -> {
                val title = intent.getStringExtra(EXTRA_YT_TITLE).orEmpty().ifBlank { "YouTube Remote" }
                val artist = intent.getStringExtra(EXTRA_YT_ARTIST).orEmpty().ifBlank { "AndAmp Stream" }
                val isPlaying = intent.getBooleanExtra(EXTRA_YT_PLAYING, true)
                startOrUpdateYouTubeForeground(title, artist, isPlaying)
                return START_STICKY
            }

            ACTION_YOUTUBE_CONTROL -> {
                val cmdName = intent.getStringExtra(EXTRA_YT_CMD)
                val cmd = runCatching { cmdName?.let { YouTubeBgCommand.valueOf(it) } }.getOrNull()
                if (cmd != null) {
                    YouTubeBgBridge.dispatch(cmd)
                }
                return START_STICKY
            }

            ACTION_YOUTUBE_STOP -> {
                stopYouTubeForeground()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startOrUpdateYouTubeForeground(title: String, artist: String, isPlaying: Boolean) {
        ensurePlaybackNotificationChannel()
        acquireYouTubeLocks(isPlaying)
        val notification = buildYouTubeNotification(title, artist, isPlaying)
        if (!isYouTubeForegroundActive) {
            isYouTubeForegroundActive = true
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, YOUTUBE_NOTIFICATION_ID, notification, fgsType)
        } else {
            runCatching {
                NotificationManagerCompat.from(this).notify(YOUTUBE_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopYouTubeForeground() {
        releaseYouTubeLocks()
        if (isYouTubeForegroundActive) {
            isYouTubeForegroundActive = false
            val exoPlaying = mediaSession?.player?.playWhenReady == true
            if (!exoPlaying) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } else {
                runCatching { NotificationManagerCompat.from(this).cancel(YOUTUBE_NOTIFICATION_ID) }
            }
        }
    }

    private fun buildYouTubeNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = controlPendingIntent(11, YouTubeBgCommand.PREVIOUS)
        val playPausePending = controlPendingIntent(12, YouTubeBgCommand.TOGGLE_PLAY_PAUSE)
        val nextPending = controlPendingIntent(13, YouTubeBgCommand.NEXT)

        val toggleIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = if (isPlaying) "Pausa" else "Reproducir"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_andamp)
            .setContentTitle(title)
            .setContentText("$artist · YouTube Remote")
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPending)
            .addAction(toggleIcon, toggleLabel, playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPending)
            .build()
    }

    private fun controlPendingIntent(reqCode: Int, cmd: YouTubeBgCommand): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_YOUTUBE_CONTROL
            putExtra(EXTRA_YT_CMD, cmd.name)
        }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun acquireYouTubeLocks(active: Boolean) {
        if (!active) {
            releaseYouTubeLocks()
            return
        }
        if (ytWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ytWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndAmp:YouTubeBgWakeLock")
                ?.apply { setReferenceCounted(false) }
        }
        runCatching {
            if (ytWakeLock?.isHeld == false) ytWakeLock?.acquire(60 * 60 * 1000L)
        }

        if (ytWifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            ytWifiLock = wm?.createWifiLock(mode, "AndAmp:YouTubeBgWifiLock")
                ?.apply { setReferenceCounted(false) }
        }
        runCatching {
            if (ytWifiLock?.isHeld == false) ytWifiLock?.acquire()
        }
    }

    private fun releaseYouTubeLocks() {
        runCatching {
            if (ytWakeLock?.isHeld == true) ytWakeLock?.release()
        }
        runCatching {
            if (ytWifiLock?.isHeld == true) ytWifiLock?.release()
        }
    }

    private fun ensurePlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_playback),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_playback_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val exoPlaying = player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!exoPlaying && !isYouTubeForegroundActive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseYouTubeLocks()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "andamp_playback"
        private const val MEDIA_NOTIFICATION_ID = 1001
        private const val YOUTUBE_NOTIFICATION_ID = 1002

        private const val ACTION_YOUTUBE_SYNC = "com.example.audio.ACTION_YOUTUBE_SYNC"
        private const val ACTION_YOUTUBE_CONTROL = "com.example.audio.ACTION_YOUTUBE_CONTROL"
        private const val ACTION_YOUTUBE_STOP = "com.example.audio.ACTION_YOUTUBE_STOP"

        private const val EXTRA_YT_TITLE = "extra_yt_title"
        private const val EXTRA_YT_ARTIST = "extra_yt_artist"
        private const val EXTRA_YT_PLAYING = "extra_yt_playing"
        private const val EXTRA_YT_CMD = "extra_yt_cmd"

        fun syncYouTubeBackground(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean
        ) {
            runCatching {
                val intent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_YOUTUBE_SYNC
                    putExtra(EXTRA_YT_TITLE, title)
                    putExtra(EXTRA_YT_ARTIST, artist)
                    putExtra(EXTRA_YT_PLAYING, isPlaying)
                }
                if (isPlaying) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopYouTubeBackground(context: Context) {
            runCatching {
                val intent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_YOUTUBE_STOP
                }
                context.startService(intent)
            }
        }
    }
}
