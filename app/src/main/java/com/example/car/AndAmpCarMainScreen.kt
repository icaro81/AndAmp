package com.example.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.R
import com.example.SonoraApplication
import com.example.audio.AudioOutputInfo
import com.example.audio.PlaybackUiState
import com.example.audio.RepeatModeState
import com.example.data.local.EqSettingsState
import com.example.domain.model.EqConstants
import com.example.domain.model.EqPreset
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@UnstableApi
@RequiresCarApi(6)
class AndAmpCarMainScreen(carContext: CarContext) : Screen(carContext) {

    private val app = carContext.applicationContext as SonoraApplication
    private val playerController = app.playerController
    private val libraryRepository = app.libraryRepository
    private val preferencesStore = app.preferencesStore
    private val eqProcessor = app.graphicEqAudioProcessor
    private val systemEq = app.systemAudioEffectEq
    private val outputMonitor = app.audioOutputMonitor

    private var activeTabId: String = TAB_PLAYER
    private var playbackState: PlaybackUiState = playerController.playbackState.value
    private var eqSettings: EqSettingsState = EqSettingsState()
    private var outputInfo: AudioOutputInfo = outputMonitor.outputInfo.value
    private var allTracks: List<Track> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var customPresets: List<EqPreset> = emptyList()
    private var selectedSourceIndex: Int = 0

    private val lcdGreenColor = CarColor.createCustom(0xFF25FF6A.toInt(), 0xFF25FF6A.toInt())
    private val goldAccentColor = CarColor.createCustom(0xFFD6B44B.toInt(), 0xFFD6B44B.toInt())

    init {
        playerController.connect()
        lifecycleScope.launch {
            combine(
                playerController.playbackState,
                preferencesStore.eqSettingsFlow,
                outputMonitor.outputInfo,
                libraryRepository.allTracksFlow,
                libraryRepository.allPlaylistsFlow,
                libraryRepository.customEqPresetsFlow
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                CarScreenSnapshot(
                    playback = values[0] as PlaybackUiState,
                    eq = values[1] as EqSettingsState,
                    output = values[2] as AudioOutputInfo,
                    tracks = values[3] as List<Track>,
                    playlists = values[4] as List<Playlist>,
                    presets = values[5] as List<EqPreset>
                )
            }.collectLatest { snap ->
                playbackState = snap.playback
                eqSettings = snap.eq
                outputInfo = snap.output
                allTracks = snap.tracks
                playlists = snap.playlists
                customPresets = snap.presets
                invalidate()
            }
        }
    }

    fun selectTab(tabId: String) {
        if (activeTabId != tabId) {
            activeTabId = tabId
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val contentTemplate = when (activeTabId) {
            TAB_EQUALIZER -> buildEqualizerTabTemplate()
            TAB_PLAYLIST -> buildPlaylistTabTemplate()
            else -> buildPlayerTabTemplate()
        }

        val tabCallback = object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                selectTab(tabContentId)
            }
        }

