package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom Media3 Extractor for Direct Stream Digital (.dsf and .dff / DSDIFF) audio files.
 * Decimates 1-bit PDM streams (DSD64 / DSD128 / DSD256) in real-time using an 8-byte (64-tap)
 * Windowed-Sinc FIR low-pass filter table into 88.2 kHz 32-bit Float Stereo PCM.
 */
@UnstableApi
class DsdExtractor : Extractor {

    private enum class Container { NONE, DSF, DFF }

    private var container = Container.NONE
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var sampleRateDsd: Int = 2_822_400
    private var channels: Int = 2
    private var blockSizePerChannel: Int = 4096
    private var lsbFirst: Boolean = true
    private var dataStartOffset: Long = 0L
    private var dataSizeBytes: Long = 0L
    private var totalDsdSamplesPerChannel: Long = 0L

    private var pcmSampleRate: Int = 88_200
    private var decimationRatio: Int = 32
    private var bytesPerPcmFrame: Int = 4 // decimationRatio / 8
    private var totalDurationUs: Long = C.TIME_UNSET

    private var currentDataByteOffset: Long = 0L
    private var currentPcmSampleIndex: Long = 0L
    private var headersParsed = false

    // Ring history per channel (last 8 DSD bytes per channel for 64-tap FIR convolution)
    private var byteHistory = Array(2) { IntArray(8) }
    private var historyPos = 0

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(16)
        if (!input.peekFully(header, 0, 16, true)) return false
        val tag = String(header, 0, 4, Charsets.US_ASCII)
        if (tag == "DSD ") return true
        if (tag == "FRM8") {
            val formType = String(header, 12, 4, Charsets.US_ASCII)
            return formType == "DSD "
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headersParsed) {
            if (!parseHeaders(input)) return Extractor.RESULT_END_OF_INPUT
            headersParsed = true

            decimationRatio = (sampleRateDsd / 88_200).coerceAtLeast(8)
            // Keep clean multiples of 8 (DSD64 -> 32, DSD128 -> 64, DSD256 -> 128)
            bytesPerPcmFrame = (decimationRatio / 8).coerceAtLeast(1)
            pcmSampleRate = sampleRateDsd / (bytesPerPcmFrame * 8)

            totalDurationUs = if (totalDsdSamplesPerChannel > 0L) {
                (totalDsdSamplesPerChannel * 1_000_000L) / sampleRateDsd
            } else {
                C.TIME_UNSET
            }

            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels.coerceAtMost(2))
                .setSampleRate(pcmSampleRate)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            trackOutput?.format(format)

            val outputSeekMap = DsdSeekMap(
                durationUs = totalDurationUs,
                dataStart = dataStartOffset,
                dataSize = dataSizeBytes,
                channels = channels,
                blockSize = if (container == Container.DSF) blockSizePerChannel else bytesPerPcmFrame
            )
            extractorOutput?.seekMap(outputSeekMap)

