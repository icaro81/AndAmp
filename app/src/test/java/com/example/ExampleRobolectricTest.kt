package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioOutputInfo
import com.example.audio.PlaybackUiState
import com.example.data.local.EqSettingsState
import com.example.data.repository.YouTubeRepository
import com.example.domain.model.AudioFormatBadge
import com.example.domain.model.PlaybackSourceMode
import com.example.domain.model.Track
import com.example.domain.model.YouTubePlaybackState
import com.example.ui.screens.WinampRackScreen
import com.example.ui.theme.SonoraTheme
import com.example.ui.viewmodel.LibraryFormatFilter
import com.example.ui.viewmodel.SmbBrowserUiState
import com.example.ui.viewmodel.YouTubeSearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AndAmp", appName)
  }

  @Test
  fun `andamp droid intro wav asset is valid 7s 24bit 48kHz stereo`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val introFile = java.io.File(context.cacheDir, "intro_test.wav")
    context.assets.open("samples/andamp_droid_intro.wav").use { input ->
      introFile.outputStream().use { output -> input.copyTo(output) }
    }
    val track = com.example.data.repository.AudioMetadataExtractor.extractTrackMetadata(
      context,
      android.net.Uri.fromFile(introFile)
    )
    assertEquals(AudioFormatBadge.WAV, track?.format)
    assertEquals(48_000, track?.sampleRateHz)
    assertEquals(24, track?.bitDepth)
    assertEquals(2, track?.channels)
    assertEquals(7_000L, track?.durationMs)
  }

  @Test
  fun `ota update semver and github release parser detects newer apk`() {
    val json = """
      {
        "tag_name": "v1.0.2",
        "name": "AndAmp v1.0.2",
        "body": "Mejoras de ecualizador y NAS",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "AndAmp-v1.0.2.apk",
            "size": 18500000,
            "browser_download_url": "https://github.com/test/AndAmp/releases/download/v1.0.2/AndAmp-v1.0.2.apk"
          }
        ]
      }
    """.trimIndent()
    val info = com.example.update.AppUpdateManager.parseLatestReleaseJson(json, "1.0.0")
    assertEquals("1.0.2", info?.version)
    assertEquals(18500000L, info?.fileSizeBytes)
    assertNull(com.example.update.AppUpdateManager.parseLatestReleaseJson(json, "1.0.2"))
  }

  @Test
  fun `youtube playlist id extraction handles urls and raw ids`() {
    assertEquals(
      "PL1234567890ABCDEF",
      YouTubeRepository.extractPlaylistId("https://www.youtube.com/playlist?list=PL1234567890ABCDEF")
    )
    assertEquals(
      "OLAK5uy_n0987654321",
      YouTubeRepository.extractPlaylistId("https://music.youtube.com/watch?v=abc&list=OLAK5uy_n0987654321")
    )
    assertEquals(
      "PLabcdef123456",
      YouTubeRepository.extractPlaylistId("PLabcdef123456")
    )
    assertNull(YouTubeRepository.extractPlaylistId("jazz hi-fi chill"))
  }

  @Test
  fun `embedded artwork check returns null cleanly when absent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sampleDir = java.io.File(context.cacheDir, "art_test").apply { mkdirs() }
    val flacFile = java.io.File(sampleDir, "test.flac")
    context.assets.open("samples/nocturne_96k_24b.flac").use { input ->
      flacFile.outputStream().use { output -> input.copyTo(output) }
    }
    val alacFile = java.io.File(sampleDir, "test.m4a")
    context.assets.open("samples/amber_48k_alac.m4a").use { input ->
      alacFile.outputStream().use { output -> input.copyTo(output) }
    }
    val flacArt = com.example.data.repository.AudioMetadataExtractor.loadEmbeddedArtworkBytes(
      context,
      android.net.Uri.fromFile(flacFile).toString()
    )
    val alacArt = com.example.data.repository.AudioMetadataExtractor.loadEmbeddedArtworkBytes(
      context,
      android.net.Uri.fromFile(alacFile).toString()
    )
    assertNull(flacArt)
    assertNull(alacArt)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
  fun `winamp rack displays all three stacked modules and hires lcd telemetry`() {
    val sampleTrack = Track(
      id = 1L,
      uriString = "asset:///samples/nocturne_96k_24b.flac",
      title = "Nocturne 96k",
      artist = "Sonora Reference",
      album = "Master Studio",
      durationMs = 14_000L,
      format = AudioFormatBadge.FLAC,
      sampleRateHz = 96_000,
      bitDepth = 24,
      bitrateKbps = 2400
    )

    composeTestRule.setContent {
      SonoraTheme {
        WinampRackScreen(
          playbackState = PlaybackUiState(currentTrack = sampleTrack, positionMs = 4_000L, durationMs = 14_000L),
          playbackSourceMode = PlaybackSourceMode.MEDIA3_AUDIO,
          youTubeState = YouTubePlaybackState(),
          spectrumBars = FloatArray(24),
          outputInfo = AudioOutputInfo(),
          eqSettings = EqSettingsState(),
          customEqPresets = emptyList(),
          allTracks = listOf(sampleTrack),
          displayedTracks = listOf(sampleTrack),
          playlists = emptyList(),
          activePlaylist = null,
          selectedTrackId = null,
          searchQuery = "",
          selectedFilter = LibraryFormatFilter.ALL,
          isImporting = false,
          smbServers = emptyList(),
          smbBrowserState = SmbBrowserUiState(),
          youTubeSearchState = YouTubeSearchUiState(),
          recentYouTubePlaylists = emptyList(),
          onPlayExplicit = {},
          onPauseExplicit = {},
          onStopPlayback = {},
          onSkipPrevious = {},
          onSkipNext = {},
          onSeekTo = {},
          onToggleShuffle = {},
          onCycleRepeat = {},
          onEqEnabledChange = {},
          onEqAutoToggle = {},
          onPreampChange = {},
          onBandChange = { _, _ -> },
          onApplyPreset = {},
          onResetEqFlat = {},
          onSaveCustomPreset = {},
          onDeleteCustomPreset = {},
          onPlayTrack = {},
          onSelectTrack = {},
          onRemoveSelectedOrActive = {},
          onSelectPlaylist = {},
          onCreatePlaylist = { _, _ -> },
          onRenamePlaylist = { _, _, _ -> },
          onDeletePlaylist = {},
          onAddTrackToPlaylist = { _, _ -> },
          onToggleFavorite = {},
          onToggleBitPerfectUsb = {},
          onSearchQueryChange = {},
          onSelectFilter = {},
          onPlayAll = {},
          onMoveSelectedInPlaylist = {},
          onLaunchFilePicker = {},
          onLaunchFolderPicker = {},
          onSaveSmbServer = {},
          onDeleteSmbServer = {},
          onOpenSmbServer = {},
          onNavigateSmbEntry = {},
          onNavigateSmbUp = {},
          onCloseSmbBrowser = {},
          onImportAllSmbInFolder = {},
          onYouTubeQueryChange = {},
          onSearchYouTube = {},
          onPlayYouTubePlaylist = {},
          onDeleteRecentYouTube = {},
          onExitYouTube = {},
          onBindYouTubePlayer = {},
          onUnbindYouTubePlayer = {},
          onYouTubeStateChanged = {},
          onYouTubeProgress = { _, _ -> },
          onYouTubeVideoChanged = {},
          onYouTubeError = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ANDAMP HI-FI").assertIsDisplayed()
    composeTestRule.onNodeWithText("ANDAMP EQUALIZER").assertIsDisplayed()
    composeTestRule.onNodeWithText("ANDAMP PLAYLIST").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("lcd_format_badge").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lcd_bit_depth").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lcd_sample_rate").assertIsDisplayed()
    composeTestRule.onNodeWithTag("eq_presets_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_playlist_add").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_playlist_misc").performScrollTo().assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w915dp-h411dp-land-xxhdpi")
  fun `landscape orientation adapts to three swipeable module tabs`() {
    val sampleTrack = Track(
      id = 1L,
      uriString = "asset:///samples/nocturne_96k_24b.flac",
      title = "Nocturne 96k",
      artist = "Sonora Reference",
      album = "Master Studio",
      durationMs = 14_000L,
      format = AudioFormatBadge.FLAC,
      sampleRateHz = 96_000,
      bitDepth = 24,
      bitrateKbps = 2400
    )

    composeTestRule.setContent {
      SonoraTheme {
        WinampRackScreen(
          playbackState = PlaybackUiState(currentTrack = sampleTrack, positionMs = 4_000L, durationMs = 14_000L),
          playbackSourceMode = PlaybackSourceMode.MEDIA3_AUDIO,
          youTubeState = YouTubePlaybackState(),
          spectrumBars = FloatArray(24),
          outputInfo = AudioOutputInfo(),
          eqSettings = EqSettingsState(),
          customEqPresets = emptyList(),
          allTracks = listOf(sampleTrack),
          displayedTracks = listOf(sampleTrack),
          playlists = emptyList(),
          activePlaylist = null,
          selectedTrackId = null,
          searchQuery = "",
          selectedFilter = LibraryFormatFilter.ALL,
          isImporting = false,
          smbServers = emptyList(),
          smbBrowserState = SmbBrowserUiState(),
          youTubeSearchState = YouTubeSearchUiState(),
          recentYouTubePlaylists = emptyList(),
          onPlayExplicit = {},
          onPauseExplicit = {},
          onStopPlayback = {},
          onSkipPrevious = {},
          onSkipNext = {},
          onSeekTo = {},
          onToggleShuffle = {},
          onCycleRepeat = {},
          onEqEnabledChange = {},
          onEqAutoToggle = {},
          onPreampChange = {},
          onBandChange = { _, _ -> },
          onApplyPreset = {},
          onResetEqFlat = {},
          onSaveCustomPreset = {},
          onDeleteCustomPreset = {},
          onPlayTrack = {},
          onSelectTrack = {},
          onRemoveSelectedOrActive = {},
          onSelectPlaylist = {},
          onCreatePlaylist = { _, _ -> },
          onRenamePlaylist = { _, _, _ -> },
          onDeletePlaylist = {},
          onAddTrackToPlaylist = { _, _ -> },
          onToggleFavorite = {},
          onToggleBitPerfectUsb = {},
          onSearchQueryChange = {},
          onSelectFilter = {},
          onPlayAll = {},
          onMoveSelectedInPlaylist = {},
          onLaunchFilePicker = {},
          onLaunchFolderPicker = {},
          onSaveSmbServer = {},
          onDeleteSmbServer = {},
          onOpenSmbServer = {},
          onNavigateSmbEntry = {},
          onNavigateSmbUp = {},
          onCloseSmbBrowser = {},
          onImportAllSmbInFolder = {},
          onYouTubeQueryChange = {},
          onSearchYouTube = {},
          onPlayYouTubePlaylist = {},
          onDeleteRecentYouTube = {},
          onExitYouTube = {},
          onBindYouTubePlayer = {},
          onUnbindYouTubePlayer = {},
          onYouTubeStateChanged = {},
          onYouTubeProgress = { _, _ -> },
          onYouTubeVideoChanged = {},
          onYouTubeError = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("landscape_tab_0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("landscape_tab_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("landscape_tab_2").assertIsDisplayed()
    composeTestRule.onNodeWithText("ANDAMP HI-FI").assertIsDisplayed()

    composeTestRule.onNodeWithTag("landscape_tab_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ANDAMP EQUALIZER").assertIsDisplayed()

    composeTestRule.onNodeWithTag("landscape_tab_2").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("ANDAMP PLAYLIST").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_playlist_misc").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("misc_check_updates_btn").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `library seeding leaves only andamp droid intro and cleans legacy sample files`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.local.SonoraDatabase.build(context)
    val repo = com.example.data.repository.LibraryRepository(
      context = context,
      trackDao = db.trackDao(),
      playlistDao = db.playlistDao(),
      eqPresetDao = db.eqPresetDao()
    )
    // Simulate legacy sample file pre-existing before update
    val sampleDir = java.io.File(context.filesDir, "reference_samples").apply { mkdirs() }
    java.io.File(sampleDir, "andamp_only_samples_v1.flag").delete()
    val legacyFlac = java.io.File(sampleDir, "nocturne_96k_24b.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val legacyUri = android.net.Uri.fromFile(legacyFlac).toString()
    db.trackDao().upsertTrack(
      com.example.data.local.TrackEntity.fromDomain(
        Track(
          uriString = legacyUri,
          title = "Nocturne en Obsidiana",
          artist = "Sample",
          album = "Old",
          durationMs = 10_000L,
          format = AudioFormatBadge.FLAC,
          sampleRateHz = 96_000,
          bitDepth = 24,
          bitrateKbps = 2000,
          isReferenceTrack = true
        )
      )
    )

    repo.ensureReferenceLibrarySeeded()
    val all = db.trackDao().getTrackCount()
    assertEquals(1, all)
    assertNull(db.trackDao().getByUri(legacyUri))
    val introUri = android.net.Uri.fromFile(java.io.File(sampleDir, "andamp_droid_intro.wav")).toString()
    val intro = db.trackDao().getByUri(introUri)
    assertEquals("Droid Whippin' Intro", intro?.title)
    db.close()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
  fun `add sheet nas tab shows lan scanner and manual connect controls`() {
    composeTestRule.setContent {
      SonoraTheme {
        WinampRackScreen(
          playbackState = PlaybackUiState(),
          playbackSourceMode = PlaybackSourceMode.MEDIA3_AUDIO,
          youTubeState = YouTubePlaybackState(),
          spectrumBars = FloatArray(24),
          outputInfo = AudioOutputInfo(),
          eqSettings = EqSettingsState(),
          customEqPresets = emptyList(),
          allTracks = emptyList(),
          displayedTracks = emptyList(),
          playlists = emptyList(),
          activePlaylist = null,
          selectedTrackId = null,
          searchQuery = "",
          selectedFilter = LibraryFormatFilter.ALL,
          isImporting = false,
          smbServers = emptyList(),
          smbBrowserState = SmbBrowserUiState(),
          youTubeSearchState = YouTubeSearchUiState(),
          recentYouTubePlaylists = emptyList(),
          onPlayExplicit = {},
          onPauseExplicit = {},
          onStopPlayback = {},
          onSkipPrevious = {},
          onSkipNext = {},
          onSeekTo = {},
          onToggleShuffle = {},
          onCycleRepeat = {},
          onEqEnabledChange = {},
          onEqAutoToggle = {},
          onPreampChange = {},
          onBandChange = { _, _ -> },
          onApplyPreset = {},
          onResetEqFlat = {},
          onSaveCustomPreset = {},
          onDeleteCustomPreset = {},
          onPlayTrack = {},
          onSelectTrack = {},
          onRemoveSelectedOrActive = {},
          onSelectPlaylist = {},
          onCreatePlaylist = { _, _ -> },
          onRenamePlaylist = { _, _, _ -> },
          onDeletePlaylist = {},
          onAddTrackToPlaylist = { _, _ -> },
          onToggleFavorite = {},
          onToggleBitPerfectUsb = {},
          onSearchQueryChange = {},
          onSelectFilter = {},
          onPlayAll = {},
          onMoveSelectedInPlaylist = {},
          onLaunchFilePicker = {},
          onLaunchFolderPicker = {},
          onSaveSmbServer = {},
          onDeleteSmbServer = {},
          onOpenSmbServer = {},
          onNavigateSmbEntry = {},
          onNavigateSmbUp = {},
          onCloseSmbBrowser = {},
          onImportAllSmbInFolder = {},
          onYouTubeQueryChange = {},
          onSearchYouTube = {},
          onPlayYouTubePlaylist = {},
          onDeleteRecentYouTube = {},
          onExitYouTube = {},
          onBindYouTubePlayer = {},
          onUnbindYouTubePlayer = {},
          onYouTubeStateChanged = {},
          onYouTubeProgress = { _, _ -> },
          onYouTubeVideoChanged = {},
          onYouTubeError = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_playlist_add").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("add_tab_nas").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("smb_scan_lan_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("smb_manual_toggle_button").assertIsDisplayed()
  }
}