        return TabTemplate.Builder(tabCallback)
            .setHeaderAction(Action.APP_ICON)
            .addTab(
                Tab.Builder()
                    .setContentId(TAB_PLAYER)
                    .setTitle(carContext.getString(R.string.car_tab_player))
                    .setIcon(tintedIcon(R.drawable.ic_car_player, activeTabId == TAB_PLAYER))
                    .build()
            )
            .addTab(
                Tab.Builder()
                    .setContentId(TAB_EQUALIZER)
                    .setTitle(carContext.getString(R.string.car_tab_equalizer))
                    .setIcon(tintedIcon(R.drawable.ic_car_equalizer, activeTabId == TAB_EQUALIZER))
                    .build()
            )
            .addTab(
                Tab.Builder()
                    .setContentId(TAB_PLAYLIST)
                    .setTitle(carContext.getString(R.string.car_tab_playlist))
                    .setIcon(tintedIcon(R.drawable.ic_car_playlist, activeTabId == TAB_PLAYLIST))
                    .build()
            )
            .setActiveTabContentId(activeTabId)
            .setTabContents(TabContents.Builder(contentTemplate).build())
            .build()
    }

    private fun buildPlayerTabTemplate(): Template {
        val currentTrack = playbackState.currentTrack
            ?.let { active -> allTracks.firstOrNull { it.id == active.id } ?: active }
            ?: allTracks.firstOrNull()

        val allPresets = EqConstants.BUILT_IN_PRESETS + customPresets
        val activePresetName = allPresets.firstOrNull { it.id == eqSettings.activePresetId }?.name
            ?: if (eqSettings.activePresetId == 0L) "Manual" else "Plano"

        val listBuilder = ItemList.Builder()

        // 1. Modulo principal: Pista activa + Play/Pausa
        val nowPlayingTitle = when {
            currentTrack == null -> "AndAmp Hi-Fi Listo"
            playbackState.isPlaying -> "▶ ${currentTrack.title}"
            playbackState.positionMs > 0L -> "❚❚ ${currentTrack.title}"
            else -> currentTrack.title
        }
        val nowPlayingMeta = if (currentTrack != null) {
            "${currentTrack.artist} · ${currentTrack.album}"
        } else {
            "Toca Lista para elegir una pista"
        }
        val nowPlayingTech = if (currentTrack != null) {
            "${currentTrack.technicalSpecLabel} · ${outputInfo.routeName}"
        } else {
            outputInfo.routeName
        }

        val playRow = Row.Builder()
            .setTitle(nowPlayingTitle)
            .addText(nowPlayingMeta)
            .addText(nowPlayingTech)
            .setImage(
                tintedIcon(
                    resId = if (playbackState.isPlaying) R.drawable.ic_car_pause else R.drawable.ic_car_play,
                    active = playbackState.isPlaying
                )
            )
            .setOnClickListener {
                if (playbackState.currentTrack == null && allTracks.isNotEmpty()) {
                    playerController.playTracks(allTracks, 0)
                } else {
                    playerController.togglePlayPause()
                }
            }
            .build()
        listBuilder.addItem(playRow)

        // 2. Navegacion de pistas: Siguiente (fila) y Anterior (accion lateral)
        val prevAction = Action.Builder()
            .setIcon(tintedIcon(R.drawable.ic_car_prev, active = false))
            .setOnClickListener { playerController.skipPrevious() }
            .build()

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Siguiente pista")
                .addText("Toca para avanzar · Botón derecho: Anterior")
                .setImage(tintedIcon(R.drawable.ic_car_next, active = true))
                .setOnClickListener {
                    if (playbackState.currentTrack == null && allTracks.size > 1) {
                        playerController.playTracks(allTracks, 1)
                    } else {
                        playerController.skipNext()
                    }
                }
                .addAction(prevAction)
                .build()
        )

        // 3. Boton rapido: Aleatorio
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Aleatorio")
                .addText(if (playbackState.shuffleEnabled) "Activado" else "Desactivado")
                .setImage(tintedIcon(R.drawable.ic_car_shuffle, active = playbackState.shuffleEnabled))
                .setToggle(
                    Toggle.Builder { _ -> playerController.toggleShuffle() }
                        .setChecked(playbackState.shuffleEnabled)
                        .build()
                )
                .build()
        )

        // 4. Boton rapido: Repetir
        val repeatText = when (playbackState.repeatMode) {
            RepeatModeState.OFF -> "Desactivado"
            RepeatModeState.ALL -> "Toda la lista"
            RepeatModeState.ONE -> "Pista actual"
        }
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Repetir")
                .addText(repeatText)
                .setImage(
                    tintedIcon(
                        R.drawable.ic_car_repeat,
                        active = playbackState.repeatMode != RepeatModeState.OFF
                    )
                )
                .setOnClickListener { playerController.cycleRepeatMode() }
                .build()
        )

        // 5. Boton rapido: Ecualizador
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Ecualizador")
                .addText("${if (eqSettings.enabled) "Activo" else "Apagado"} · $activePresetName")
                .setImage(tintedIcon(R.drawable.ic_car_equalizer, active = eqSettings.enabled))
                .setToggle(
                    Toggle.Builder { enabled ->
                        updateEqSettings(eqSettings.copy(enabled = enabled))
                    }
                        .setChecked(eqSettings.enabled)
                        .build()
                )
                .build()
        )

        // 6. Boton rapido: Favorito
        val isFav = currentTrack?.isFavorite == true
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Favorito")
                .addText(
                    when {
                        currentTrack == null -> "Sin pista seleccionada"
                        isFav -> "Guardada en Favoritos"
                        else -> "Toca para marcar Favorito"
                    }
                )
                .setImage(
                    tintedIcon(
                        resId = if (isFav) R.drawable.ic_car_favorite else R.drawable.ic_car_favorite_border,
                        active = isFav
                    )
                )
                .setToggle(
                    Toggle.Builder { _ ->
                        currentTrack?.let { target ->
                            lifecycleScope.launch {
                                libraryRepository.toggleFavorite(target)
                                val updated = target.copy(isFavorite = !target.isFavorite)
                                playerController.syncTrackMetadataUpdates(listOf(updated))
                            }
                        }
                    }
                        .setChecked(isFav)
                        .setEnabled(currentTrack != null)
                        .build()
                )
                .build()
        )

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildEqualizerTabTemplate(): Template {
        val maxItems = resolveMaxListItems()
        val listBuilder = ItemList.Builder()
        var added = 0

        fun tryAdd(row: Row) {
            if (added < maxItems) {
                listBuilder.addItem(row)
                added++
            }
        }

        // 1. Toggle general EQ
        tryAdd(
            Row.Builder()
                .setTitle("Ecualizador DSP (10 Bandas)")
                .addText(if (eqSettings.enabled) "Procesamiento activo" else "Bypass directo")
                .setImage(tintedIcon(R.drawable.ic_car_equalizer, active = eqSettings.enabled))
                .setToggle(
                    Toggle.Builder { enabled ->
                        updateEqSettings(eqSettings.copy(enabled = enabled))
                    }
                        .setChecked(eqSettings.enabled)
                        .build()
                )
                .build()
        )

        // 2. Auto-Gain
        tryAdd(
            Row.Builder()
                .setTitle("Auto-Gain (Anti-clipping)")
                .addText(if (eqSettings.autoGain) "Ajuste automático de preamp" else "Control manual")
                .setToggle(
                    Toggle.Builder { nextAuto ->
                        val maxBoost = eqSettings.bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
                        val adjustedPreamp = if (nextAuto && maxBoost > 0f) {
                            (-maxBoost * 0.7f).coerceIn(-12f, 0f)
                        } else eqSettings.preampDb
                        updateEqSettings(eqSettings.copy(autoGain = nextAuto, preampDb = adjustedPreamp))
                    }
                        .setChecked(eqSettings.autoGain)
                        .build()
                )
                .build()
        )

        // 3. USB Bit-Perfect si hay DAC USB conectado (o al principio de hardware)
        if (outputInfo.isUsbDacConnected) {
            tryAdd(
                Row.Builder()
                    .setTitle("DAC USB Bit-Perfect")
                    .addText(
                        if (outputInfo.bitPerfectActive) "Activo · ${outputInfo.routeName}"
                        else outputInfo.routeName
                    )
                    .setImage(tintedIcon(R.drawable.ic_car_usb, active = eqSettings.bitPerfectUsbRequested))
                    .setToggle(
                        Toggle.Builder { enabled ->
                            updateEqSettings(eqSettings.copy(bitPerfectUsbRequested = enabled))
                            outputMonitor.refresh(enabled)
                        }
                            .setChecked(eqSettings.bitPerfectUsbRequested)
                            .build()
                    )
                    .build()
            )
        }

        // Presets integrados y personalizados (priorizando el activo si el límite de conducción es estricto)
        val presets = (EqConstants.BUILT_IN_PRESETS + customPresets).let { all ->
            val active = all.firstOrNull { it.id == eqSettings.activePresetId }
            if (active != null && all.indexOf(active) >= (maxItems - added)) {
                listOf(active) + all.filter { it.id != active.id }
            } else {
                all
            }
        }

        presets.forEach { preset ->
            val isActive = preset.id == eqSettings.activePresetId
            tryAdd(
                Row.Builder()
                    .setTitle(if (isActive) "✓ ${preset.name}" else preset.name)
                    .addText(if (preset.isBuiltIn) "Preset AndAmp Hi-Fi" else "Preset personalizado")
                    .setOnClickListener {
                        updateEqSettings(
                            eqSettings.copy(
                                enabled = true,
                                bandsDb = preset.bandsDb,
                                preampDb = preset.preampDb,
                                activePresetId = preset.id
                            )
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildPlaylistTabTemplate(): Template {
        val maxItems = resolveMaxListItems()
        val listBuilder = ItemList.Builder()

        val sourceLabels = mutableListOf(
            "Todas las pistas (${allTracks.size})",
            "Favoritos (${allTracks.count { it.isFavorite }})",
            "Música en Red NAS (${allTracks.count { it.uriString.startsWith("smb://") }})"
        )
        playlists.forEach { pl ->
            sourceLabels.add("Lista: ${pl.name} (${pl.tracks.size})")
        }
        val totalSources = sourceLabels.size.coerceAtLeast(1)
        val safeSourceIdx = selectedSourceIndex.coerceIn(0, totalSources - 1)

        val filteredTracks = when (safeSourceIdx) {
            0 -> allTracks
            1 -> allTracks.filter { it.isFavorite }
            2 -> allTracks.filter { it.uriString.startsWith("smb://") }
            else -> playlists.getOrNull(safeSourceIdx - 3)?.tracks ?: allTracks
        }

        // Fila 1: Selector de fuente / lista de reproduccion
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Fuente: ${sourceLabels[safeSourceIdx]}")
                .addText("Toca para cambiar entre Biblioteca, Favoritos, NAS o Listas")
                .setImage(tintedIcon(R.drawable.ic_car_playlist, active = true))
                .setOnClickListener {
                    selectedSourceIndex = (safeSourceIdx + 1) % totalSources
                    invalidate()
                }
                .build()
        )

        val remainingSlots = (maxItems - 1).coerceAtLeast(1)
        if (filteredTracks.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Sin pistas en esta vista")
                    .addText("Toca la fila superior para ver otra fuente")
                    .build()
            )
        } else {
            val activeId = playbackState.currentTrack?.id
            filteredTracks.take(remainingSlots).forEachIndexed { idx, track ->
                val isPlayingThis = track.id == activeId
                val title = if (isPlayingThis) "▶ ${track.title}" else track.title
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(title)
                        .addText("${track.artist} · ${track.technicalSpecLabel}")
                        .setImage(
                            tintedIcon(
                                resId = if (isPlayingThis) R.drawable.ic_car_play else R.drawable.ic_car_player,
                                active = isPlayingThis
                            )
                        )
                        .setOnClickListener {
                            playerController.playTracks(filteredTracks, idx)
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun updateEqSettings(updated: EqSettingsState) {
        eqSettings = updated
        eqProcessor.updateSettings(updated.enabled, updated.bandsDb, updated.preampDb)
        systemEq.updateSettings(
            enabled = updated.enabled,
            gainsDb = updated.bandsDb,
            preamp = updated.preampDb,
            shouldActivate = !eqProcessor.isHandlingActiveStream
        )
        lifecycleScope.launch {
            preferencesStore.saveEqSettings(updated)
        }
        invalidate()
    }

    private fun resolveMaxListItems(): Int {
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrElse { 6 }.coerceAtLeast(6)
    }

    private fun tintedIcon(@DrawableRes resId: Int, active: Boolean): CarIcon {
        val color = if (active) lcdGreenColor else goldAccentColor
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId))
            .setTint(color)
            .build()
    }

    private data class CarScreenSnapshot(
        val playback: PlaybackUiState,
        val eq: EqSettingsState,
        val output: AudioOutputInfo,
        val tracks: List<Track>,
        val playlists: List<Playlist>,
        val presets: List<EqPreset>
    )

    companion object {
        const val TAB_PLAYER = "tab_player"
        const val TAB_EQUALIZER = "tab_equalizer"
        const val TAB_PLAYLIST = "tab_playlist"
    }
}
