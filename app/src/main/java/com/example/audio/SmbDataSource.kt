package com.example.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.example.data.repository.SmbOpenHandle
import com.example.data.repository.SmbRepository
import java.io.IOException

@UnstableApi
class SmbAwareDataSourceFactory(
    context: Context,
    private val smbRepository: SmbRepository
) : DataSource.Factory {
    private val defaultFactory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource {
        val delegate = defaultFactory.createDataSource()
        val smbSource = SmbDataSource(smbRepository)
        return object : DataSource {
            private var activeSource: DataSource? = null

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                delegate.addTransferListener(transferListener)
                smbSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val chosen = if (dataSpec.uri.scheme == "smb") smbSource else delegate
                activeSource = chosen
                return chosen.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return activeSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            }

            override fun getUri(): Uri? = activeSource?.uri

            override fun close() {
                activeSource?.close()
                activeSource = null
            }
        }
    }
}

@UnstableApi
class SmbDataSource(
    private val smbRepository: SmbRepository
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var openHandle: SmbOpenHandle? = null
    private var fileOffset: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)

        val handle = try {
            smbRepository.openSmbConnectionHandle(dataSpec.uri)
                ?: throw IOException("No se pudo resolver el servidor SMB para ${dataSpec.uri}")
        } catch (e: Exception) {
            throw IOException("Error conectando al servidor NAS: ${e.message}", e)
        }

        openHandle = handle
        val totalSize = handle.fileSize
        fileOffset = dataSpec.position

        if (totalSize in 0 until fileOffset) {
            close()
            throw IOException("Posición fuera de rango en archivo SMB")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalSize >= 0L) {
            (totalSize - fileOffset).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val handle = openHandle ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        val readCount = try {
            handle.file.read(buffer, fileOffset, offset, toRead)
        } catch (e: Exception) {
            throw IOException("Error leyendo flujo SMB: ${e.message}", e)
        }

        if (readCount <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        fileOffset += readCount
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= readCount
        }
        bytesTransferred(readCount)
        return readCount
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        try {
            openHandle?.closeQuietly()
        } finally {
            openHandle = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
