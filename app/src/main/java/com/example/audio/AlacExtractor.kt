package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Pure-Kotlin Apple Lossless (ALAC) MP4/M4A Extractor & Decoder.
 * Ensures lossless 16-bit and 24-bit ALAC playback across all Android devices, including
 * devices/emulators that do not bundle a platform MediaCodec ALAC decoder.
 */
@UnstableApi
class AlacExtractor(
    private val forceForM4aHint: Boolean = false
) : Extractor {

    private data class AlacConfig(
        val frameLength: Int,
        val bitDepth: Int,
        val pb: Int,
        val mb: Int,
        val kb: Int,
        val channels: Int,
        val maxRun: Int,
        val sampleRate: Int
    )

    private data class SampleEntry(
        val offset: Long,
        val size: Int,
        val ptsUs: Long,
        val sampleCount: Int
    )

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var parsed = false
    private var alacConfig: AlacConfig? = null
    private var samples: List<SampleEntry> = emptyList()
    private var totalDurationUs: Long = C.TIME_UNSET
    private var currentSampleIndex = 0

    override fun sniff(input: ExtractorInput): Boolean {
        val hdr = ByteArray(12)
        if (!input.peekFully(hdr, 0, 12, true)) return false
        val boxType = String(hdr, 4, 4, Charsets.US_ASCII)
        if (boxType != "ftyp") return false
        val brand = String(hdr, 8, 4, Charsets.US_ASCII)
        val isMp4Brand = brand in setOf("M4A ", "M4B ", "mp42", "isom", "qt  ", "M4P ")
        if (!isMp4Brand) return false

        if (forceForM4aHint) return true

        // Peek up to 256 KB to check if moov/alac is at the start of the file
        val maxPeek = min(input.length.takeIf { it > 0 } ?: 262_144L, 262_144L).toInt()
        if (maxPeek <= 32) return false
        val buf = ByteArray(maxPeek)
        // Reset peek and read what's available without failing
        var read = 0
        val boxHdr = ByteArray(8)
        while (read + 8 < maxPeek) {
            if (!input.peekFully(boxHdr, 0, 8, true)) break
            val sz = ByteBuffer.wrap(boxHdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = String(boxHdr, 4, 4, Charsets.US_ASCII)
            if (sz < 8) break
            if (type == "moov" && read + sz <= maxPeek) {
                val moovBytes = ByteArray(sz.toInt() - 8)
                if (input.peekFully(moovBytes, 0, moovBytes.size, true)) {
                    return containsAscii(moovBytes, "alac")
                }
                break
            } else if (type == "mdat") {
                // moov is after mdat; rely on forceForM4aHint if flagged by registry
                break
            } else {
                val skip = (sz - 8).toInt()
                if (read + sz > maxPeek) break
                input.advancePeekPosition(skip)
                read += sz.toInt()
            }
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!parsed) {
            if (!parseMp4AlacMetadata(input)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            parsed = true
            val cfg = alacConfig ?: return Extractor.RESULT_END_OF_INPUT

            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(cfg.channels.coerceIn(1, 2))
                .setSampleRate(cfg.sampleRate)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            trackOutput?.format(format)

            extractorOutput?.seekMap(AlacSeekMap(totalDurationUs, samples))

            if (samples.isNotEmpty() && input.position != samples[0].offset) {
                seekPosition.position = samples[0].offset
                return Extractor.RESULT_SEEK
            }
        }

        if (currentSampleIndex >= samples.size) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val entry = samples[currentSampleIndex]
        if (input.position != entry.offset) {
            seekPosition.position = entry.offset
            return Extractor.RESULT_SEEK
        }

        val packet = ByteArray(entry.size)
        if (!input.readFully(packet, 0, entry.size, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }
        currentSampleIndex++

        val cfg = alacConfig ?: return Extractor.RESULT_END_OF_INPUT
        val pcmBytes = decodeAlacPacketToFloatPcm(packet, cfg)
        if (pcmBytes.isNotEmpty()) {
            val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
            out.sampleData(ParsableByteArray(pcmBytes), pcmBytes.size)
            out.sampleMetadata(entry.ptsUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes.size, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!parsed || samples.isEmpty()) {
            currentSampleIndex = 0
            return
        }
        val idx = samples.binarySearch { it.ptsUs.compareTo(timeUs) }
        currentSampleIndex = if (idx >= 0) idx else (-idx - 2).coerceIn(0, samples.lastIndex)
    }

    override fun release() = Unit

    private fun parseMp4AlacMetadata(input: ExtractorInput): Boolean {
        val fileLen = input.length
        // Walk top-level boxes via input.readFully / skipFully from position 0
        // If input is not at 0, we assume ExoPlayer starts at 0 before first read.
        var pos = input.position
        val hdr8 = ByteArray(8)
        var moovPayload: ByteArray? = null

        while (fileLen <= 0L || pos + 8 <= fileLen) {
            if (!input.readFully(hdr8, 0, 8, true)) break
            val bb = ByteBuffer.wrap(hdr8).order(ByteOrder.BIG_ENDIAN)
            var boxSize = bb.getInt(0).toLong() and 0xFFFFFFFFL
            val boxType = String(hdr8, 4, 4, Charsets.US_ASCII)
            var headerBytes = 8L
            if (boxSize == 1L) {
                val ext = ByteArray(8)
                if (!input.readFully(ext, 0, 8, true)) break
                boxSize = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                headerBytes = 16L
            } else if (boxSize == 0L && fileLen > 0L) {
                boxSize = fileLen - pos
            }
            if (boxSize < headerBytes) break

            val payloadSize = boxSize - headerBytes
            if (boxType == "moov") {
                val safeSize = payloadSize.toInt().coerceAtMost(8 * 1024 * 1024)
                val buf = ByteArray(safeSize)
                input.readFully(buf, 0, safeSize)
                moovPayload = buf
                break
            } else {
                skipLong(input, payloadSize)
                pos += boxSize
            }
        }

        val moov = moovPayload ?: return false
        return parseMoov(moov)
    }

    private fun skipLong(input: ExtractorInput, bytes: Long) {
        var rem = bytes
        while (rem > 0L) {
            val step = min(rem, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            rem -= step
        }
    }

    private fun parseMoov(moov: ByteArray): Boolean {
        val tracks = findBoxes(moov, 0, moov.size, "trak")
        for (trakRange in tracks) {
            val mdia = findFirstBox(moov, trakRange.first, trakRange.second, "mdia") ?: continue
            val mdhd = findFirstBox(moov, mdia.first, mdia.second, "mdhd")
            val timescale = mdhd?.let { parseMdhdTimescale(moov, it.first, it.second) } ?: 44100L
            val minf = findFirstBox(moov, mdia.first, mdia.second, "minf") ?: continue
            val stbl = findFirstBox(moov, minf.first, minf.second, "stbl") ?: continue
            val stsd = findFirstBox(moov, stbl.first, stbl.second, "stsd") ?: continue
            val cfg = parseStsdAlac(moov, stsd.first, stsd.second) ?: continue

            val stsz = findFirstBox(moov, stbl.first, stbl.second, "stsz") ?: continue
            val stsc = findFirstBox(moov, stbl.first, stbl.second, "stsc") ?: continue
            val stts = findFirstBox(moov, stbl.first, stbl.second, "stts")
            val stco = findFirstBox(moov, stbl.first, stbl.second, "stco")
            val co64 = findFirstBox(moov, stbl.first, stbl.second, "co64")

            val sampleSizes = parseStsz(moov, stsz.first, stsz.second)
            val chunkOffsets = when {
                stco != null -> parseStco(moov, stco.first, stco.second)
                co64 != null -> parseCo64(moov, co64.first, co64.second)
                else -> continue
            }
            val stscEntries = parseStsc(moov, stsc.first, stsc.second)
            val durations = stts?.let { parseStts(moov, it.first, it.second, sampleSizes.size) }
                ?: IntArray(sampleSizes.size) { cfg.frameLength }

            val built = ArrayList<SampleEntry>(sampleSizes.size)
            var sampleIdx = 0
            var accTicks = 0L
            for (chunkIdx in chunkOffsets.indices) {
                val chunkNum1Based = chunkIdx + 1
                var samplesInChunk = 1
                for (e in stscEntries) {
                    if (chunkNum1Based >= e.firstChunk) samplesInChunk = e.samplesPerChunk
                }
                var offset = chunkOffsets[chunkIdx]
                for (s in 0 until samplesInChunk) {
                    if (sampleIdx >= sampleSizes.size) break
                    val sz = sampleSizes[sampleIdx]
                    val durTicks = durations.getOrElse(sampleIdx) { cfg.frameLength }
                    val ptsUs = (accTicks * 1_000_000L) / timescale.coerceAtLeast(1L)
                    built.add(SampleEntry(offset, sz, ptsUs, durTicks))
                    offset += sz
                    accTicks += durTicks
                    sampleIdx++
                }
            }
            alacConfig = cfg
            samples = built
            totalDurationUs = (accTicks * 1_000_000L) / timescale.coerceAtLeast(1L)
            return built.isNotEmpty()
        }
        return false
    }

    private fun parseMdhdTimescale(data: ByteArray, start: Int, end: Int): Long {
        if (start + 16 > end) return 44100L
        val version = data[start].toInt() and 0xFF
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        return if (version == 1) {
            if (start + 24 <= end) bb.getInt(start + 20).toLong() and 0xFFFFFFFFL else 44100L
        } else {
            bb.getInt(start + 12).toLong() and 0xFFFFFFFFL
        }
    }

    private fun parseStsdAlac(data: ByteArray, start: Int, end: Int): AlacConfig? {
        if (start + 8 > end) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val entryCount = bb.getInt(start + 4)
        var pos = start + 8
        for (i in 0 until entryCount) {
            if (pos + 8 > end) break
            val entrySize = bb.getInt(pos)
            val entryType = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (entryType == "alac" && pos + 36 <= end) {
                // AudioSampleEntry is 28 bytes payload (total 36 with header), followed by inner "alac" atom
                val inner = findFirstBox(data, pos + 36, min(pos + entrySize, end), "alac")
                if (inner != null && inner.first + 28 <= inner.second) {
                    // 4 bytes version/flags + 24 bytes ALACSpecificConfig
                    val c = inner.first + 4
                    val frameLen = bb.getInt(c)
                    val bitDepth = data[c + 5].toInt() and 0xFF
                    val pb = data[c + 6].toInt() and 0xFF
                    val mb = data[c + 7].toInt() and 0xFF
                    val kb = data[c + 8].toInt() and 0xFF
                    val channels = data[c + 9].toInt() and 0xFF
                    val maxRun = bb.getShort(c + 10).toInt() and 0xFFFF
                    val sampleRate = bb.getInt(c + 20)
                    return AlacConfig(
                        frameLength = frameLen.coerceAtLeast(1024),
                        bitDepth = bitDepth,
                        pb = pb,
                        mb = mb,
                        kb = kb,
                        channels = channels,
                        maxRun = maxRun,
                        sampleRate = sampleRate
                    )
                }
            }
            pos += entrySize.coerceAtLeast(8)
        }
        return null
    }

    private fun parseStsz(data: ByteArray, start: Int, end: Int): IntArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val fixedSize = bb.getInt(start + 4)
        val count = bb.getInt(start + 8).coerceAtLeast(0)
        if (fixedSize != 0) return IntArray(count) { fixedSize }
        return IntArray(count) { idx ->
            val p = start + 12 + idx * 4
            if (p + 4 <= end) bb.getInt(p) else 0
        }
    }

    private fun parseStco(data: ByteArray, start: Int, end: Int): LongArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val count = bb.getInt(start + 4).coerceAtLeast(0)
        return LongArray(count) { idx ->
            val p = start + 8 + idx * 4
            if (p + 4 <= end) bb.getInt(p).toLong() and 0xFFFFFFFFL else 0L
        }
    }

    private fun parseCo64(data: ByteArray, start: Int, end: Int): LongArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val count = bb.getInt(start + 4).coerceAtLeast(0)
        return LongArray(count) { idx ->
            val p = start + 8 + idx * 8
            if (p + 8 <= end) bb.getLong(p) else 0L
        }
    }

    private data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)

    private fun parseStsc(data: ByteArray, start: Int, end: Int): List<StscEntry> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val count = bb.getInt(start + 4).coerceAtLeast(0)
        val list = ArrayList<StscEntry>(count)
        for (i in 0 until count) {
            val p = start + 8 + i * 12
            if (p + 12 <= end) {
                list.add(StscEntry(bb.getInt(p), bb.getInt(p + 4)))
            }
        }
        return list
    }

    private fun parseStts(data: ByteArray, start: Int, end: Int, totalSamples: Int): IntArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val entryCount = bb.getInt(start + 4).coerceAtLeast(0)
        val out = IntArray(totalSamples)
        var idx = 0
        for (i in 0 until entryCount) {
            val p = start + 8 + i * 8
            if (p + 8 > end) break
            val count = bb.getInt(p)
            val delta = bb.getInt(p + 4)
            for (k in 0 until count) {
                if (idx >= totalSamples) break
                out[idx++] = delta
            }
        }
        return out
    }

    private fun findFirstBox(data: ByteArray, start: Int, end: Int, target: String): Pair<Int, Int>? =
        findBoxes(data, start, end, target).firstOrNull()

    private fun findBoxes(data: ByteArray, start: Int, end: Int, target: String): List<Pair<Int, Int>> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val out = mutableListOf<Pair<Int, Int>>()
        var pos = start
        while (pos + 8 <= end) {
            val sz = bb.getInt(pos).toLong() and 0xFFFFFFFFL
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (sz < 8 || pos + sz > end) break
            if (type == target) {
                out.add((pos + 8) to (pos + sz.toInt()))
            }
            pos += sz.toInt()
        }
        return out
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(haystack.size - n.size)) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }

    // --- Pure-Kotlin Apple Lossless (ALAC) Frame Decoder ---

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun readBits(count: Int): Int {
            var res = 0
            for (i in 0 until count) {
                val byteIdx = bitPos ushr 3
                val bitIdx = 7 - (bitPos and 7)
                val bit = if (byteIdx < data.size) (data[byteIdx].toInt() ushr bitIdx) and 1 else 0
                res = (res shl 1) or bit
                bitPos++
            }
            return res
        }

        fun readSignedBits(count: Int): Int {
            val v = readBits(count)
            val signBit = 1 shl (count - 1)
            return if ((v and signBit) != 0) v - (1 shl count) else v
        }

        fun alignToByte() {
            val rem = bitPos and 7
            if (rem != 0) bitPos += (8 - rem)
        }
    }

    private fun decodeAlacPacketToFloatPcm(packet: ByteArray, cfg: AlacConfig): ByteArray {
        val bits = BitReader(packet)
        val tag = bits.readBits(3)
        if (tag == 7) return ByteArray(0) // ID_END
        val chCount = if (tag == 1) 2 else 1
        bits.readBits(4) // elementInstanceTag
        bits.readBits(12) // unused

        val hasSize = bits.readBits(1)
        val uncompressedBytes = bits.readBits(2)
        val isNotCompressed = bits.readBits(1)
        val numSamples = if (hasSize != 0) bits.readBits(32) else cfg.frameLength
        if (numSamples <= 0 || numSamples > 131_072) return ByteArray(0)

        val outChannels = chCount.coerceAtMost(2)
        val channelSamples = Array(outChannels) { IntArray(numSamples) }
        val scaleDivisor = (1L shl (cfg.bitDepth - 1)).toFloat()

        if (isNotCompressed != 0) {
            for (i in 0 until numSamples) {
                for (c in 0 until outChannels) {
                    channelSamples[c][i] = bits.readSignedBits(cfg.bitDepth)
                }
            }
        } else {
            val mixBits = bits.readBits(8)
            val mixRes = bits.readSignedBits(8)
            val mode = IntArray(outChannels)
            val denShift = IntArray(outChannels)
            val pbFactor = IntArray(outChannels)
            val numCoefs = IntArray(outChannels)
            val coefs = Array(outChannels) { IntArray(32) }

            for (c in 0 until outChannels) {
                mode[c] = bits.readBits(4)
                denShift[c] = bits.readBits(4)
                pbFactor[c] = bits.readBits(3)
                numCoefs[c] = bits.readBits(5)
                for (k in 0 until numCoefs[c]) {
                    coefs[c][k] = bits.readSignedBits(16)
                }
            }

            val shiftBits = uncompressedBytes * 8
            val extraLsbs = if (shiftBits > 0) Array(outChannels) { IntArray(numSamples) } else null
            if (extraLsbs != null) {
                for (i in 0 until numSamples) {
                    for (c in 0 until outChannels) {
                        extraLsbs[c][i] = bits.readBits(shiftBits)
                    }
                }
            }

            val riceBits = (cfg.bitDepth - shiftBits) + if (outChannels == 2 && mixRes != 0) 1 else 0
            val predictors = Array(outChannels) { IntArray(numSamples) }

            for (c in 0 until outChannels) {
                dynDecomp(
                    bits = bits,
                    out = predictors[c],
                    numSamples = numSamples,
                    bps = riceBits,
                    mbInit = cfg.mb,
                    kb = cfg.kb,
                    pb = cfg.pb,
                    maxRun = cfg.maxRun
                )
                lpcPredict(
                    residuals = predictors[c],
                    out = channelSamples[c],
                    numSamples = numSamples,
                    coefs = coefs[c],
                    numCoefs = numCoefs[c],
                    denShift = denShift[c],
                    bps = riceBits
                )
            }

            if (outChannels == 2) {
                unmixStereo(channelSamples[0], channelSamples[1], numSamples, mixBits, mixRes)
            }

            if (extraLsbs != null) {
                for (i in 0 until numSamples) {
                    for (c in 0 until outChannels) {
                        channelSamples[c][i] = (channelSamples[c][i] shl shiftBits) or extraLsbs[c][i]
                    }
                }
            }
        }

        val outBytes = ByteArray(numSamples * outChannels * 4)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            for (c in 0 until outChannels) {
                // Sign-extend if needed and normalize
                val raw = channelSamples[c][i]
                val shift = 32 - cfg.bitDepth
                val signed = (raw shl shift) shr shift
                outBuf.putFloat((signed / scaleDivisor).coerceIn(-1f, 1f))
            }
        }
        return outBytes
    }

    private fun dynDecomp(
        bits: BitReader,
        out: IntArray,
        numSamples: Int,
        bps: Int,
        mbInit: Int,
        kb: Int,
        pb: Int,
        maxRun: Int
    ) {
        var mb = mbInit
        var zmode = 0
        var c = 0
        while (c < numSamples) {
            var k = 31 - Integer.numberOfLeadingZeros((mb ushr 8) + 3)
            k = min(k, kb)
            val m = (1 shl k) - 1
            val n = dynGet32(bits, m, k, bps)
            val value = n + zmode
            zmode = 0

            val signedVal = ((value + 1) ushr 1) * if ((value and 1) != 0) 1 else -1
            // Wait: standard ALAC unsigned-to-signed mapping:
            // 0 -> 0, 1 -> -1, 2 -> 1, 3 -> -2, 4 -> 2 => (value ushr 1) xor -(value and 1)
            out[c++] = (value ushr 1) xor -(value and 1)

            mb = mb - ((pb * mb) ushr 9) + value * pb
            if (value > 65535) {
                mb = 0x4000
            }

            if (mb < 128 && c < numSamples) {
                val zf = (7 - Integer.numberOfLeadingZeros(mb)) + ((mb + 16) ushr 6)
                var kz = kb - zf.coerceAtLeast(0)
                if (kz < 0) kz = 0
                val kVal = 31 - Integer.numberOfLeadingZeros(mb) + 3
                val runK = min(kVal, kb)
                val runM = (1 shl runK) - 1
                var zeroRun = dynGet16(bits, runM, runK)
                if (zeroRun > 0) {
                    val actualRun = min(zeroRun, numSamples - c)
                    for (z in 0 until actualRun) out[c++] = 0
                }
                if (zeroRun < 65535) zmode = 1
                mb = 0
            }
        }
    }

    private fun dynGet16(bits: BitReader, m: Int, k: Int): Int {
        var q = 0
        while (q < 9 && bits.readBits(1) == 1) q++
        if (q == 9) return bits.readBits(16)
        if (k <= 0) return q
        val firstBits = bits.readBits(k - 1)
        val r = if (firstBits >= 1) {
            ((firstBits shl 1) or bits.readBits(1)) - 1
        } else {
            0
        }
        return q * m + r
    }

    private fun dynGet32(bits: BitReader, m: Int, k: Int, bps: Int): Int {
        var q = 0
        while (q < 9 && bits.readBits(1) == 1) q++
        if (q == 9) {
            return bits.readBits(bps)
        }
        if (k <= 0) return q
        val firstBits = bits.readBits(k - 1)
        val r = if (firstBits >= 1) {
            ((firstBits shl 1) or bits.readBits(1)) - 1
        } else {
            0
        }
        return q * m + r
    }

    private fun lpcPredict(
        residuals: IntArray,
        out: IntArray,
        numSamples: Int,
        coefs: IntArray,
        numCoefs: Int,
        denShift: Int,
        bps: Int
    ) {
        val shift = 32 - bps
        fun signExt(v: Int): Int = (v shl shift) shr shift

        out[0] = signExt(residuals[0])
        if (numSamples == 1) return

        if (numCoefs == 0) {
            for (i in 1 until numSamples) {
                out[i] = signExt(residuals[i])
            }
            return
        }

        if (numCoefs == 31) {
            for (i in 1 until numSamples) {
                out[i] = signExt(out[i - 1] + residuals[i])
            }
            return
        }

        val warmEnd = min(numCoefs + 1, numSamples)
        for (i in 1 until warmEnd) {
            out[i] = signExt(out[i - 1] + residuals[i])
        }

        val denHalf = 1 shl (denShift - 1)
        for (i in warmEnd until numSamples) {
            val base = out[i - numCoefs - 1]
            var sum = denHalf
            for (j in 0 until numCoefs) {
                sum += (out[i - 1 - j] - base) * coefs[j]
            }
            val pred = base + (sum shr denShift)
            var err = residuals[i]
            val valOut = signExt(pred + err)
            out[i] = valOut

            // Adaptive coefficient update
            if (err > 0) {
                var j = 0
                while (j < numCoefs && err > 0) {
                    val diff = base - out[i - numCoefs + j]
                    val sgn = diff.compareTo(0)
                    if (sgn != 0) {
                        val coefIdx = numCoefs - 1 - j
                        coefs[coefIdx] -= sgn
                        err -= ((diff * sgn) shr denShift) * (j + 1)
                    }
                    j++
                }
            } else if (err < 0) {
                var j = 0
                while (j < numCoefs && err < 0) {
                    val diff = base - out[i - numCoefs + j]
                    val sgn = diff.compareTo(0)
                    if (sgn != 0) {
                        val coefIdx = numCoefs - 1 - j
                        coefs[coefIdx] += sgn
                        err += ((diff * sgn) shr denShift) * (j + 1)
                    }
                    j++
                }
            }
        }
    }

    private fun unmixStereo(left: IntArray, right: IntArray, numSamples: Int, mixBits: Int, mixRes: Int) {
        if (mixRes == 0) return
        for (i in 0 until numSamples) {
            val mid = left[i]
            val side = right[i]
            val r = mid - ((side * mixRes) shr mixBits)
            val l = r + side
            left[i] = l
            right[i] = r
        }
    }

    private class AlacSeekMap(
        private val durationUs: Long,
        private val samples: List<SampleEntry>
    ) : SeekMap {
        override fun isSeekable(): Boolean = durationUs > 0L && samples.isNotEmpty()
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)
            val idx = samples.binarySearch { it.ptsUs.compareTo(timeUs) }
            val safeIdx = if (idx >= 0) idx else (-idx - 2).coerceIn(0, samples.lastIndex)
            val s = samples[safeIdx]
            return SeekMap.SeekPoints(SeekPoint(s.ptsUs, s.offset))
        }
    }
}
