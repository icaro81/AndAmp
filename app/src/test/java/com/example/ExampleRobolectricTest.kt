package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.example.ui.viewmodel.browserKey
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
    assertEquals("icaro81/AndAmp", com.example.data.local.AppUpdatePrefs().githubRepo)
    assertEquals("icaro81/AndAmp", com.example.data.local.AppUpdatePrefs.normalizeRepo("usuario/AndAmp"))
    assertEquals("icaro81/AndAmp", com.example.data.local.AppUpdatePrefs.normalizeRepo("https://github.com/icaro81/AndAmp.git"))

    val listJson = """
      [
        {
          "tag_name": "latest-ci",
          "name": "AndAmp CI",
          "body": "CI Build",
          "draft": false,
          "prerelease": true,
          "assets": [
            {
              "name": "AndAmp-v1.0.5.apk",
              "size": 19000000,
              "browser_download_url": "https://github.com/icaro81/AndAmp/releases/download/latest-ci/AndAmp-v1.0.5.apk"
            },
            {
              "name": "AndAmp-v1.0.5-release.apk",
              "size": 17000000,
              "browser_download_url": "https://github.com/icaro81/AndAmp/releases/download/latest-ci/AndAmp-v1.0.5-release.apk"
            }
          ]
        }
      ]
    """.trimIndent()
    val fallbackDebug = com.example.update.AppUpdateManager.parseReleasesListJson(listJson, "1.0.0", preferReleaseApk = false)
    assertEquals("1.0.5", fallbackDebug?.version)
    assertEquals(
      "https://github.com/icaro81/AndAmp/releases/download/latest-ci/AndAmp-v1.0.5.apk",
      fallbackDebug?.apkUrl
    )
    val fallbackRelease = com.example.update.AppUpdateManager.parseReleasesListJson(listJson, "1.0.0", preferReleaseApk = true)
    assertEquals(
      "https://github.com/icaro81/AndAmp/releases/download/latest-ci/AndAmp-v1.0.5-release.apk",
      fallbackRelease?.apkUrl
    )
  }

  @Test
  fun `smb share and subpath splitting handles gpon router paths and protocol hints`() {
    val (share, path) = com.example.data.repository.SmbRepository.splitShareAndSubpath("USB1_1/MUSICA", "")
    assertEquals("USB1_1", share)
    assertEquals("MUSICA", path)

    val (shareNested, pathNested) = com.example.data.repository.SmbRepository.splitShareAndSubpath(
      "\\USB1_1\\MUSICA",
      "ROCK\\90s"
    )
    assertEquals("USB1_1", shareNested)
    assertEquals("MUSICA\\ROCK\\90s", pathNested)

    assertEquals("gpon.net", com.example.data.repository.SmbRepository.normalizeHost("smb://gpon.net/"))

    val uri = android.net.Uri.parse(
      com.example.data.repository.SmbRepository.buildSmbUri(
        serverId = 7L,
        shareName = "USB1_1",
        relativePath = "MUSICA\\Track.flac",
        fileName = "Track.flac",
        protocolHint = "smb1"
      )
    )
    assertEquals("smb1", uri.getQueryParameter("proto"))
    assertEquals("USB1_1", uri.getQueryParameter("share"))
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

    val ytState = YouTubePlaybackState(privacyEnhancedMode = true, reloadNonce = 2)
    assertEquals(true, ytState.privacyEnhancedMode)
    assertEquals(2, ytState.reloadNonce)
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
    composeTestRule.onNodeWithText("BUSCAR EN MI WI-FI").assertIsDisplayed()
    composeTestRule.onNodeWithTag("smb_manual_toggle_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("AVANZADO").assertIsDisplayed()

    val autoRetryState = YouTubePlaybackState(hasAutoRetriedPrivacy = true, privacyEnhancedMode = true)
    assertEquals(true, autoRetryState.hasAutoRetriedPrivacy)
    assertEquals(true, autoRetryState.privacyEnhancedMode)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
  fun `smb browser supports multi folder checkboxes and guardar seleccionadas bar`() {
    val testServer = com.example.domain.model.SmbServerProfile(
      id = 1L,
      alias = "Router GPON",
      host = "gpon.net",
      defaultShare = "USB1_1"
    )
    val dirs = listOf(
      com.example.domain.model.SmbBrowserEntry(
        name = "Rock",
        relativePath = "MUSICA\\Rock",
        shareName = "USB1_1",
        isDirectory = true
      ),
      com.example.domain.model.SmbBrowserEntry(
        name = "Jazz",
        relativePath = "MUSICA\\Jazz",
        shareName = "USB1_1",
        isDirectory = true
      )
    )
    var importedBatchCount = 0

    composeTestRule.setContent {
      var browserState by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
          SmbBrowserUiState(
            activeServer = testServer,
            currentShare = "USB1_1",
            currentPath = "MUSICA",
            protocolLabel = "SMB1/CIFS",
            entries = dirs
          )
        )
      }
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
          smbServers = listOf(testServer),
          smbBrowserState = browserState,
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
          onToggleSmbFolderSelection = { entry ->
            val key = entry.browserKey()
            val next = if (key in browserState.selectedFolderKeys) {
              browserState.selectedFolderKeys - key
            } else {
              browserState.selectedFolderKeys + key
            }
            browserState = browserState.copy(selectedFolderKeys = next)
          },
          onSelectAllSmbFolders = {
            browserState = browserState.copy(
              selectedFolderKeys = browserState.entries.map { it.browserKey() }.toSet()
            )
          },
          onClearSmbFolderSelection = {
            browserState = browserState.copy(selectedFolderKeys = emptySet())
          },
          onImportSelectedSmbFolders = {
            importedBatchCount = browserState.selectedFolderKeys.size
          },
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

    composeTestRule.onNodeWithTag("smb_folder_checkbox_Rock").performClick()
    composeTestRule.onNodeWithTag("smb_folder_checkbox_Jazz").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("smb_multi_select_bar").assertIsDisplayed()
    composeTestRule.onNodeWithText("2 carpeta(s) seleccionada(s)").assertIsDisplayed()
    composeTestRule.onNodeWithTag("smb_import_selected_folders").performClick()
    assertEquals(2, importedBatchCount)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w1024dp-h540dp-land-xxhdpi")
  fun `android auto manifest metadata and wide landscape fullscreen toggle work cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager
    val appInfo = pm.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
    assertEquals(android.content.pm.ApplicationInfo.CATEGORY_AUDIO, appInfo.category)
    assertEquals(
      R.xml.automotive_app_desc,
      appInfo.metaData.getInt("com.google.android.gms.car.application")
    )

    val mediaLibServices = pm.queryIntentServices(
      android.content.Intent("androidx.media3.session.MediaLibraryService"),
      0
    )
    assertEquals(true, mediaLibServices.any { it.serviceInfo.packageName == context.packageName })

    val manager = com.example.update.AppUpdateManager(context)
    val badApk = java.io.File(context.cacheDir, "invalid.apk").apply { writeText("not an apk") }
    assertEquals(
      com.example.update.ApkInstallResult.ERROR,
      manager.launchApkInstaller(badApk)
    )

    var fullscreenState = false
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
          isLandscapeFullscreen = fullscreenState,
          onSetLandscapeFullscreen = { fullscreenState = it },
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
    composeTestRule.onNodeWithTag("landscape_fullscreen_toggle").assertIsDisplayed().performClick()
    assertEquals(true, fullscreenState)
  }
}
