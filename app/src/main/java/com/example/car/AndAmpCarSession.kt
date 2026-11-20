package com.example.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.example.SonoraApplication

@UnstableApi
class AndAmpCarSession : Session() {

    private var mainScreen: AndAmpCarMainScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as SonoraApplication
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.playerController.connect()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainScreen = null
            }
        })

        val screen = AndAmpCarMainScreen(carContext)
        mainScreen = screen
        if (ACTION_SHOW_MEDIA_PLAYBACK == intent.action) {
            screen.selectTab(AndAmpCarMainScreen.TAB_PLAYER)
        }
        return screen
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ACTION_SHOW_MEDIA_PLAYBACK == intent.action) {
            mainScreen?.selectTab(AndAmpCarMainScreen.TAB_PLAYER)
        }
    }

    companion object {
        const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
