package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.EqPreset
import com.example.domain.model.Track

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val formatName: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val channels: Int,
    val fileSizeBytes: Long,
    val artIndex: Int,
    val isFavorite: Boolean = false,
    val isReferenceTrack: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        uriString = uriString,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        format = runCatching { AudioFormatBadge.valueOf(formatName) }.getOrDefault(AudioFormatBadge.UNKNOWN),
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        bitrateKbps = bitrateKbps,
        channels = channels,
        fileSizeBytes = fileSizeBytes,
        artIndex = artIndex,
        isFavorite = isFavorite,
        isReferenceTrack = isReferenceTrack,
        addedAt = addedAt
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            id = track.id,
            uriString = track.uriString,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            formatName = track.format.name,
            sampleRateHz = track.sampleRateHz,
            bitDepth = track.bitDepth,
            bitrateKbps = track.bitrateKbps,
            channels = track.channels,
            fileSizeBytes = track.fileSizeBytes,
            artIndex = track.artIndex,
            isFavorite = track.isFavorite,
            isReferenceTrack = track.isReferenceTrack,
            addedAt = track.addedAt
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("trackId")]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    @ColumnInfo(defaultValue = "0")
    val position: Int
)

data class PlaylistWithOrderedTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
)

@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val bandsSerialized: String,
    val preampDb: Float = 0f,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): EqPreset {
        val gains = bandsSerialized.split(",")
            .mapNotNull { it.toFloatOrNull() }
            .let { if (it.size == 10) it else List(10) { 0f } }
        return EqPreset(
            id = id,
            name = name,
            bandsDb = gains,
            preampDb = preampDb,
            isBuiltIn = false
        )
    }

    companion object {
        fun fromDomain(preset: EqPreset): EqPresetEntity = EqPresetEntity(
            id = if (preset.id > 0L) preset.id else 0L,
            name = preset.name,
            bandsSerialized = preset.bandsDb.joinToString(","),
            preampDb = preset.preampDb
        )
    }
}

@Entity(tableName = "smb_servers")
data class SmbServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val alias: String,
    val host: String,
    val port: Int = 445,
    val defaultShare: String = "",
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = com.example.domain.model.SmbServerProfile(
        id = id,
        alias = alias,
        host = host,
        port = port,
        defaultShare = defaultShare,
        username = username,
        password = password,
        isGuest = isGuest,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(profile: com.example.domain.model.SmbServerProfile) = SmbServerEntity(
            id = profile.id,
            alias = profile.alias,
            host = profile.host,
            port = profile.port,
            defaultShare = profile.defaultShare,
            username = profile.username,
            password = profile.password,
            isGuest = profile.isGuest,
            createdAt = profile.createdAt
        )
    }
}

@Entity(tableName = "youtube_playlists")
data class SavedYouTubePlaylistEntity(
    @PrimaryKey val playlistId: String,
    val title: String,
    val channelTitle: String,
    val videoCountText: String = "",
    val thumbnailUrl: String = "",
    val savedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = com.example.domain.model.YouTubePlaylistSummary(
        playlistId = playlistId,
        title = title,
        channelTitle = channelTitle,
        videoCountText = videoCountText,
        thumbnailUrl = thumbnailUrl
    )

    companion object {
        fun fromDomain(summary: com.example.domain.model.YouTubePlaylistSummary) = SavedYouTubePlaylistEntity(
            playlistId = summary.playlistId,
            title = summary.title,
            channelTitle = summary.channelTitle,
            videoCountText = summary.videoCountText,
            thumbnailUrl = summary.thumbnailUrl
        )
    }
}

