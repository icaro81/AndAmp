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
import com.hierynomus.smbj.share.File as SmbjFile
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile as JcifsSmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.Properties
import java.util.concurrent.TimeUnit

data class SmbDirectoryListing(
    val resolvedShare: String,
    val resolvedPath: String,
    val entries: List<SmbBrowserEntry>,
    val protocolLabel: String
)

class SmbRepository(
    private val smbServerDao: SmbServerDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao
) {
    private val supportedAudioExts = setOf(
        "flac", "m4a", "alac", "dsf", "dff", "wav", "aif", "aiff", "mp3", "ogg", "oga", "opus", "aac"
    )

    private val commonShareProbes = listOf(
        "USB1_1", "usb1_1", "USB1", "usb1", "USB",
        "Music", "music", "Musica", "Música", "MUSICA",
        "Media", "media", "Audio", "audio",
        "Public", "public", "Shared", "Share", "share",
        "NAS", "home", "Downloads", "Volume_1", "sda1", "sdb1"
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

    fun buildClient(multiProtocolNegotiate: Boolean = true): SMBClient {
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(multiProtocolNegotiate)
            .withTimeout(8, TimeUnit.SECONDS)
            .withSoTimeout(12, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    fun buildAuthContexts(server: SmbServerProfile): List<AuthenticationContext> {
        return if (server.isGuest || server.username.isBlank()) {
            listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        } else {
            listOf(AuthenticationContext(server.username.trim(), server.password.toCharArray(), ""))
        }
    }

    private fun buildJcifsBaseContext(smb1First: Boolean): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.useSMB2Negotiation", if (smb1First) "false" else "true")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.signingPreferred", "false")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "12000")
            setProperty("jcifs.smb.client.soTimeout", "12000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
        }
        return BaseContext(PropertyConfiguration(props))
    }

    private fun buildJcifsAuthContexts(server: SmbServerProfile, base: CIFSContext): List<CIFSContext> {
        return if (server.isGuest || server.username.isBlank()) {
            listOf(
                base.withCredentials(NtlmPasswordAuthenticator("", "", "")),
                base.withCredentials(NtlmPasswordAuthenticator(null, "guest", ""))
            )
        } else {
            listOf(
                base.withCredentials(
                    NtlmPasswordAuthenticator(null, server.username.trim(), server.password)
                )
            )
        }
    }

    suspend fun listDirectory(
        server: SmbServerProfile,
        shareName: String,
        relativePath: String
    ): Result<SmbDirectoryListing> = withContext(Dispatchers.IO) {
        val cleanHost = normalizeHost(server.host)
        val (normalizedShare, normalizedRelPath) = splitShareAndSubpath(
            rawShare = shareName.ifBlank { server.defaultShare },
            currentRelPath = relativePath
        )

        // 1) Try native SMB2/SMB3 via smbj first
        val smb2Result = runCatching {
            listDirectorySmb2(server, cleanHost, normalizedShare, normalizedRelPath)
        }
        if (smb2Result.isSuccess) {
            return@withContext smb2Result
        }

        val smb2Error = smb2Result.exceptionOrNull()
        // 2) Automatic SMB 1.0 / CIFS fallback (resolves GPON routers & legacy NAS EOF drops)
        val smb1Result = runCatching {
            listDirectoryJcifsSmb1(server, cleanHost, normalizedShare, normalizedRelPath)
        }
        if (smb1Result.isSuccess) {
            return@withContext smb1Result
        }

        val finalError = smb1Result.exceptionOrNull() ?: smb2Error ?: IOException("Error desconocido SMB")
        Result.failure(IOException(formatFriendlySmbError(finalError, cleanHost, normalizedShare), finalError))
    }

    private fun listDirectorySmb2(
        server: SmbServerProfile,
        cleanHost: String,
        shareName: String,
        relativePath: String
    ): SmbDirectoryListing {
        val client = buildClient(multiProtocolNegotiate = true)
        return client.use { smbClient ->
            val connection = smbClient.connect(cleanHost, server.port)
            connection.use { conn ->
                val sess = authenticateSmb2WithFallback(conn, server)
                sess.use { session ->
                    val effectiveShare = resolveEffectiveShareSmb2(session, shareName)
                    val share = session.connectShare(effectiveShare) as? DiskShare
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
                        SmbDirectoryListing(
                            resolvedShare = effectiveShare,
                            resolvedPath = cleanPath,
                            entries = entries,
                            protocolLabel = "SMB2/3"
                        )
                    }
                }
            }
        }
    }

    private fun authenticateSmb2WithFallback(conn: Connection, server: SmbServerProfile): Session {
        var lastError: Throwable? = null
        for (auth in buildAuthContexts(server)) {
            val attempt = runCatching { conn.authenticate(auth) }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IOException("Fallo autenticando sesión SMB2")
    }

    private fun resolveEffectiveShareSmb2(session: Session, requestedShare: String): String {
        val candidate = requestedShare.trim()
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

    private fun listDirectoryJcifsSmb1(
        server: SmbServerProfile,
        cleanHost: String,
        shareName: String,
        relativePath: String
    ): SmbDirectoryListing {
        val base = buildJcifsBaseContext(smb1First = true)
        var lastError: Throwable? = null

        for (ctx in buildJcifsAuthContexts(server, base)) {
            try {
                val rootPrefix = buildJcifsServerRoot(cleanHost, server.port)
                if (shareName.isBlank()) {
                    val serverRoot = JcifsSmbFile(rootPrefix, ctx)
                    val shares = runCatching { serverRoot.listFiles() }
                        .getOrNull()
                        ?.map { it.name.trimEnd('/') }
                        ?.filter { it.isNotBlank() && !it.endsWith("$") }
                        .orEmpty()

                    if (shares.size == 1) {
                        val singleShare = shares.first()
                        return readJcifsShareDirectory(rootPrefix, ctx, singleShare, "")
                    } else if (shares.isNotEmpty()) {
                        val shareEntries = shares.sortedBy { it.lowercase() }.map { s ->
                            SmbBrowserEntry(
                                name = s,
                                relativePath = "",
                                shareName = s,
                                isDirectory = true,
                                sizeBytes = 0L,
                                formatHint = null
                            )
                        }
                        return SmbDirectoryListing(
                            resolvedShare = "/",
                            resolvedPath = "",
                            entries = shareEntries,
                            protocolLabel = "SMB1/CIFS"
                        )
                    } else {
                        for (probe in commonShareProbes) {
                            val probed = runCatching {
                                readJcifsShareDirectory(rootPrefix, ctx, probe, "")
                            }.getOrNull()
                            if (probed != null) return probed
                        }
                        error("No se detectaron carpetas compartidas accesibles en $cleanHost")
                    }
                } else {
                    return readJcifsShareDirectory(rootPrefix, ctx, shareName, relativePath)
                }
            } catch (authErr: SmbAuthException) {
                lastError = authErr
            } catch (err: Throwable) {
                lastError = err
                if (!server.isGuest && server.username.isNotBlank()) break
            }
        }

        throw lastError ?: IOException("No se pudo listar directorio vía SMB 1.0")
    }

    private fun readJcifsShareDirectory(
        rootPrefix: String,
        ctx: CIFSContext,
        shareName: String,
        relativePath: String
    ): SmbDirectoryListing {
        val cleanShare = shareName.trim('/', '\\')
        val cleanPathBackslash = relativePath.replace('/', '\\').trim('\\')
        val cleanPathForward = cleanPathBackslash.replace('\\', '/')

        var dirFile = JcifsSmbFile("$rootPrefix$cleanShare/", ctx)
        if (cleanPathForward.isNotBlank()) {
            for (seg in cleanPathForward.split('/').filter { it.isNotBlank() }) {
                dirFile = JcifsSmbFile(dirFile, "$seg/")
            }
        }

        val rawFiles = dirFile.listFiles()
        val entries = rawFiles.mapNotNull { item ->
            val rawName = item.name ?: return@mapNotNull null
            val name = rawName.trimEnd('/')
            if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
            val isDir = runCatching { item.isDirectory }.getOrDefault(rawName.endsWith("/"))
            val nextRel = if (cleanPathBackslash.isEmpty()) name else "$cleanPathBackslash\\$name"
            if (isDir) {
                SmbBrowserEntry(
                    name = name,
                    relativePath = nextRel,
                    shareName = cleanShare,
                    isDirectory = true,
                    sizeBytes = 0L,
                    formatHint = null
                )
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in supportedAudioExts) return@mapNotNull null
                val size = runCatching { item.length() }.getOrDefault(0L)
                SmbBrowserEntry(
                    name = name,
                    relativePath = nextRel,
                    shareName = cleanShare,
                    isDirectory = false,
                    sizeBytes = size,
                    formatHint = guessFormat(ext)
                )
            }
        }.sortedWith(
            compareByDescending<SmbBrowserEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )

        return SmbDirectoryListing(
            resolvedShare = cleanShare,
            resolvedPath = cleanPathBackslash,
            entries = entries,
            protocolLabel = "SMB1/CIFS"
        )
    }

    suspend fun importSmbEntries(
        server: SmbServerProfile,
        entries: List<SmbBrowserEntry>,
        targetPlaylistId: Long? = null,
        protocolHint: String = ""
    ): Int = withContext(Dispatchers.IO) {
        val audioEntries = entries.filter { !it.isDirectory }
        if (audioEntries.isEmpty()) return@withContext 0

        val cleanHost = normalizeHost(server.host)
        val useSmb1Directly = protocolHint.contains("SMB1", ignoreCase = true)
        var addedCount = 0
        val existingCount = trackDao.getTrackCount()

        if (!useSmb1Directly) {
            val smb2Attempt = runCatching {
                val client = buildClient(multiProtocolNegotiate = true)
                client.use { smbClient ->
                    val conn = smbClient.connect(cleanHost, server.port)
                    conn.use { connection ->
                        val sess = authenticateSmb2WithFallback(connection, server)
                        sess.use { session ->
                            val shareCache = mutableMapOf<String, DiskShare>()
                            try {
                                audioEntries.forEachIndexed { idx, entry ->
                                    val uriStr = buildSmbUri(server.id, entry.shareName, entry.relativePath, entry.name)
                                    if (trackDao.getByUri(uriStr) == null) {
                                        val diskShare = shareCache.getOrPut(entry.shareName) {
                                            session.connectShare(entry.shareName) as DiskShare
                                        }
                                        val track = extractSmb2Track(
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
            }
            if (smb2Attempt.isSuccess) return@withContext addedCount
        }

        // SMB 1.0 / CIFS import path
        addedCount = importSmb1Entries(
            server = server,
            cleanHost = cleanHost,
            audioEntries = audioEntries,
            targetPlaylistId = targetPlaylistId,
            existingCount = existingCount
        )
        addedCount
    }

    private suspend fun importSmb1Entries(
        server: SmbServerProfile,
        cleanHost: String,
        audioEntries: List<SmbBrowserEntry>,
        targetPlaylistId: Long?,
        existingCount: Int
    ): Int {
        var addedCount = 0
        val base = buildJcifsBaseContext(smb1First = true)
        val rootPrefix = buildJcifsServerRoot(cleanHost, server.port)
        val ctx = resolveWorkingJcifsContext(server, rootPrefix, audioEntries.first().shareName, base)

        audioEntries.forEachIndexed { idx, entry ->
            val uriStr = buildSmbUri(
                serverId = server.id,
                shareName = entry.shareName,
                relativePath = entry.relativePath,
                fileName = entry.name,
                protocolHint = "smb1"
            )
            if (trackDao.getByUri(uriStr) == null) {
                val track = extractSmb1Track(
                    rootPrefix = rootPrefix,
                    ctx = ctx,
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
        return addedCount
    }

    private fun resolveWorkingJcifsContext(
        server: SmbServerProfile,
        rootPrefix: String,
        shareName: String,
        base: CIFSContext
    ): CIFSContext {
        val contexts = buildJcifsAuthContexts(server, base)
        if (contexts.size == 1) return contexts.first()
        for (candidate in contexts) {
            val ok = runCatching {
                JcifsSmbFile("$rootPrefix${shareName.trim('/', '\\')}/", candidate).exists()
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return contexts.first()
    }

    private fun extractSmb2Track(
        diskShare: DiskShare,
        server: SmbServerProfile,
        entry: SmbBrowserEntry,
        uriString: String,
        artHint: Int
    ): Track {
        val head = ByteArray(16 * 1024)
        var bytesRead = 0
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
                bytesRead = f.read(head, 0L)
            }
        }
        return parseAudioHeaderToTrack(
            head = head,
            bytesRead = bytesRead,
            fileSizeBytes = entry.sizeBytes,
            fileName = entry.name,
            serverAlias = server.alias,
            shareName = entry.shareName,
            uriString = uriString,
            artHint = artHint
        )
    }

    private fun extractSmb1Track(
        rootPrefix: String,
        ctx: CIFSContext,
        server: SmbServerProfile,
        entry: SmbBrowserEntry,
        uriString: String,
        artHint: Int
    ): Track {
        val head = ByteArray(16 * 1024)
        var bytesRead = 0
        runCatching {
            val target = resolveJcifsTargetFile(rootPrefix, ctx, entry.shareName, entry.relativePath)
            SmbRandomAccessFile(target, "r").use { raf ->
                bytesRead = raf.read(head, 0, head.size)
            }
        }
        return parseAudioHeaderToTrack(
            head = head,
            bytesRead = bytesRead,
            fileSizeBytes = entry.sizeBytes,
            fileName = entry.name,
            serverAlias = server.alias,
            shareName = entry.shareName,
            uriString = uriString,
            artHint = artHint
        )
    }

    private fun parseAudioHeaderToTrack(
        head: ByteArray,
        bytesRead: Int,
        fileSizeBytes: Long,
        fileName: String,
        serverAlias: String,
        shareName: String,
        uriString: String,
        artHint: Int
    ): Track {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        var format = guessFormat(ext)
        var sampleRate = if (format == AudioFormatBadge.DSD) 2_822_400 else 44_100
        var bitDepth = if (format == AudioFormatBadge.DSD) 1 else 16
        var channels = 2
        var durationMs = 0L

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
                    if (fileSizeBytes > 44L) {
                        durationMs = ((fileSizeBytes - 44L) * 1000L) / byteRate
                    }
                }
            }
        }

        val bitrateKbps = if (durationMs > 0L && fileSizeBytes > 0L) {
            ((fileSizeBytes * 8L) / durationMs).toInt()
        } else 0

        val baseTitle = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { fileName }

        return Track(
            uriString = uriString,
            title = baseTitle,
            artist = serverAlias,
            album = "NAS · $shareName",
            durationMs = durationMs.coerceAtLeast(0L),
            format = format,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels,
            fileSizeBytes = fileSizeBytes,
            artIndex = artHint,
            isReferenceTrack = false
        )
    }

    fun openSmbConnectionHandle(uri: Uri): SmbOpenHandle? {
        if (uri.scheme != "smb") return null
        val sid = uri.getQueryParameter("sid")?.toLongOrNull() ?: return null
        val rawShare = uri.getQueryParameter("share") ?: return null
        val rawRelPath = uri.getQueryParameter("path") ?: return null
        val protoHint = uri.getQueryParameter("proto").orEmpty()
        val (shareName, relPath) = splitShareAndSubpath(rawShare, rawRelPath)

        val entity = kotlinx.coroutines.runBlocking(Dispatchers.IO) { smbServerDao.getById(sid) }
            ?: return null
        val server = entity.toDomain()
        val cleanHost = normalizeHost(server.host)

        if (!protoHint.equals("smb1", ignoreCase = true)) {
            val smb2Handle = runCatching {
                openSmb2Handle(server, cleanHost, shareName, relPath)
            }.getOrNull()
            if (smb2Handle != null) return smb2Handle
        }

        return openSmb1Handle(server, cleanHost, shareName, relPath)
    }

    private fun openSmb2Handle(
        server: SmbServerProfile,
        cleanHost: String,
        shareName: String,
        relPath: String
    ): SmbOpenHandle {
        val client = buildClient(multiProtocolNegotiate = true)
        var conn: Connection? = null
        var sess: Session? = null
        var share: DiskShare? = null
        var file: SmbjFile? = null
        try {
            conn = client.connect(cleanHost, server.port)
            sess = authenticateSmb2WithFallback(conn, server)
            share = sess.connectShare(shareName) as DiskShare
            file = share.openFile(
                relPath.replace('/', '\\').trim('\\'),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val fileSize = file.fileInformation.standardInformation.endOfFile
            return Smb2OpenHandle(client, conn, sess, share, file, fileSize)
        } catch (t: Throwable) {
            runCatching { file?.close() }
            runCatching { share?.close() }
            runCatching { sess?.close() }
            runCatching { conn?.close() }
            runCatching { client.close() }
            throw t
        }
    }

    private fun openSmb1Handle(
        server: SmbServerProfile,
        cleanHost: String,
        shareName: String,
        relPath: String
    ): SmbOpenHandle {
        val base = buildJcifsBaseContext(smb1First = true)
        val rootPrefix = buildJcifsServerRoot(cleanHost, server.port)
        val ctx = resolveWorkingJcifsContext(server, rootPrefix, shareName, base)
        val target = resolveJcifsTargetFile(rootPrefix, ctx, shareName, relPath)
        val size = target.length()
        val raf = SmbRandomAccessFile(target, "r")
        return Smb1OpenHandle(target, raf, size)
    }

    private fun resolveJcifsTargetFile(
        rootPrefix: String,
        ctx: CIFSContext,
        shareName: String,
        relPath: String
    ): JcifsSmbFile {
        var current = JcifsSmbFile("$rootPrefix${shareName.trim('/', '\\')}/", ctx)
        val segments = relPath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        for (i in 0 until segments.lastIndex) {
            current = JcifsSmbFile(current, "${segments[i]}/")
        }
        return if (segments.isNotEmpty()) JcifsSmbFile(current, segments.last()) else current
    }

    private fun buildJcifsServerRoot(cleanHost: String, port: Int): String {
        val portSuffix = if (port in 1..65535 && port != 445) ":$port" else ""
        return "smb://$cleanHost$portSuffix/"
    }

    private fun formatFriendlySmbError(err: Throwable, host: String, share: String): String {
        val chain = generateSequence(err) { it.cause }.toList()
        val smbErr = chain.filterIsInstance<SmbException>().firstOrNull()
        val hasEof = chain.any { it is EOFException }
        val hasTimeout = chain.any { it is SocketTimeoutException }
        val hasUnknownHost = chain.any { it is UnknownHostException }
        val hasConnRefused = chain.any { it is SocketException }
        val hasAuth = chain.any {
            it is SmbAuthException ||
                (it.message?.contains("LOGON_FAILURE", ignoreCase = true) == true) ||
                (it.message?.contains("STATUS_ACCESS_DENIED", ignoreCase = true) == true)
        }

        return when {
            hasUnknownHost ->
                "No se encontró el equipo \"$host\" en la red Wi-Fi. Prueba usar su IP local."
            hasAuth ->
                "Acceso denegado en \"$host\". Verifica usuario y contraseña (o activa/desactiva acceso Invitado)."
            smbErr?.message?.contains("BAD_NETWORK_NAME", ignoreCase = true) == true ||
                chain.any { it.message?.contains("BAD_NETWORK_NAME", ignoreCase = true) == true } ->
                "No existe la carpeta compartida \"$share\" en $host. Revisa el nombre exacto del Share."
            hasTimeout ->
                "Tiempo de espera agotado conectando a $host. Comprueba que estés en el mismo Wi-Fi."
            hasEof || hasConnRefused ->
                "El servidor $host cerró la conexión (intentado en SMB2/3 y SMB1). Revisa el nombre del Share, puerto 445 o credenciales del router."
            else ->
                chain.lastOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: err.localizedMessage
                    ?: "No se pudo conectar a $host"
        }
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
        fun normalizeHost(rawHost: String): String {
            return rawHost.trim()
                .removePrefix("smb://")
                .removePrefix("SMB://")
                .trim('/', '\\')
                .substringBefore('/')
                .substringBefore('\\')
        }

        fun splitShareAndSubpath(rawShare: String, currentRelPath: String): Pair<String, String> {
            val cleanedShareInput = rawShare.trim()
                .removePrefix("smb://")
                .replace('\\', '/')
                .trim('/')
            if (cleanedShareInput.isBlank()) {
                return "" to currentRelPath.replace('/', '\\').trim('\\')
            }
            val firstSlash = cleanedShareInput.indexOf('/')
            val shareRoot = if (firstSlash >= 0) cleanedShareInput.substring(0, firstSlash) else cleanedShareInput
            val shareSubpath = if (firstSlash >= 0) {
                cleanedShareInput.substring(firstSlash + 1).trim('/').replace('/', '\\')
            } else {
                ""
            }
            val cleanCurrent = currentRelPath.replace('/', '\\').trim('\\')
            val combinedPath = when {
                shareSubpath.isNotBlank() && cleanCurrent.isNotBlank() ->
                    if (cleanCurrent.equals(shareSubpath, ignoreCase = true) ||
                        cleanCurrent.startsWith("$shareSubpath\\", ignoreCase = true)
                    ) {
                        cleanCurrent
                    } else {
                        "$shareSubpath\\$cleanCurrent"
                    }
                shareSubpath.isNotBlank() -> shareSubpath
                else -> cleanCurrent
            }
            return shareRoot to combinedPath
        }

        fun buildSmbUri(
            serverId: Long,
            shareName: String,
            relativePath: String,
            fileName: String,
            protocolHint: String = ""
        ): String {
            val builder = Uri.Builder()
                .scheme("smb")
                .authority("nas-$serverId")
                .appendPath(fileName)
                .appendQueryParameter("sid", serverId.toString())
                .appendQueryParameter("share", shareName)
                .appendQueryParameter("path", relativePath)
            if (protocolHint.isNotBlank()) {
                builder.appendQueryParameter("proto", protocolHint)
            }
            return builder.build().toString()
        }
    }
}

sealed interface SmbOpenHandle {
    val fileSize: Long
    fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int
    fun closeQuietly()
}

private class Smb2OpenHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: SmbjFile,
    override val fileSize: Long
) : SmbOpenHandle {
    override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int {
        return file.read(buffer, offset, destOffset, length)
    }

    override fun closeQuietly() {
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

private class Smb1OpenHandle(
    private val smbFile: JcifsSmbFile,
    private val raf: SmbRandomAccessFile,
    override val fileSize: Long
) : SmbOpenHandle {
    override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int {
        synchronized(raf) {
            if (raf.filePointer != offset) {
                raf.seek(offset)
            }
            return raf.read(buffer, destOffset, length)
        }
    }

    override fun closeQuietly() {
        runCatching { raf.close() }
        runCatching { smbFile.close() }
    }
}
