package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY isReferenceTrack ASC, addedAt DESC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Query("SELECT * FROM tracks WHERE uriString = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<TrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, favorite: Boolean)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)
}

@Dao
interface PlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistWithOrderedTracks>>

    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getPlaylistsOnce(): List<PlaylistWithOrderedTracks>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_track_cross_ref ref ON t.id = ref.trackId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.position ASC
        """
    )
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, description = :description WHERE id = :playlistId")
    suspend fun updatePlaylist(playlistId: Long, name: String, description: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(ref: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("UPDATE playlist_track_cross_ref SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updateTrackPosition(playlistId: Long, trackId: Long, position: Int)

    @Transaction
    suspend fun reorderPlaylistTracks(playlistId: Long, orderedTrackIds: List<Long>) {
        orderedTrackIds.forEachIndexed { index, trackId ->
            updateTrackPosition(playlistId, trackId, index)
        }
    }
}

@Dao
interface EqPresetDao {
    @Query("SELECT * FROM eq_presets ORDER BY createdAt DESC")
    fun observeCustomPresets(): Flow<List<EqPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: EqPresetEntity): Long

    @Query("DELETE FROM eq_presets WHERE id = :presetId")
    suspend fun deletePreset(presetId: Long)
}

@Dao
interface SmbServerDao {
    @Query("SELECT * FROM smb_servers ORDER BY createdAt DESC")
    fun observeAllServers(): Flow<List<SmbServerEntity>>

    @Query("SELECT * FROM smb_servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmbServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: SmbServerEntity): Long

    @Query("DELETE FROM smb_servers WHERE id = :id")
    suspend fun deleteServer(id: Long)
}

@Dao
interface YouTubePlaylistDao {
    @Query("SELECT * FROM youtube_playlists ORDER BY savedAt DESC")
    fun observeRecentPlaylists(): Flow<List<SavedYouTubePlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: SavedYouTubePlaylistEntity)

    @Query("DELETE FROM youtube_playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}

