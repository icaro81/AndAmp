package com.example.domain.model

enum class AudioFormatBadge(val label: String, val isHiResCapable: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    DSD("DSD", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    OPUS("OPUS", false),
    OGG("OGG", false),
    AAC("AAC", false),
    MP3("MP3", false),
    UNKNOWN("AUDIO", false)
}

data class Track(
    val id: Long = 0L,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: AudioFormatBadge,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val channels: Int = 2,
    val fileSizeBytes: Long = 0L,
    val artIndex: Int = 0,
    val isFavorite: Boolean = false,
    val isReferenceTrack: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    val isHiRes: Boolean
        get() = format == AudioFormatBadge.DSD ||
            sampleRateHz > 48_000 ||
            bitDepth > 16

    val sampleRateDisplay: String
        get() = if (format == AudioFormatBadge.DSD) {
            String.format(java.util.Locale.US, "%.2f MHz", sampleRateHz / 1_000_000.0)
        } else if (sampleRateHz % 1000 == 0) {
            "${sampleRateHz / 1000} kHz"
        } else {
            String.format(java.util.Locale.US, "%.1f kHz", sampleRateHz / 1000.0)
        }

    val bitDepthDisplay: String
        get() = if (format == AudioFormatBadge.DSD) "1-bit" else if (bitDepth > 0) "${bitDepth}-bit" else "16-bit"

    val technicalSpecLabel: String
        get() = when (format) {
            AudioFormatBadge.DSD -> {
                val mult = when {
                    sampleRateHz >= 11_000_000 -> "DSD256"
                    sampleRateHz >= 5_000_000 -> "DSD128"
                    else -> "DSD64"
                }
                "$mult · 1-bit · $sampleRateDisplay"
            }
            else -> "${format.label} · $bitDepthDisplay · $sampleRateDisplay"
        }

    val shortBadge: String
        get() = when {
            format == AudioFormatBadge.DSD && sampleRateHz >= 11_000_000 -> "DSD256"
            format == AudioFormatBadge.DSD && sampleRateHz >= 5_000_000 -> "DSD128"
            format == AudioFormatBadge.DSD -> "DSD64"
            isHiRes -> "${format.label} HR"
            else -> format.label
        }
}

data class Playlist(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val tracks: List<Track> = emptyList()
) {
    val trackCount: Int get() = tracks.size
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
}

data class EqPreset(
    val id: Long = 0L,
    val name: String,
    val bandsDb: List<Float>,
    val preampDb: Float = 0f,
    val isBuiltIn: Boolean = false
)

object EqConstants {
    val BAND_FREQUENCIES_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val MIN_PREAMP_DB = -12f
    const val MAX_PREAMP_DB = 6f

    val BUILT_IN_PRESETS = listOf(
        EqPreset(-1L, "Plano (Referencia)", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f, true),
        EqPreset(-2L, "Audiófilo Calidez", listOf(2.5f, 2.0f, 1.0f, 0.5f, 0f, 0f, 1.0f, 2.0f, 2.5f, 1.5f), -1.5f, true),
        EqPreset(-3L, "Graves Profundos", listOf(6.5f, 5.5f, 4.0f, 2.0f, 0f, -0.5f, 0f, 1.0f, 1.5f, 1.0f), -3.5f, true),
        EqPreset(-4L, "Jazz Acústico", listOf(2.0f, 1.5f, 0.5f, 1.5f, -0.5f, 0f, 1.5f, 2.5f, 2.0f, 1.5f), -1.5f, true),
        EqPreset(-5L, "Clásica Sinfónica", listOf(3.0f, 2.0f, 0.5f, 0f, -0.5f, 0f, 1.0f, 2.5f, 3.5f, 3.0f), -2.0f, true),
        EqPreset(-6L, "Rock Dinámico", listOf(4.5f, 3.5f, 2.0f, 0f, -1.5f, -0.5f, 1.5f, 3.0f, 4.0f, 3.5f), -2.5f, true),
        EqPreset(-7L, "Electrónica Hi-Res", listOf(5.0f, 4.0f, 1.5f, -1.0f, -1.5f, 0.5f, 2.0f, 3.5f, 4.5f, 4.0f), -3.0f, true),
        EqPreset(-8L, "Vocal Íntima", listOf(-2.0f, -1.5f, 0f, 1.5f, 3.5f, 4.0f, 3.0f, 1.5f, 0.5f, -1.0f), -2.0f, true)
    )
}

data class SmbServerProfile(
    val id: Long = 0L,
    val alias: String,
    val host: String,
    val port: Int = 445,
    val defaultShare: String = "",
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class SmbBrowserEntry(
    val name: String,
    val relativePath: String,
    val shareName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val formatHint: AudioFormatBadge? = null
)

data class YouTubePlaylistSummary(
    val playlistId: String,
    val title: String,
    val channelTitle: String,
    val videoCountText: String = "",
    val thumbnailUrl: String = ""
)

enum class PlaybackSourceMode {
    MEDIA3_AUDIO,
    YOUTUBE_REMOTE
}

data class DiscoveredSmbHost(
    val host: String,
    val displayName: String,
    val port: Int = 445,
    val source: String = "LAN"
)

data class YouTubePlaybackState(
    val activePlaylist: YouTubePlaylistSummary? = null,
    val isPlaying: Boolean = false,
    val currentSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val currentVideoId: String = "",
    val privacyEnhancedMode: Boolean = false,
    val hasAutoRetriedPrivacy: Boolean = false,
    val reloadNonce: Int = 0,
    val errorMessage: String? = null
)

