package com.example.ui.components

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun YouTubeRemotePlayerBox(
    playlistId: String,
    initialSeekSeconds: Float = 0f,
    privacyEnhancedMode: Boolean = false,
    reloadNonce: Int = 0,
    onBindPlayer: (YouTubePlayer) -> Unit,
    onUnbindPlayer: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    onProgress: (Float, Float?) -> Unit,
    onVideoChanged: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(WinampColors.LcdBlack)
            .winampBevel(raised = false, strokeWidth = 1.4.dp)
            .testTag("youtube_remote_deck")
    ) {
        key(playlistId, privacyEnhancedMode, reloadNonce) {
            var playerViewRef: YouTubePlayerView? = null
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    runCatching {
                        CookieManager.getInstance().setAcceptCookie(true)
                    }
                    YouTubePlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        enableAutomaticInitialization = false
                        // Keep playing continuously when app is minimized or screen turns off
                        enableBackgroundPlayback(true)

                        val originUrl = if (privacyEnhancedMode) {
                            "https://www.youtube-nocookie.com"
                        } else {
                            "https://www.youtube.com"
                        }
                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(1)
                            .privacyEnhancedMode(privacyEnhancedMode)
                            .origin(originUrl)
                            .listType("playlist")
                            .list(playlistId)
                            .build()

                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                configureEmbeddedWebViews(this@apply)
                                onBindPlayer(youTubePlayer)
                                if (initialSeekSeconds > 1.5f) {
                                    youTubePlayer.seekTo(initialSeekSeconds)
                                }
                            }

                            override fun onStateChange(
                                youTubePlayer: YouTubePlayer,
                                state: PlayerConstants.PlayerState
                            ) {
                                when (state) {
                                    PlayerConstants.PlayerState.PLAYING -> onPlayingChanged(true)
                                    PlayerConstants.PlayerState.PAUSED,
                                    PlayerConstants.PlayerState.ENDED -> onPlayingChanged(false)
                                    else -> Unit
                                }
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                onProgress(second, null)
                            }

                            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                                onProgress(0f, duration)
                            }

                            override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) {
                                onVideoChanged(videoId)
                            }

                            override fun onError(
                                youTubePlayer: YouTubePlayer,
                                error: PlayerConstants.PlayerError
                            ) {
                                onError("Error YouTube (${error.name}). Prueba NOCOOKIE o CUENTA YT.")
                            }
                        }, options)

                        post { configureEmbeddedWebViews(this) }
                        playerViewRef = this
                    }
                }
            )

            DisposableEffect(playlistId, privacyEnhancedMode, reloadNonce) {
                onDispose {
                    runCatching { CookieManager.getInstance().flush() }
                    onUnbindPlayer()
                    playerViewRef?.release()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureEmbeddedWebViews(root: View) {
    if (root is WebView) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(root, true)
            root.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val cleanUa = userAgentString.replace("; wv)", ")").replace("; wv;", ";")
                if (cleanUa.isNotBlank()) {
                    userAgentString = cleanUa
                }
            }
        }
    } else if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            configureEmbeddedWebViews(root.getChildAt(i))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeAccountLoginDialog(
    onLoginCompletedAndReload: () -> Unit,
    onDismiss: () -> Unit
) {
    var progressPct by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    AlertDialog(
        onDismissRequest = {
            runCatching { CookieManager.getInstance().flush() }
            onDismiss()
        },
        containerColor = WinampColors.ChassisSurface,
        titleContentColor = WinampColors.TitleGold,
        textContentColor = WinampColors.TextPrimary,
        title = {
            Text(
                text = "CUENTA YOUTUBE (COOKIES)",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Si YouTube muestra el bloqueo anti-bot, inicia sesión aquí una sola vez. Las cookies quedan guardadas para el reproductor.",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = WinampColors.LcdGreen
                )
                if (progressPct in 1..99) {
                    LinearProgressIndicator(
                        progress = { progressPct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = WinampColors.LcdGreen,
                        trackColor = WinampColors.LcdBlack
                    )
                }
                Surface(
                    color = WinampColors.LcdBlack,
                    border = BorderStroke(1.dp, WinampColors.BevelHighlight),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    userAgentString = userAgentString
                                        .replace("; wv)", ")")
                                        .replace("; wv;", ";")
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progressPct = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (url != null) currentUrl = url
                                        runCatching { CookieManager.getInstance().flush() }
                                    }
                                }
                                loadUrl(currentUrl)
                                webViewRef = this
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            runCatching {
                                CookieManager.getInstance().removeAllCookies {
                                    CookieManager.getInstance().flush()
                                }
                                webViewRef?.clearCache(true)
                                webViewRef?.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
                            }
                        }
                    ) {
                        Text(
                            text = "LIMPIAR SESIÓN",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = WinampColors.LcdPeakRed
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (currentUrl.contains("youtube.com")) "Sesión lista" else "Inicia sesión",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = WinampColors.EqGoldBright
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching { CookieManager.getInstance().flush() }
                    onLoginCompletedAndReload()
                },
                modifier = Modifier.testTag("youtube_login_apply_btn")
            ) {
                Text(
                    text = "LISTO Y RECARGAR",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = WinampColors.LcdGreen
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                runCatching { CookieManager.getInstance().flush() }
                onDismiss()
            }) {
                Text("Cerrar", color = WinampColors.TextSecondary)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                CookieManager.getInstance().flush()
                webViewRef?.destroy()
            }
        }
    }
}
