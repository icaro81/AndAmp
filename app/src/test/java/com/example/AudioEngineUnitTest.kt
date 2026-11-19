package com.example

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.util.ParsableByteArray
import com.example.audio.AlacExtractor
import com.example.audio.DsdExtractor
import com.example.audio.GraphicEqAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioEngineUnitTest {

    private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, toRead)
            pos += toRead
            peekPos = pos
            return toRead
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, pos, target, offset, length)
            pos += length
            peekPos = pos
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val toSkip = minOf(length, data.size - pos)
            pos += toSkip
            peekPos = pos
            return toSkip
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            pos += length
            peekPos = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peekPos >= data.size) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length, data.size - peekPos)
            System.arraycopy(data, peekPos, target, offset, toRead)
            peekPos += toRead
            return toRead
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, peekPos, target, offset, length)
            peekPos += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPos = pos
        }

        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable?> setRetryPosition(position: Long, e: E) {
            pos = position.toInt()
            peekPos = pos
        }

        fun seekTo(newPos: Long) {
            pos = newPos.toInt().coerceIn(0, data.size)
            peekPos = pos
        }
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        val pcmChunks = mutableListOf<ByteArray>()

        private val track = object : TrackOutput {
            override fun format(format: Format) {
                this@CapturingExtractorOutput.format = format
            }

            override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val slice = ByteArray(length)
                data.readBytes(slice, 0, length)
                pcmChunks.add(slice)
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }

        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun resolveSampleFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets/samples/$name"),
            File("app/src/main/assets/samples/$name")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Sample file $name not found in working dir ${File(".").absolutePath}")
    }

    @Test
    fun dsdExtractorDecodesGenuineDsfToHiResFloatPcm() {
        val bytes = resolveSampleFile("prism_dsd64.dsf").readBytes()
        val input = ByteArrayExtractorInput(bytes)
        val extractor = DsdExtractor()

        assertTrue("DSF should be sniffed", extractor.sniff(input))
        input.resetPeekPosition()

        val output = CapturingExtractorOutput()
        extractor.init(output)

        val seekPos = PositionHolder()
        var iterations = 0
        while (iterations < 50) {
            val res = extractor.read(input, seekPos)
            if (res == Extractor.RESULT_SEEK) {
                input.seekTo(seekPos.position)
            } else if (res == Extractor.RESULT_END_OF_INPUT) {
                break
            }
            iterations++
        }

        val fmt = output.format
        assertNotNull("DSD format should be emitted", fmt)
        assertEquals(88_200, fmt!!.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, fmt.pcmEncoding)
        assertTrue("SeekMap should be seekable", output.seekMap?.isSeekable == true)
        assertTrue("Should decode non-empty PCM chunks", output.pcmChunks.isNotEmpty())

        val firstBuf = ByteBuffer.wrap(output.pcmChunks.first()).order(ByteOrder.LITTLE_ENDIAN)
        var maxAmp = 0f
        while (firstBuf.remaining() >= 4) {
            val s = firstBuf.getFloat()
            assertFalse("Sample must not be NaN", s.isNaN())
            maxAmp = maxOf(maxAmp, abs(s))
        }
        assertTrue("Decoded DSD audio should have non-zero signal", maxAmp > 0.01f)
    }

    @Test
    fun alacExtractorDecodesGenuineM4aAlacToFloatPcm() {
        val bytes = resolveSampleFile("amber_48k_alac.m4a").readBytes()
        val input = ByteArrayExtractorInput(bytes)
        val extractor = AlacExtractor(forceForM4aHint = true)

        assertTrue("M4A ALAC should be sniffed when hinted", extractor.sniff(input))
        input.resetPeekPosition()

        val output = CapturingExtractorOutput()
        extractor.init(output)

        val seekPos = PositionHolder()
        var iterations = 0
        while (iterations < 30) {
            val res = extractor.read(input, seekPos)
            if (res == Extractor.RESULT_SEEK) {
                input.seekTo(seekPos.position)
            } else if (res == Extractor.RESULT_END_OF_INPUT) {
                break
            }
            iterations++
        }

        val fmt = output.format
        assertNotNull("ALAC format should be emitted", fmt)
        assertEquals(48_000, fmt!!.sampleRate)
        assertEquals(2, fmt.channelCount)
        assertEquals(C.ENCODING_PCM_FLOAT, fmt.pcmEncoding)
        assertTrue("SeekMap should be seekable", output.seekMap?.isSeekable == true)
        assertTrue("Should decode ALAC frames", output.pcmChunks.isNotEmpty())

        val combined = output.pcmChunks.drop(2).firstOrNull() ?: output.pcmChunks.first()
        val bb = ByteBuffer.wrap(combined).order(ByteOrder.LITTLE_ENDIAN)
        var maxAmp = 0f
        while (bb.remaining() >= 4) {
            val s = bb.getFloat()
            assertFalse("Sample must not be NaN", s.isNaN())
            maxAmp = maxOf(maxAmp, abs(s))
        }
        assertTrue("Decoded ALAC audio should have non-zero signal", maxAmp > 0.005f)
    }

    @Test
    fun graphicEq10BandProcessorFiltersAndComputesSpectrum() {
        val processor = GraphicEqAudioProcessor()
        processor.updateSettings(
            enabled = true,
            gainsDb = listOf(6f, 4f, 0f, -2f, 0f, 3f, 0f, 2f, 4f, 5f),
            preampDb = -2f
        )
        val outFmt = processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(48_000, outFmt.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, outFmt.encoding)
        processor.flush()

        val frameCount = 1024
        val inBuf = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            val sample = (kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / 48_000.0) * 0.4).toFloat()
            inBuf.putFloat(sample)
            inBuf.putFloat(sample)
        }
        inBuf.flip()

        processor.queueInput(inBuf)
        val outBuf = processor.output
        assertEquals(frameCount * 2 * 4, outBuf.remaining())

        var maxOut = 0f
        while (outBuf.remaining() >= 4) {
            val s = outBuf.getFloat()
            assertFalse(s.isNaN())
            maxOut = maxOf(maxOut, abs(s))
        }
        assertTrue("EQ output should contain filtered signal", maxOut > 0.1f)
        val spectrum = processor.spectrumFlow.value
        assertEquals(24, spectrum.size)
        assertTrue("Spectrum analyzer should detect 1kHz energy", spectrum.maxOrNull()!! > 0.2f)
    }
}
