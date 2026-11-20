package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.data.local.EqPresetDao
import com.example.data.local.EqPresetEntity
import com.example.data.local.PlaylistDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.TrackDao
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.EqPreset
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of URIs known to contain Apple Lossless (ALAC) streams so our custom AlacExtractor
 * can claim them cleanly even when moov is placed after mdat.
 */
object AlacUriRegistry {
    private val alacUris = ConcurrentHashMap.newKeySet<String>()

    fun markAlac(uriString: String) {
        alacUris.add(uriString)
    }

    fun isMarkedAlac(uriString: String?): Boolean =
        uriString != null && alacUris.contains(uriString)
}

class LibraryRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val eqPresetDao: EqPresetDao
) {
    private val supportedExtensions = setOf(
        "flac", "m4a", "alac", "dsf", "dff", "wav", "aif", "aiff", "mp3", "ogg", "oga", "opus", "aac"
    )

    val allTracksFlow: Flow<List<Track>> = trackDao.observeAllTracks().map { list ->
        list.map { entity ->
            val domain = entity.toDomain()
            if (domain.format == AudioFormatBadge.ALAC) {
                AlacUriRegistry.markAlac(domain.uriString)
            }
            domain
        }
    }

    val allPlaylistsFlow: Flow<List<Playlist>> = playlistDao.observeAllPlaylists().map { list ->
        list.map { rel ->
            Playlist(
                id = rel.playlist.id,
                name = rel.playlist.name,
                description = rel.playlist.description,
                createdAt = rel.playlist.createdAt,
                tracks = rel.tracks.map { it.toDomain() }
            )
        }
    }

    val customEqPresetsFlow: Flow<List<EqPreset>> = eqPresetDao.observeCustomPresets().map { list ->
        list.map { it.toDomain() }
    }

    fun observePlaylistDetail(playlistId: Long): Flow<Playlist?> =
        combine(
            playlistDao.observePlaylist(playlistId),
            playlistDao.observeTracksForPlaylist(playlistId)
        ) { entity, tracks ->
            entity?.let {
                Playlist(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    createdAt = it.createdAt,
                    tracks = tracks.map { t -> t.toDomain() }
                )
            }
        }

    suspend fun ensureReferenceLibrarySeeded() = withContext(Dispatchers.IO) {
        val sampleDir = File(context.filesDir, "reference_samples").apply { mkdirs() }
        val introAsset = "andamp_droid_intro.wav"
        val introDest = File(sampleDir, introAsset)
        val introUri = Uri.fromFile(introDest).toString()

        val legacySampleNames = listOf(
            "nocturne_96k_24b.flac",
            "amber_48k_alac.m4a",
            "prism_dsd64.dsf"
        )
        val cleanupFlag = File(sampleDir, "andamp_only_samples_v1.flag")
        if (!cleanupFlag.exists()) {
            legacySampleNames.forEach { legacyName ->
                val legacyFile = File(sampleDir, legacyName)
                trackDao.deleteByUri(Uri.fromFile(legacyFile).toString())
                if (legacyFile.exists()) {
                    runCatching { legacyFile.delete() }
                }
            }
            cleanupFlag.writeText("v1")
        }

        val introFlag = File(sampleDir, "andamp_droid_intro_voice_v2.flag")
        if (!introDest.exists() || !introFlag.exists()) {
            runCatching {
                context.assets.open("samples/$introAsset").use { input ->
                    introDest.outputStream().use { output -> input.copyTo(output) }
                }
                introFlag.writeText("v2")
            }
        }

        val existingTrackCount = trackDao.getTrackCount()
        if ((existingTrackCount == 0 || trackDao.getByUri(introUri) == null) && introDest.exists()) {
            val extracted = AudioMetadataExtractor.extractTrackMetadata(
                context = context,
                uri = Uri.fromFile(introDest),
                artIndexHint = 0,
                isReference = true
            )?.copy(
                title = "Droid Whippin' Intro",
                artist = "AndAmp Droid",
                album = "Winamp Droid Intro (7s)",
                durationMs = 7_000L
            )
            if (extracted != null) {
                trackDao.upsertTrack(com.example.data.local.TrackEntity.fromDomain(extracted))
            }
        }
    }

    suspend fun importDocumentUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val existingCount = trackDao.getTrackCount()
        uris.forEachIndexed { index, uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (trackDao.getByUri(uri.toString()) == null) {
                val track = AudioMetadataExtractor.extractTrackMetadata(
                    context = context,
                    uri = uri,
                    artIndexHint = (existingCount + index) % 3,
                    isReference = false
                )
                if (track != null) {
                    if (track.format == AudioFormatBadge.ALAC) {
                        AlacUriRegistry.markAlac(track.uriString)
                    }
                    trackDao.upsertTrack(com.example.data.local.TrackEntity.fromDomain(track))
                    count++
                }
            }
        }
        count
    }

    suspend fun importFolderTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val discovered = mutableListOf<Uri>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        scanDirectoryRecursive(treeUri, rootDocId, discovered, depth = 0)
        importDocumentUris(discovered)
    }

    private fun scanDirectoryRecursive(
        treeUri: Uri,
        parentDocId: String,
        out: MutableList<Uri>,
        depth: Int
    ) {
        if (depth > 4 || out.size > 400) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: ""
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDirectoryRecursive(treeUri, docId, out, depth + 1)
                    } else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (mime.startsWith("audio/") || ext in supportedExtensions) {
                            out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                        }
                    }
                }
            }
        }
    }

    suspend fun toggleFavorite(track: Track) {
        trackDao.setFavorite(track.id, !track.isFavorite)
    }

    suspend fun deleteTrack(trackId: Long) {
        trackDao.deleteTrack(trackId)
    }

    suspend fun createPlaylist(name: String, description: String = ""): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name.trim(), description = description.trim()))

    suspend fun renamePlaylist(playlistId: Long, name: String, description: String) =
        playlistDao.updatePlaylist(playlistId, name.trim(), description.trim())

    suspend fun deletePlaylist(playlistId: Long) = playlistDao.deletePlaylist(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val nextPos = playlistDao.getMaxPosition(playlistId) + 1
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, nextPos))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)

    suspend fun reorderPlaylistTracks(playlistId: Long, orderedTrackIds: List<Long>) =
        playlistDao.reorderPlaylistTracks(playlistId, orderedTrackIds)

    suspend fun saveCustomEqPreset(name: String, bandsDb: List<Float>, preampDb: Float): Long =
        eqPresetDao.insertPreset(
            EqPresetEntity.fromDomain(
                EqPreset(
                    name = name.trim(),
                    bandsDb = bandsDb,
                    preampDb = preampDb,
                    isBuiltIn = false
                )
            )
        )

    suspend fun deleteCustomEqPreset(presetId: Long) = eqPresetDao.deletePreset(presetId)
}
