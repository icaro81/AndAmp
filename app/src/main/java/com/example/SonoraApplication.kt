package com.example

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.example.audio.AudioOutputMonitor
import com.example.audio.GraphicEqAudioProcessor
import com.example.audio.PlayerController
import com.example.audio.SystemAudioEffectEq
import com.example.data.local.PreferencesStore
import com.example.data.repository.LanSmbScanner
import com.example.data.local.SonoraDatabase
import com.example.data.repository.LibraryRepository
import com.example.data.repository.SmbRepository
import com.example.data.repository.YouTubeRepository
import com.example.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.Provider
import java.security.Security

@UnstableApi
class SonoraApplication : Application() {

    companion object {
        init {
            runCatching {
                val provider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor()
                    .newInstance() as Provider
                Security.removeProvider(provider.name)
                Security.insertProviderAt(provider, 1)
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: SonoraDatabase by lazy { SonoraDatabase.build(this) }
    val preferencesStore: PreferencesStore by lazy { PreferencesStore(this) }
    val graphicEqAudioProcessor: GraphicEqAudioProcessor by lazy { GraphicEqAudioProcessor() }
    val systemAudioEffectEq: SystemAudioEffectEq by lazy { SystemAudioEffectEq() }
    val audioOutputMonitor: AudioOutputMonitor by lazy { AudioOutputMonitor(this) }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            context = this,
            trackDao = database.trackDao(),
            playlistDao = database.playlistDao(),
            eqPresetDao = database.eqPresetDao()
        )
    }

    val smbRepository: SmbRepository by lazy {
        SmbRepository(
            smbServerDao = database.smbServerDao(),
            trackDao = database.trackDao(),
            playlistDao = database.playlistDao()
        )
    }

    val lanSmbScanner: LanSmbScanner by lazy { LanSmbScanner(this) }

    val youTubeRepository: YouTubeRepository by lazy {
        YouTubeRepository(
            youTubePlaylistDao = database.youTubePlaylistDao()
        )
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(this)
    }

    val playerController: PlayerController by lazy {
        PlayerController(this, graphicEqAudioProcessor)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val eq = preferencesStore.eqSettingsFlow.first()
            graphicEqAudioProcessor.updateSettings(eq.enabled, eq.bandsDb, eq.preampDb)
            systemAudioEffectEq.updateSettings(
                enabled = eq.enabled,
                gainsDb = eq.bandsDb,
                preamp = eq.preampDb,
                shouldActivate = false
            )
            audioOutputMonitor.refresh(eq.bitPerfectUsbRequested)
        }
        appScope.launch {
            libraryRepository.ensureReferenceLibrarySeeded()
        }
    }
}
