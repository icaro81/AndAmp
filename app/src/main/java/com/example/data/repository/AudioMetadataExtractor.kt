package com.example.data.repository

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.Track
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object AudioMetadataExtractor {

    fun extractTrackMetadata(context: Context, uri: Uri, artIndexHint: Int = 0, isReference: Boolean = false): Track? {
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Pista de Audio"
        val fileSize = queryFileSize(context, uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()

        // 1. Check DSD (.dsf / .dff) directly via binary header
        if (ext == "dsf" || ext == "dff") {
            val dsdInfo = parseDsdHeader(context, uri, ext)
            val baseName = displayName.substringBeforeLast('.')
            return Track(
                uriString = uri.toString(),
                title = dsdInfo?.title ?: formatCleanTitle(baseName),
                artist = dsdInfo?.artist ?: "Master DSD",
                album = dsdInfo?.album ?: "Direct Stream Digital",
                durationMs = dsdInfo?.durationMs ?: 0L,
                format = AudioFormatBadge.DSD,
                sampleRateHz = dsdInfo?.sampleRateHz ?: 2_822_400,
                bitDepth = 1,
                bitrateKbps = ((dsdInfo?.sampleRateHz ?: 2_822_400) * (dsdInfo?.channels ?: 2)) / 1000,
                channels = dsdInfo?.channels ?: 2,
                fileSizeBytes = fileSize,
                artIndex = artIndexHint,
                isReferenceTrack = isReference
            )
        }

        // 2. General metadata via MediaMetadataRetriever
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var bitrateKbps = 0

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrateKbps = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
        } catch (_: Exception) {
            // Fallback to container header parsing below
        } finally {
            runCatching { mmr.release() }
        }

        // 3. Container-specific Hi-Res spec extraction (FLAC / ALAC / WAV / AIFF / MediaExtractor)
        var formatBadge = guessFormatFromExtension(ext)
        var sampleRateHz = 44_100
        var bitDepth = 16
        var channels = 2

        if (ext == "flac") {
            parseFlacHeader(context, uri)?.let { flac ->
                formatBadge = AudioFormatBadge.FLAC
                sampleRateHz = flac.sampleRateHz
                bitDepth = flac.bitDepth
                channels = flac.channels
                if (durationMs <= 0L) durationMs = flac.durationMs
                if (title.isNullOrBlank()) title = flac.title
                if (artist.isNullOrBlank()) artist = flac.artist
                if (album.isNullOrBlank()) album = flac.album
            }
        } else if (ext == "m4a" || ext == "alac" || ext == "mp4" || ext == "caf") {
            val alacInfo = parseMp4AlacHeader(context, uri)
            if (alacInfo != null) {
                formatBadge = AudioFormatBadge.ALAC
                sampleRateHz = alacInfo.sampleRateHz
                bitDepth = alacInfo.bitDepth
                channels = alacInfo.channels
                if (durationMs <= 0L) durationMs = alacInfo.durationMs
            } else {
                inspectWithMediaExtractor(context, uri)?.let { info ->
                    if (info.mime.contains("alac", ignoreCase = true)) formatBadge = AudioFormatBadge.ALAC
                    else if (formatBadge == AudioFormatBadge.UNKNOWN) formatBadge = AudioFormatBadge.AAC
                    sampleRateHz = info.sampleRateHz
                    bitDepth = info.bitDepth
                    channels = info.channels
                    if (durationMs <= 0L) durationMs = info.durationMs
                }
            }
        } else if (ext == "wav") {
            parseWavHeader(context, uri)?.let { wav ->
                formatBadge = AudioFormatBadge.WAV
                sampleRateHz = wav.sampleRateHz
                bitDepth = wav.bitDepth
                channels = wav.channels
                if (durationMs <= 0L) durationMs = wav.durationMs
            } ?: inspectWithMediaExtractor(context, uri)?.let { info ->
                formatBadge = AudioFormatBadge.WAV
                sampleRateHz = info.sampleRateHz
                bitDepth = info.bitDepth
                channels = info.channels
                if (durationMs <= 0L) durationMs = info.durationMs
            }
        } else {
            inspectWithMediaExtractor(context, uri)?.let { info ->
                if (formatBadge == AudioFormatBadge.UNKNOWN) {
                    formatBadge = mapMimeToBadge(info.mime)
                }
                sampleRateHz = info.sampleRateHz
                bitDepth = info.bitDepth
                channels = info.channels
                if (durationMs <= 0L) durationMs = info.durationMs
            }
        }

        if (bitrateKbps <= 0 && durationMs > 0L && fileSize > 0L) {
            bitrateKbps = ((fileSize * 8L) / durationMs).toInt()
        }

        val cleanBase = formatCleanTitle(displayName.substringBeforeLast('.'))
        return Track(
            uriString = uri.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: cleanBase,
            artist = artist?.takeIf { it.isNotBlank() } ?: "Artista Desconocido",
            album = album?.takeIf { it.isNotBlank() } ?: "Archivo Local",
            durationMs = durationMs.coerceAtLeast(0L),
            format = formatBadge,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels,
            fileSizeBytes = fileSize,
            artIndex = artIndexHint,
            isReferenceTrack = isReference
        )
    }

    private val artworkPresenceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun loadEmbeddedArtworkBytes(context: Context, uriString: String): ByteArray? {
        if (artworkPresenceCache[uriString] == false) return null
        val uri = Uri.parse(uriString)

        // 1. Inspect container in pure Kotlin first to avoid noisy JNI ALOGE when no artwork exists
        val inspection = inspectEmbeddedArtwork(context, uri)
        if (inspection.directBytes != null) {
            artworkPresenceCache[uriString] = true
            return inspection.directBytes
        }
        if (!inspection.mayHaveEmbeddedPicture) {
            artworkPresenceCache[uriString] = false
            return null
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture.also { bytes ->
                artworkPresenceCache[uriString] = (bytes != null)
            }
        } catch (_: Exception) {
            artworkPresenceCache[uriString] = false
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private data class ArtworkInspection(
        val mayHaveEmbeddedPicture: Boolean,
        val directBytes: ByteArray? = null
    )

    private fun inspectEmbeddedArtwork(context: Context, uri: Uri): ArtworkInspection {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(16)
                val read = stream.read(head)
                if (read < 12) return ArtworkInspection(false)
                val magic4 = String(head, 0, 4, Charsets.US_ASCII)

                when {
                    // DSD containers (.dsf / .dff) - check ID3v2 only if needed, skip heavy scan
                    magic4 == "DSD " || magic4 == "FRM8" -> ArtworkInspection(false)

                    // FLAC: walk metadata blocks; extract block type 6 (PICTURE) directly in Kotlin
                    magic4 == "fLaC" -> parseFlacPictureBlock(head, read, stream)

                    // MP3 / ID3v2: check tag payload for APIC / PIC frame
                    head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> {
                        val tagSize = ((head[6].toInt() and 0x7F) shl 21) or
                            ((head[7].toInt() and 0x7F) shl 14) or
                            ((head[8].toInt() and 0x7F) shl 7) or
                            (head[9].toInt() and 0x7F)
                        val toScan = tagSize.coerceIn(0, 2 * 1024 * 1024)
                        val buf = ByteArray(toScan)
                        var offset = (read - 10).coerceAtLeast(0)
                        if (offset > 0) System.arraycopy(head, 10, buf, 0, minOf(offset, buf.size))
                        while (offset < buf.size) {
                            val r = stream.read(buf, offset, buf.size - offset)
                            if (r <= 0) break
                            offset += r
                        }
                        val hasPic = containsBytes(buf, offset, "APIC".toByteArray(Charsets.US_ASCII)) ||
                            containsBytes(buf, offset, "PIC".toByteArray(Charsets.US_ASCII))
                        ArtworkInspection(mayHaveEmbeddedPicture = hasPic)
                    }

                    // MP4 / M4A / ALAC: walk top-level boxes and inspect only 'moov' for 'covr'
                    String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                        ArtworkInspection(mayHaveEmbeddedPicture = mp4MoovContainsCovr(head, read, stream))
                    }

                    // OGG / Opus: check for METADATA_BLOCK_PICTURE Vorbis comment
                    magic4 == "OggS" -> {
                        val rest = stream.readBytesBounded(512 * 1024)
                        val hasPic = containsBytes(rest, rest.size, "METADATA_BLOCK_PICTURE".toByteArray(Charsets.US_ASCII))
                        ArtworkInspection(mayHaveEmbeddedPicture = hasPic)
                    }

                    else -> ArtworkInspection(false)
                }
            } ?: ArtworkInspection(false)
        } catch (_: Exception) {
            ArtworkInspection(false)
        }
    }

    private fun parseFlacPictureBlock(
        head: ByteArray,
        headLen: Int,
        stream: InputStream
    ): ArtworkInspection {
        // Reassemble buffered bytes after "fLaC" (4 bytes)
        var leftover = head.copyOfRange(4, headLen)
        var leftoverOffset = 0

        fun readExact(dst: ByteArray, count: Int): Boolean {
            var filled = 0
            if (leftoverOffset < leftover.size) {
                val avail = minOf(count, leftover.size - leftoverOffset)
                System.arraycopy(leftover, leftoverOffset, dst, 0, avail)
                leftoverOffset += avail
                filled += avail
            }
            while (filled < count) {
                val r = stream.read(dst, filled, count - filled)
                if (r <= 0) return false
                filled += r
            }
            return true
        }

        fun skipExact(count: Int): Boolean {
            var rem = count
            if (leftoverOffset < leftover.size) {
                val avail = minOf(rem, leftover.size - leftoverOffset)
                leftoverOffset += avail
                rem -= avail
            }
            val scratch = ByteArray(4096)
            while (rem > 0) {
                val r = stream.read(scratch, 0, minOf(rem, scratch.size))
                if (r <= 0) return false
                rem -= r
            }
            return true
        }

        val blockHdr = ByteArray(4)
        while (readExact(blockHdr, 4)) {
            val isLast = (blockHdr[0].toInt() and 0x80) != 0
            val blockType = blockHdr[0].toInt() and 0x7F
            val blockLen = ((blockHdr[1].toInt() and 0xFF) shl 16) or
                ((blockHdr[2].toInt() and 0xFF) shl 8) or
                (blockHdr[3].toInt() and 0xFF)

            if (blockType == 6 && blockLen in 32..(8 * 1024 * 1024)) {
                val payload = ByteArray(blockLen)
                if (readExact(payload, blockLen)) {
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                    // 4B pictureType + 4B mimeLen + mime + 4B descLen + desc + 16B dims + 4B dataLen
                    var p = 4
                    if (p + 4 <= payload.size) {
                        val mimeLen = bb.getInt(p).coerceAtLeast(0)
                        p += 4 + mimeLen
                        if (p + 4 <= payload.size) {
                            val descLen = bb.getInt(p).coerceAtLeast(0)
                            p += 4 + descLen + 16
                            if (p + 4 <= payload.size) {
                                val dataLen = bb.getInt(p).coerceAtLeast(0)
                                p += 4
                                if (dataLen > 0 && p + dataLen <= payload.size) {
                                    return ArtworkInspection(
                                        mayHaveEmbeddedPicture = true,
                                        directBytes = payload.copyOfRange(p, p + dataLen)
                                    )
                                }
                            }
                        }
                    }
                }
                return ArtworkInspection(mayHaveEmbeddedPicture = true)
            } else {
                if (!skipExact(blockLen)) break
            }
            if (isLast) break
        }
        return ArtworkInspection(mayHaveEmbeddedPicture = false)
    }

    private fun mp4MoovContainsCovr(head: ByteArray, headLen: Int, stream: InputStream): Boolean {
        if (headLen < 8) return false
        val firstSize = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (firstSize < 8L) return false
        // Skip remaining bytes of ftyp box
        var remFtyp = firstSize - headLen.toLong()
        while (remFtyp > 0L) {
            val s = stream.skip(remFtyp)
            if (s <= 0L) break
            remFtyp -= s
        }
        val boxHdr = ByteArray(8)
        while (true) {
            var got = 0
            while (got < 8) {
                val r = stream.read(boxHdr, got, 8 - got)
                if (r <= 0) return false
                got += r
            }
            var boxSize = ByteBuffer.wrap(boxHdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val boxType = String(boxHdr, 4, 4, Charsets.US_ASCII)
            var hdrSize = 8L
            if (boxSize == 1L) {
                val ext = ByteArray(8)
                var eg = 0
                while (eg < 8) {
                    val r = stream.read(ext, eg, 8 - eg)
                    if (r <= 0) return false
                    eg += r
                }
                boxSize = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                hdrSize = 16L
            }
            if (boxSize < hdrSize) return false
            val payloadSize = boxSize - hdrSize
            if (boxType == "moov") {
                val moovBytes = stream.readBytesBounded(payloadSize.coerceAtMost(4L * 1024 * 1024).toInt())
                return containsBytes(moovBytes, moovBytes.size, "covr".toByteArray(Charsets.US_ASCII))
            } else {
                var toSkip = payloadSize
                while (toSkip > 0L) {
                    val s = stream.skip(toSkip)
                    if (s <= 0L) {
                        val scratch = ByteArray(minOf(toSkip, 8192L).toInt())
                        val r = stream.read(scratch)
                        if (r <= 0) return false
                        toSkip -= r
                    } else {
                        toSkip -= s
                    }
                }
            }
        }
    }

    private fun InputStream.readBytesBounded(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val r = read(buf, 0, minOf(buf.size, maxBytes - total))
            if (r <= 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    private fun containsBytes(haystack: ByteArray, length: Int, needle: ByteArray): Boolean {
        if (length < needle.size) return false
        outer@ for (i in 0..(length - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private data class ParsedInfo(
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channels: Int,
        val durationMs: Long,
        val mime: String = "",
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null
    )

    private fun parseDsdHeader(context: Context, uri: Uri, ext: String): ParsedInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(128)
                val read = stream.read(head)
                if (read < 64) return null
                val magic = String(head, 0, 4, Charsets.US_ASCII)
                if (magic == "DSD " && read >= 80) {
                    val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                    val ch = bb.getInt(28 + 24).coerceIn(1, 2)
                    val sr = bb.getInt(28 + 28).coerceAtLeast(2_822_400)
                    val sampleCount = bb.getLong(28 + 36)
                    val durMs = if (sr > 0) (sampleCount * 1000L) / sr else 0L
                    ParsedInfo(sr, 1, ch, durMs)
                } else if (magic == "FRM8" && ext == "dff") {
                    // Best-effort quick scan for FS  and CHNL inside first 512 bytes
                    val bigHead = ByteArray(512)
                    System.arraycopy(head, 0, bigHead, 0, read)
                    val extra = stream.read(bigHead, read, bigHead.size - read).coerceAtLeast(0)
                    val total = read + extra
                    var sr = 2_822_400
                    var ch = 2
                    val bb = ByteBuffer.wrap(bigHead).order(ByteOrder.BIG_ENDIAN)
                    for (i in 0 until total - 16) {
                        if (bigHead[i] == 'F'.code.toByte() && bigHead[i + 1] == 'S'.code.toByte() &&
                            bigHead[i + 2] == ' '.code.toByte() && bigHead[i + 3] == ' '.code.toByte()
                        ) {
                            sr = bb.getInt(i + 12).coerceAtLeast(2_822_400)
                        }
                        if (bigHead[i] == 'C'.code.toByte() && bigHead[i + 1] == 'H'.code.toByte() &&
                            bigHead[i + 2] == 'N'.code.toByte() && bigHead[i + 3] == 'L'.code.toByte()
                        ) {
                            ch = bb.getShort(i + 12).toInt().coerceIn(1, 2)
                        }
                    }
                    val fileBytes = queryFileSize(context, uri)
                    val durMs = if (fileBytes > 128 && sr > 0) ((fileBytes - 128) * 8L * 1000L) / (sr.toLong() * ch) else 0L
                    ParsedInfo(sr, 1, ch, durMs)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlacHeader(context: Context, uri: Uri): ParsedInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(42)
                if (stream.read(head) < 42) return null
                if (String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
                // STREAMINFO starts at offset 8 (18 bytes min/max + 16 bytes metadata)
                val b10 = head[8 + 10].toInt() and 0xFF
                val b11 = head[8 + 11].toInt() and 0xFF
                val b12 = head[8 + 12].toInt() and 0xFF
                val b13 = head[8 + 13].toInt() and 0xFF
                val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                val channels = ((b12 ushr 1) and 0x07) + 1
                val bps = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                val totalSamples = ((b13.toLong() and 0x0F) shl 32) or
                    ((head[8 + 14].toLong() and 0xFF) shl 24) or
                    ((head[8 + 15].toLong() and 0xFF) shl 16) or
                    ((head[8 + 16].toLong() and 0xFF) shl 8) or
                    (head[8 + 17].toLong() and 0xFF)
                val durMs = if (sampleRate > 0) (totalSamples * 1000L) / sampleRate else 0L
                ParsedInfo(sampleRate, bps, channels, durMs, "audio/flac")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMp4AlacHeader(context: Context, uri: Uri): ParsedInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = readBoundedMoovOrHead(stream, 4 * 1024 * 1024) ?: return null
                // Search for "alac" magic cookie inside stsd -> inner alac box
                val needle = "alac".toByteArray(Charsets.US_ASCII)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                for (i in 0 until bytes.size - 32) {
                    if (bytes[i] == needle[0] && bytes[i + 1] == needle[1] &&
                        bytes[i + 2] == needle[2] && bytes[i + 3] == needle[3]
                    ) {
                        // Inner alac box has 4-byte size before "alac", 4-byte version/flags after, then 24-byte config
                        // Config: +0 frameLen(4), +4 compat(1), +5 bitDepth(1), +9 ch(1), +20 sampleRate(4)
                        val c = i + 8
                        if (c + 24 <= bytes.size) {
                            val bitDepth = bytes[c + 5].toInt() and 0xFF
                            val ch = bytes[c + 9].toInt() and 0xFF
                            val sr = bb.getInt(c + 20)
                            if (bitDepth in listOf(16, 20, 24, 32) && ch in 1..8 && sr in 8000..384_000) {
                                return ParsedInfo(sr, bitDepth, ch, 0L, "audio/alac")
                            }
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBoundedMoovOrHead(stream: InputStream, maxTotalBytes: Int): ByteArray? {
        val all = stream.readBytes()
        return if (all.size <= maxTotalBytes) all else all.copyOf(maxTotalBytes)
    }

    private fun parseWavHeader(context: Context, uri: Uri): ParsedInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(128)
                val read = stream.read(head)
                if (read < 44) return null
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) {
                    return null
                }
                val bb = ByteBuffer.wrap(head, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 12
                var ch = 2
                var sr = 48_000
                var bd = 16
                var byteRate = 192_000
                var dataBytes = 0L
                while (pos + 8 <= read) {
                    val chunkId = String(head, pos, 4, Charsets.US_ASCII)
                    val chunkSize = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                    val bodyStart = pos + 8
                    if (chunkId == "fmt " && bodyStart + 16 <= read) {
                        ch = bb.getShort(bodyStart + 2).toInt().coerceIn(1, 8)
                        sr = bb.getInt(bodyStart + 4).coerceAtLeast(8_000)
                        byteRate = bb.getInt(bodyStart + 8).coerceAtLeast(1)
                        bd = bb.getShort(bodyStart + 14).toInt().coerceIn(8, 32)
                    } else if (chunkId == "data") {
                        dataBytes = chunkSize
                        break
                    }
                    pos = bodyStart + chunkSize.toInt() + (chunkSize.toInt() and 1)
                }
                if (dataBytes <= 0L) {
                    val totalSize = queryFileSize(context, uri)
                    if (totalSize > 44L) dataBytes = totalSize - 44L
                }
                val durMs = if (byteRate > 0 && dataBytes > 0L) (dataBytes * 1000L) / byteRate else 0L
                ParsedInfo(sr, bd, ch, durMs, "audio/wav")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inspectWithMediaExtractor(context: Context, uri: Uri): ParsedInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
                    val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                    val pcmEnc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else 0
                    val bitDepth = when (pcmEnc) {
                        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        android.media.AudioFormat.ENCODING_PCM_32BIT,
                        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                        else -> 16
                    }
                    return ParsedInfo(sr, bitDepth, ch, durUs / 1000L, mime)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            return runCatching { java.io.File(uri.path ?: "").length() }.getOrDefault(0L)
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun guessFormatFromExtension(ext: String): AudioFormatBadge = when (ext) {
        "flac" -> AudioFormatBadge.FLAC
        "alac" -> AudioFormatBadge.ALAC
        "dsf", "dff" -> AudioFormatBadge.DSD
        "wav" -> AudioFormatBadge.WAV
        "aif", "aiff" -> AudioFormatBadge.AIFF
        "opus" -> AudioFormatBadge.OPUS
        "ogg", "oga" -> AudioFormatBadge.OGG
        "aac" -> AudioFormatBadge.AAC
        "m4a" -> AudioFormatBadge.AAC // overridden if alac box detected
        "mp3" -> AudioFormatBadge.MP3
        else -> AudioFormatBadge.UNKNOWN
    }

    private fun mapMimeToBadge(mime: String): AudioFormatBadge = when {
        mime.contains("flac", true) -> AudioFormatBadge.FLAC
        mime.contains("alac", true) -> AudioFormatBadge.ALAC
        mime.contains("opus", true) -> AudioFormatBadge.OPUS
        mime.contains("vorbis", true) -> AudioFormatBadge.OGG
        mime.contains("mpeg", true) -> AudioFormatBadge.MP3
        mime.contains("mp4a", true) -> AudioFormatBadge.AAC
        mime.contains("raw", true) -> AudioFormatBadge.WAV
        else -> AudioFormatBadge.UNKNOWN
    }

    private fun formatCleanTitle(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim().ifBlank { "Pista Sin Título" }
}
