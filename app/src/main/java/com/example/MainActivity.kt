package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.common.util.UnstableApi
import com.example.ui.SonoraAppShell
import com.example.ui.viewmodel.SonoraViewModel

@UnstableApi
class MainActivity : ComponentActivity() {

    private val sonoraApp: SonoraApplication
        get() = application as SonoraApplication

    private val viewModel: SonoraViewModel by viewModels {
        SonoraViewModel.Factory(
            repository = sonoraApp.libraryRepository,
            smbRepository = sonoraApp.smbRepository,
            youTubeRepository = sonoraApp.youTubeRepository,
            preferencesStore = sonoraApp.preferencesStore,
            playerController = sonoraApp.playerController,
            eqProcessor = sonoraApp.graphicEqAudioProcessor,
            outputMonitor = sonoraApp.audioOutputMonitor,
            appUpdateManager = sonoraApp.appUpdateManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonoraAppShell(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        sonoraApp.playerController.connect()
    }

    override fun onResume() {
        super.onResume()
        viewModel.retryPendingApkInstallIfAllowed()
    }

    override fun onStop() {
        super.onStop()
        sonoraApp.playerController.disconnect()
    }
}
