package com.example.data.repository

import android.net.Uri
import com.example.data.local.PlaylistDao
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.SmbServerDao
import com.example.data.local.SmbServerEntity
import com.example.data.local.TrackDao
import com.example.data.local.TrackEntity
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.SmbBrowserEntry
import com.example.domain.model.SmbServerProfile
import com.example.domain.model.Track
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbRepository(
    private val smbServerDao: SmbServerDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao
) {
    private val supportedAudioExts = setOf(
        "flac", "m4a", "alac", "dsf", "dff", "wav", "aif", "aiff", "mp3", "ogg", "oga", "opus", "aac"
    )

    private val commonShareProbes = listOf(
        "Music", "music", "Media", "media", "Audio", "audio", "Public", "public", "Shared", "NAS", "home"
    )

    val allServersFlow: Flow<List<SmbServerProfile>> = smbServerDao.observeAllServers().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun saveServer(profile: SmbServerProfile): Long = withContext(Dispatchers.IO) {
        smbServerDao.upsertServer(SmbServerEntity.fromDomain(profile))
    }

    suspend fun deleteServer(serverId: Long) = withContext(Dispatchers.IO) {
        smbServerDao.deleteServer(serverId)
    }

    suspend fun getServerById(serverId: Long): SmbServerProfile? = withContext(Dispatchers.IO) {
        smbServerDao.getById(serverId)?.toDomain()
    }

    fun buildClient(): SMBClient {
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(10, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    fun buildAuthContext(server: SmbServerProfile): AuthenticationContext {
        return if (server.isGuest || server.username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(server.username.trim(), server.password.toCharArray(), "")
        }
    }

    suspend fun listDirectory(
        server: SmbServerProfile,
        shareName: String,
        relativePath: String
    ): Result<Pair<String, List<SmbBrowserEntry>>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildClient()
            client.use { smbClient ->
                val connection = smbClient.connect(server.host.trim(), server.port)
                connection.use { conn ->
                    val session = conn.authenticate(buildAuthContext(server))
                    session.use { sess ->
                        val effectiveShare = resolveEffectiveShare(sess, shareName, server.defaultShare)
                        val share = sess.connectShare(effectiveShare) as? DiskShare
                            ?: error("El recurso \"$effectiveShare\" no es una carpeta compartida SMB")
                        share.use { diskShare ->
                            val cleanPath = relativePath.replace('/', '\\').trim('\\')
                            val rawList = diskShare.list(cleanPath)
                            val entries = rawList.mapNotNull { info ->
                                val name = info.fileName ?: return@mapNotNull null
                                if (name == "." || name == "..") return@mapNotNull null
                                val isDir = EnumWithValue.EnumUtils.isSet(
                                    info.fileAttributes,
                                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                                )
                                val nextRel = if (cleanPath.isEmpty()) name else "$cleanPath\\$name"
                                if (isDir) {
                                    SmbBrowserEntry(
                                        name = name,
                                        relativePath = nextRel,
                                        shareName = effectiveShare,
                                        isDirectory = true,
                                        sizeBytes = 0L,
                                        formatHint = null
                                    )
                                } else {
                                    val ext = name.substringAfterLast('.', "").lowercase()
                                    if (ext !in supportedAudioExts) return@mapNotNull null
                                    SmbBrowserEntry(
                                        name = name,
                                        relativePath = nextRel,
                                        shareName = effectiveShare,
                                        isDirectory = false,
                                        sizeBytes = info.endOfFile,
                                        formatHint = guessFormat(ext)
                                    )
                                }
                            }.sortedWith(
                                compareByDescending<SmbBrowserEntry> { it.isDirectory }
                                    .thenBy { it.name.lowercase() }
                            )
                            effectiveShare to entries
                        }
                    }
                }
            }
        }
    }

    private fun resolveEffectiveShare(session: Session, requestedShare: String, defaultShare: String): String {
        val candidate = requestedShare.trim().ifBlank { defaultShare.trim() }
        if (candidate.isNotBlank()) return candidate

        for (probe in commonShareProbes) {
            val ok = runCatching {
                val share = session.connectShare(probe)
                share.close()
                true
            }.getOrDefault(false)
            if (ok) return probe
        }
        error("Indica el nombre de la carpeta compartida (Share) en la configuración del servidor")
    }

    suspend fun importSmbEntries(
        server: SmbServerProfile,
        entries: List<SmbBrowserEntry>,
        targetPlaylistId: Long? = null
    ): Int = withContext(Dispatchers.IO) {
        val audioEntries = entries.filter { !it.isDirectory }
        if (audioEntries.isEmpty()) return@withContext 0

        var addedCount = 0
        val existingCount = trackDao.getTrackCount()
        val client = buildClient()
        client.use { smbClient ->
            val conn = smbClient.connect(server.host.trim(), server.port)
            conn.use { connection ->
                val sess = connection.authenticate(buildAuthContext(server))
                sess.use { session ->
                    val shareCache = mutableMapOf<String, DiskShare>()
                    try {
                        audioEntries.forEachIndexed { idx, entry ->
                            val uriStr = buildSmbUri(server.id, entry.shareName, entry.relativePath, entry.name)
                            if (trackDao.getByUri(uriStr) == null) {
                                val diskShare = shareCache.getOrPut(entry.shareName) {
                                    session.connectShare(entry.shareName) as DiskShare
                                }
                                val track = extractSmbTrack(
                                    diskShare = diskShare,
                                    server = server,
                                    entry = entry,
                                    uriString = uriStr,
                                    artHint = (existingCount + idx) % 3
                                )
                                if (track.format == AudioFormatBadge.ALAC) {
                                    AlacUriRegistry.markAlac(track.uriString)
                                }
                                val id = trackDao.upsertTrack(TrackEntity.fromDomain(track))
                                if (targetPlaylistId != null && id > 0L) {
                                    val pos = playlistDao.getMaxPosition(targetPlaylistId) + 1
                                    playlistDao.addTrackToPlaylist(
                                        PlaylistTrackCrossRef(targetPlaylistId, id, pos)
                                    )
                                }
                                addedCount++
                            }
                        }
                    } finally {
                        shareCache.values.forEach { runCatching { it.close() } }
                    }
                }
            }
        }
        addedCount
    }

    private fun extractSmbTrack(
        diskShare: DiskShare,
        server: SmbServerProfile,
        entry: SmbBrowserEntry,
        uriString: String,
        artHint: Int
    ): Track {
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        var format = guessFormat(ext)
        var sampleRate = if (format == AudioFormatBadge.DSD) 2_822_400 else 44_100
        var bitDepth = if (format == AudioFormatBadge.DSD) 1 else 16
        var channels = 2
        var durationMs = 0L

        runCatching {
            val smbFile = diskShare.openFile(
                entry.relativePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            smbFile.use { f ->
                val head = ByteArray(16 * 1024)
                val bytesRead = f.read(head, 0L)
                if (bytesRead >= 42) {
                    val magic = String(head, 0, 4, Charsets.US_ASCII)
                    when {
                        magic == "fLaC" -> {
                            format = AudioFormatBadge.FLAC
                            val b10 = head[18].toInt() and 0xFF
                            val b11 = head[19].toInt() and 0xFF
                            val b12 = head[20].toInt() and 0xFF
                            val b13 = head[21].toInt() and 0xFF
                            sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                            channels = ((b12 ushr 1) and 0x07) + 1
                            bitDepth = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                            val totalSamples = ((b13.toLong() and 0x0F) shl 32) or
                                ((head[22].toLong() and 0xFF) shl 24) or
                                ((head[23].toLong() and 0xFF) shl 16) or
                                ((head[24].toLong() and 0xFF) shl 8) or
                                (head[25].toLong() and 0xFF)
                            if (sampleRate > 0) durationMs = (totalSamples * 1000L) / sampleRate
                        }
                        magic == "DSD " && bytesRead >= 80 -> {
                            format = AudioFormatBadge.DSD
                            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                            channels = bb.getInt(28 + 24).coerceIn(1, 2)
                            sampleRate = bb.getInt(28 + 28).coerceAtLeast(2_822_400)
                            val sampleCount = bb.getLong(28 + 36)
                            if (sampleRate > 0) durationMs = (sampleCount * 1000L) / sampleRate
                            bitDepth = 1
                        }
                        String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                            val needle = "alac".toByteArray(Charsets.US_ASCII)
                            val bb = ByteBuffer.wrap(head, 0, bytesRead).order(ByteOrder.BIG_ENDIAN)
                            for (i in 0 until bytesRead - 32) {
                                if (head[i] == needle[0] && head[i + 1] == needle[1] &&
                                    head[i + 2] == needle[2] && head[i + 3] == needle[3]
                                ) {
                                    val c = i + 8
                                    if (c + 24 <= bytesRead) {
                                        val bd = head[c + 5].toInt() and 0xFF
                                        val ch = head[c + 9].toInt() and 0xFF
                                        val sr = bb.getInt(c + 20)
                                        if (bd in listOf(16, 20, 24, 32) && ch in 1..8 && sr in 8000..384_000) {
                                            format = AudioFormatBadge.ALAC
                                            bitDepth = bd
                                            channels = ch
                                            sampleRate = sr
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        magic == "RIFF" && bytesRead >= 44 -> {
                            format = AudioFormatBadge.WAV
                            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                            channels = bb.getShort(22).toInt().coerceIn(1, 8)
                            sampleRate = bb.getInt(24).coerceAtLeast(44_100)
                            bitDepth = bb.getShort(34).toInt().coerceIn(8, 32)
                            val byteRate = (sampleRate * channels * (bitDepth / 8)).coerceAtLeast(1)
                            if (entry.sizeBytes > 44L) {
                                durationMs = ((entry.sizeBytes - 44L) * 1000L) / byteRate
                            }
                        }
                    }
                }
            }
        }

        val bitrateKbps = if (durationMs > 0L && entry.sizeBytes > 0L) {
            ((entry.sizeBytes * 8L) / durationMs).toInt()
        } else 0

        val baseTitle = entry.name.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { entry.name }

        return Track(
            uriString = uriString,
            title = baseTitle,
            artist = server.alias,
            album = "NAS · ${entry.shareName}",
            durationMs = durationMs.coerceAtLeast(0L),
            format = format,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels,
            fileSizeBytes = entry.sizeBytes,
            artIndex = artHint,
            isReferenceTrack = false
        )
    }

    fun openSmbConnectionHandle(uri: Uri): SmbOpenHandle? {
        if (uri.scheme != "smb") return null
        val sid = uri.getQueryParameter("sid")?.toLongOrNull() ?: return null
        val shareName = uri.getQueryParameter("share") ?: return null
        val relPath = uri.getQueryParameter("path") ?: return null
        val entity = kotlinx.coroutines.runBlocking(Dispatchers.IO) { smbServerDao.getById(sid) }
            ?: return null
        val server = entity.toDomain()

        val client = buildClient()
        val conn = client.connect(server.host.trim(), server.port)
        val sess = conn.authenticate(buildAuthContext(server))
        val share = sess.connectShare(shareName) as DiskShare
        val file = share.openFile(
            relPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        val fileSize = file.fileInformation.standardInformation.endOfFile
        return SmbOpenHandle(client, conn, sess, share, file, fileSize)
    }

    private fun guessFormat(ext: String): AudioFormatBadge = when (ext) {
        "flac" -> AudioFormatBadge.FLAC
        "alac" -> AudioFormatBadge.ALAC
        "dsf", "dff" -> AudioFormatBadge.DSD
        "wav" -> AudioFormatBadge.WAV
        "aif", "aiff" -> AudioFormatBadge.AIFF
        "opus" -> AudioFormatBadge.OPUS
        "ogg", "oga" -> AudioFormatBadge.OGG
        "aac" -> AudioFormatBadge.AAC
        "m4a" -> AudioFormatBadge.AAC
        "mp3" -> AudioFormatBadge.MP3
        else -> AudioFormatBadge.UNKNOWN
    }

    companion object {
        fun buildSmbUri(serverId: Long, shareName: String, relativePath: String, fileName: String): String {
            return Uri.Builder()
                .scheme("smb")
                .authority("nas-$serverId")
                .appendPath(fileName)
                .appendQueryParameter("sid", serverId.toString())
                .appendQueryParameter("share", shareName)
                .appendQueryParameter("path", relativePath)
                .build()
                .toString()
        }
    }
}

class SmbOpenHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    val file: SmbFile,
    val fileSize: Long
) {
    fun closeQuietly() {
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