            if (input.position != dataStartOffset) {
                seekPosition.position = dataStartOffset
                return Extractor.RESULT_SEEK
            }
        }

        return if (container == Container.DSF) {
            readDsfBlockClean(input)
        } else {
            readDffChunk(input)
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        for (ch in byteHistory.indices) byteHistory[ch].fill(0x55) // neutral alternating pattern
        chRingIndices.fill(0)

        if (!headersParsed || totalDurationUs <= 0L) {
            currentDataByteOffset = 0L
            currentPcmSampleIndex = 0L
            return
        }

        val clampedTimeUs = timeUs.coerceIn(0L, totalDurationUs)
        val targetDsdSample = (clampedTimeUs * sampleRateDsd) / 1_000_000L
        val targetPcmSample = targetDsdSample / (bytesPerPcmFrame * 8)

        if (container == Container.DSF) {
            val samplesPerBlock = blockSizePerChannel * 8L
            val blockIndex = targetDsdSample / samplesPerBlock
            val stride = blockSizePerChannel.toLong() * channels
            currentDataByteOffset = (blockIndex * stride).coerceIn(0L, dataSizeBytes)
            currentPcmSampleIndex = (blockIndex * samplesPerBlock) / (bytesPerPcmFrame * 8)
        } else {
            val frameStride = bytesPerPcmFrame.toLong() * channels
            currentDataByteOffset = (targetPcmSample * frameStride).coerceIn(0L, dataSizeBytes)
            currentPcmSampleIndex = targetPcmSample
        }
    }

    override fun release() = Unit

    private fun parseHeaders(input: ExtractorInput): Boolean {
        val peek = ByteArray(16)
        input.readFully(peek, 0, 16)
        val magic = String(peek, 0, 4, Charsets.US_ASCII)

        return if (magic == "DSD ") {
            container = Container.DSF
            parseDsfHeader(input, peek)
        } else if (magic == "FRM8" && String(peek, 12, 4, Charsets.US_ASCII) == "DSD ") {
            container = Container.DFF
            parseDffHeader(input)
        } else {
            false
        }
    }

    private fun parseDsfHeader(input: ExtractorInput, first16: ByteArray): Boolean {
        // DSD chunk is 28 bytes total; we already read 16 bytes
        val restDsd = ByteArray(12)
        input.readFully(restDsd, 0, 12)
        val dsdChunk = ByteBuffer.wrap(first16 + restDsd).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSize = dsdChunk.getLong(4)
        if (chunkSize > 28) {
            input.skipFully((chunkSize - 28).toInt())
        }

        // Read fmt chunk (52 bytes minimum)
        val fmtHeader = ByteArray(52)
        input.readFully(fmtHeader, 0, 52)
        val fmtBuf = ByteBuffer.wrap(fmtHeader).order(ByteOrder.LITTLE_ENDIAN)
        val fmtTag = String(fmtHeader, 0, 4, Charsets.US_ASCII)
        if (fmtTag != "fmt ") return false
        val fmtSize = fmtBuf.getLong(4)
        channels = fmtBuf.getInt(24).coerceIn(1, 2)
        sampleRateDsd = fmtBuf.getInt(28).coerceAtLeast(2_822_400)
        val bps = fmtBuf.getInt(32)
        totalDsdSamplesPerChannel = fmtBuf.getLong(36)
        blockSizePerChannel = fmtBuf.getInt(44).coerceAtLeast(1024)
        lsbFirst = (bps == 1)

        if (fmtSize > 52) {
            input.skipFully((fmtSize - 52).toInt())
        }

        // Read data chunk header (12 bytes)
        val dataHeader = ByteArray(12)
        input.readFully(dataHeader, 0, 12)
        val dataBuf = ByteBuffer.wrap(dataHeader).order(ByteOrder.LITTLE_ENDIAN)
        val dataTag = String(dataHeader, 0, 4, Charsets.US_ASCII)
        if (dataTag != "data") return false
        val dataChunkSize = dataBuf.getLong(4)
        dataStartOffset = input.position
        dataSizeBytes = (dataChunkSize - 12L).coerceAtLeast(0L)
        byteHistory = Array(channels) { IntArray(8) { 0x55 } }
        chRingIndices = IntArray(channels)
        return true
    }

    private fun parseDffHeader(input: ExtractorInput): Boolean {
        // Already read 16 bytes ("FRM8" + 8-byte size + "DSD ")
        var foundProp = false
        var foundData = false
        val header8 = ByteArray(12)
        while (!foundData) {
            if (!input.readFully(header8, 0, 12, true)) break
            val id = String(header8, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header8, 4, 8).order(ByteOrder.BIG_ENDIAN).long
            when (id) {
                "PROP" -> {
                    val propData = ByteArray(size.toInt().coerceAtMost(8192))
                    input.readFully(propData, 0, propData.size)
                    if (size > propData.size) input.skipFully((size - propData.size).toInt())
                    parseDffProp(propData)
                    foundProp = true
                }
                "DSD " -> {
                    dataStartOffset = input.position
                    dataSizeBytes = size
                    lsbFirst = false // DSDIFF is MSB-first
                    if (channels > 0 && sampleRateDsd > 0) {
                        totalDsdSamplesPerChannel = (dataSizeBytes * 8L) / channels
                    }
                    foundData = true
                }
                else -> {
                    val paddedSize = size + (size and 1L)
                    input.skipFully(paddedSize.toInt())
                }
            }
        }
        byteHistory = Array(channels.coerceIn(1, 2)) { IntArray(8) { 0x55 } }
        chRingIndices = IntArray(channels.coerceIn(1, 2))
        return foundProp && foundData
    }

    private fun parseDffProp(propData: ByteArray) {
        // First 4 bytes are "SND "
        var pos = 4
        val buf = ByteBuffer.wrap(propData).order(ByteOrder.BIG_ENDIAN)
        while (pos + 12 <= propData.size) {
            val id = String(propData, pos, 4, Charsets.US_ASCII)
            val sz = buf.getLong(pos + 4).toInt()
            val bodyStart = pos + 12
            if (bodyStart + sz > propData.size) break
            when (id) {
                "FS  " -> if (sz >= 4) sampleRateDsd = buf.getInt(bodyStart)
                "CHNL" -> if (sz >= 2) channels = buf.getShort(bodyStart).toInt().coerceIn(1, 2)
            }
            pos = bodyStart + sz + (sz and 1)
        }
    }

    private var chRingIndices = IntArray(2)

    private fun readDsfBlockClean(input: ExtractorInput): Int {
        val remaining = dataSizeBytes - currentDataByteOffset
        val stride = blockSizePerChannel * channels
        if (remaining < stride) return Extractor.RESULT_END_OF_INPUT

        val blockBuf = ByteArray(stride)
        if (!input.readFully(blockBuf, 0, stride, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }
        currentDataByteOffset += stride

        val step = bytesPerPcmFrame
        val outChannels = channels.coerceAtMost(2)
        val outFrames = blockSizePerChannel / step
        val pcmBytes = ByteArray(outFrames * outChannels * 4)
        val pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (f in 0 until outFrames) {
            val baseByte = f * step
            for (ch in 0 until outChannels) {
                val chOffset = ch * blockSizePerChannel + baseByte
                var idx = chRingIndices[ch]
                val ring = byteHistory[ch]
                for (b in 0 until step) {
                    val raw = blockBuf[chOffset + b].toInt() and 0xFF
                    ring[idx and 7] = if (lsbFirst) raw else BIT_REVERSE[raw]
                    idx++
                }
                chRingIndices[ch] = idx
                val sample = evalFir64(ring, idx)
                pcmBuf.putFloat(sample)
            }
        }
        return emitPcmAndAdvance(pcmBytes, outFrames)
    }

    private fun readDffChunk(input: ExtractorInput): Int {
        val remaining = dataSizeBytes - currentDataByteOffset
        val outChannels = channels.coerceAtMost(2)
        val step = bytesPerPcmFrame
        val framesToRead = min(1024L, remaining / (step * channels)).toInt()
        if (framesToRead <= 0) return Extractor.RESULT_END_OF_INPUT

        val chunkBytes = ByteArray(framesToRead * step * channels)
        if (!input.readFully(chunkBytes, 0, chunkBytes.size, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }
        currentDataByteOffset += chunkBytes.size

        val pcmBytes = ByteArray(framesToRead * outChannels * 4)
        val pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        for (f in 0 until framesToRead) {
            for (b in 0 until step) {
                for (ch in 0 until channels) {
                    val raw = chunkBytes[pos++].toInt() and 0xFF
                    if (ch < outChannels) {
                        val idx = chRingIndices[ch]
                        byteHistory[ch][idx and 7] = BIT_REVERSE[raw] // DFF is MSB-first -> reverse to LSB-first
                        chRingIndices[ch] = idx + 1
                    }
                }
            }
            for (ch in 0 until outChannels) {
                val sample = evalFir64(byteHistory[ch], chRingIndices[ch])
                pcmBuf.putFloat(sample)
            }
        }
        return emitPcmAndAdvance(pcmBytes, framesToRead)
    }

    private fun emitPcmAndAdvance(pcmBytes: ByteArray, outFrames: Int): Int {
        val ptsUs = (currentPcmSampleIndex * 1_000_000L) / pcmSampleRate
        currentPcmSampleIndex += outFrames
        val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        out.sampleData(androidx.media3.common.util.ParsableByteArray(pcmBytes), pcmBytes.size)
        out.sampleMetadata(ptsUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes.size, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    private fun convolveDsd64(ring: IntArray, step: Int): Float = evalFir64(ring, historyPos + step)

    private fun evalFir64(ring: IntArray, writeCursor: Int): Float {
        var acc = 0f
        // Oldest byte is at writeCursor - 8, newest at writeCursor - 1
        for (seg in 0 until 8) {
            val bVal = ring[(writeCursor - 8 + seg) and 7]
            acc += FIR_BYTE_LUT[seg][bVal]
        }
        return (acc * 1.45f).coerceIn(-1f, 1f)
    }

    private class DsdSeekMap(
        private val durationUs: Long,
        private val dataStart: Long,
        private val dataSize: Long,
        private val channels: Int,
        private val blockSize: Int
    ) : SeekMap {
        override fun isSeekable(): Boolean = durationUs > 0L && dataSize > 0L
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)
            val clamped = timeUs.coerceIn(0L, durationUs)
            val stride = (blockSize * channels).toLong().coerceAtLeast(1L)
            val totalBlocks = dataSize / stride
            val targetBlock = ((clamped * totalBlocks) / durationUs).coerceIn(0L, (totalBlocks - 1).coerceAtLeast(0L))
            val bytePos = dataStart + targetBlock * stride
            val actualTime = if (totalBlocks > 0) (targetBlock * durationUs) / totalBlocks else 0L
            return SeekMap.SeekPoints(SeekPoint(actualTime, bytePos))
        }
    }

    companion object {
        private val BIT_REVERSE = IntArray(256) { v ->
            var r = 0
            for (i in 0 until 8) {
                if (((v shr i) and 1) != 0) r = r or (1 shl (7 - i))
            }
            r
        }

        // 8-segment (64-tap) Blackman-Harris windowed sinc low-pass filter lookup table for LSB-first bytes
        private val FIR_BYTE_LUT: Array<FloatArray> = buildFirLut()

        private fun buildFirLut(): Array<FloatArray> {
            val taps = 64
            val coeffs = DoubleArray(taps)
            val fcNorm = 0.012 // ~33 kHz cutoff relative to 2.8224 MHz DSD64
            val mid = (taps - 1) / 2.0
            var sum = 0.0
            for (n in 0 until taps) {
                val x = n - mid
                val sinc = if (x == 0.0) 2.0 * fcNorm else sin(2.0 * PI * fcNorm * x) / (PI * x)
                val w = 0.35875 -
                    0.48829 * kotlin.math.cos(2.0 * PI * n / (taps - 1)) +
                    0.14128 * kotlin.math.cos(4.0 * PI * n / (taps - 1)) -
                    0.01168 * kotlin.math.cos(6.0 * PI * n / (taps - 1))
                coeffs[n] = sinc * w
                sum += coeffs[n]
            }
            for (n in 0 until taps) coeffs[n] /= sum

            return Array(8) { seg ->
                val baseTap = seg * 8
                FloatArray(256) { byteVal ->
                    var s = 0.0
                    for (bit in 0 until 8) {
                        val level = if (((byteVal shr bit) and 1) != 0) 1.0 else -1.0
                        s += level * coeffs[baseTap + bit]
                    }
                    s.toFloat()
                }
            }
        }
    }
}
