package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.audio.AudioOutputInfo
import com.example.data.local.AppUpdatePrefs
import com.example.domain.model.EqConstants
import com.example.domain.model.EqPreset
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.ui.components.WinampMetallicButton
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import com.example.ui.viewmodel.LibraryFormatFilter
import com.example.update.AppUpdateUiState
import com.example.update.ReleaseUpdateInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SelectFilterSheet(
    searchQuery: String,
    selectedFilter: LibraryFormatFilter,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (LibraryFormatFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WinampColors.ChassisSurface,
        contentColor = WinampColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "FILTRAR Y SELECCIONAR PISTAS",
                style = MaterialTheme.typography.titleMedium,
                color = WinampColors.TitleGold
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Buscar título, artista, FLAC, DSD...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = WinampColors.LcdGreen) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Limpiar búsqueda", tint = WinampColors.TextSecondary)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = WinampColors.LcdBlack,
                    unfocusedContainerColor = WinampColors.LcdBlack,
                    focusedTextColor = WinampColors.LcdGreen,
                    unfocusedTextColor = WinampColors.LcdGreen,
                    focusedBorderColor = WinampColors.EqGoldBright,
                    unfocusedBorderColor = WinampColors.BevelHighlight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_search_input")
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LibraryFormatFilter.entries.forEach { filter ->
                    val selected = filter == selectedFilter
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WinampColors.PlaylistSelectBg,
                            selectedLabelColor = WinampColors.LcdGreen,
                            containerColor = WinampColors.ChassisSurfaceBottom,
                            labelColor = WinampColors.TextSecondary
                        ),
                        modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        onSearchQueryChange("")
                        onSelectFilter(LibraryFormatFilter.ALL)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("filter_reset_button")
                ) {
                    Text("Restablecer", color = WinampColors.EqGoldBright)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar", color = WinampColors.LcdGreen)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiscManagerSheet(
    playlists: List<Playlist>,
    activePlaylistId: Long?,
    selectedOrActiveTrack: Track?,
    outputInfo: AudioOutputInfo,
    bitPerfectRequested: Boolean,
    appUpdatePrefs: AppUpdatePrefs = AppUpdatePrefs(),
    appUpdateState: AppUpdateUiState = AppUpdateUiState.Idle,
    onSelectPlaylist: (Long?) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onRenamePlaylist: (Long, String, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleBitPerfectUsb: (Boolean) -> Unit,
    onSaveUpdateRepo: (String) -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var repoText by remember(appUpdatePrefs.githubRepo) { mutableStateOf(appUpdatePrefs.githubRepo) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WinampColors.ChassisSurface,
        contentColor = WinampColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LISTAS Y SALIDA HI-RES",
                    style = MaterialTheme.typography.titleMedium,
                    color = WinampColors.TitleGold
                )
                WinampMetallicButton(
                    onClick = { showCreateDialog = true },
                    testTag = "misc_new_playlist",
                    contentDesc = "Nueva lista de reproducción",
                    minHeight = 40.dp
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = WinampColors.LcdGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("NUEVA", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp)
                    .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    val isSelected = activePlaylistId == null
                    Surface(
                        onClick = {
                            onSelectPlaylist(null)
                            onDismiss()
                        },
                        color = if (isSelected) WinampColors.PlaylistSelectBg else WinampColors.LcdBlack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playlist_item_all")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Biblioteca Completa",
                                fontFamily = JetBrainsMonoFamily,
                                color = if (isSelected) WinampColors.TextPrimary else WinampColors.LcdGreen,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(Icons.Filled.Check, contentDescription = "Activa", tint = WinampColors.LcdGreen)
                            }
                        }
                    }
                }
                items(playlists, key = { it.id }) { pl ->
                    val isSelected = pl.id == activePlaylistId
                    Surface(
                        onClick = {
                            onSelectPlaylist(pl.id)
                            onDismiss()
                        },
                        color = if (isSelected) WinampColors.PlaylistSelectBg else WinampColors.LcdBlack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playlist_item_${pl.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.name,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = if (isSelected) WinampColors.TextPrimary else WinampColors.LcdGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pl.trackCount} pistas",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WinampColors.TextSecondary
                                )
                            }
                            IconButton(
                                onClick = { editingPlaylist = pl },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Renombrar lista", tint = WinampColors.EqGoldBright)
                            }
                            IconButton(
                                onClick = { onDeletePlaylist(pl.id) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Eliminar lista", tint = WinampColors.LcdPeakRed)
                            }
                        }
                    }
                }
            }

            if (selectedOrActiveTrack != null) {
                Surface(
                    color = WinampColors.ChassisSurfaceBottom,
                    border = BorderStroke(1.dp, WinampColors.BevelHighlight.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedOrActiveTrack.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedOrActiveTrack.technicalSpecLabel,
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = WinampColors.LcdGreen
                            )
                        }
                        IconButton(
                            onClick = { onToggleFavorite(selectedOrActiveTrack) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("misc_toggle_favorite")
                        ) {
                            Icon(
                                imageVector = if (selectedOrActiveTrack.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Alternar favorito",
                                tint = if (selectedOrActiveTrack.isFavorite) WinampColors.EqGoldBright else WinampColors.TextSecondary
                            )
                        }
                    }
                }
            }

            Surface(
                color = WinampColors.ChassisSurfaceBottom,
                border = BorderStroke(1.dp, WinampColors.BevelHighlight.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(3.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null, tint = WinampColors.EqGoldBright)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = outputInfo.routeName,
                            style = MaterialTheme.typography.titleSmall,
                            color = WinampColors.TextPrimary
                        )
                        Text(
                            text = "Máx. ${outputInfo.maxSupportedSampleRateHz / 1000} kHz · " +
                                if (outputInfo.bitPerfectActive) "USB Bit-Perfect activo" else "Mezcla Hi-Res Float32",
                            style = MaterialTheme.typography.labelSmall,
                            color = WinampColors.LcdGreen
                        )
                    }
                    if (outputInfo.bitPerfectSupported) {
                        Switch(
                            checked = bitPerfectRequested,
                            onCheckedChange = onToggleBitPerfectUsb,
                            colors = SwitchDefaults.colors(checkedThumbColor = WinampColors.LcdGreen)
                        )
                    }
                }
            }

            // Internal App Update Panel (GitHub Releases APK OTA without external browser)
            Surface(
                color = WinampColors.ChassisSurfaceBottom,
                border = BorderStroke(1.dp, WinampColors.BevelHighlight.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = WinampColors.LcdGreen)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ACTUALIZACIONES INTERNAS (v${BuildConfig.VERSION_NAME})",
                                style = MaterialTheme.typography.titleSmall,
                                color = WinampColors.TitleGold
                            )
                            val statusText = when (appUpdateState) {
                                AppUpdateUiState.Idle -> "Chequea y descarga APK desde GitHub Releases"
                                AppUpdateUiState.Checking -> "Comprobando última versión en GitHub..."
                                is AppUpdateUiState.UpToDate -> "Estás en la última versión (v${appUpdateState.currentVersion})"
                                is AppUpdateUiState.Available -> "¡Nueva versión v${appUpdateState.info.version} disponible!"
                                is AppUpdateUiState.Downloading -> "Descargando v${appUpdateState.version}: ${(appUpdateState.progress * 100).toInt()}%"
                                is AppUpdateUiState.ReadyToInstall -> "APK v${appUpdateState.version} listo para instalar"
                                is AppUpdateUiState.NeedsInstallPermission -> "Falta permiso para instalar actualización"
                                is AppUpdateUiState.Error -> appUpdateState.message
                            }
                            Text(
                                text = statusText,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = if (appUpdateState is AppUpdateUiState.Error) WinampColors.LcdPeakRed else WinampColors.LcdGreen
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = repoText,
                            onValueChange = { repoText = it },
                            label = { Text("Repo GitHub (usuario/AndAmp)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = WinampColors.LcdBlack,
                                unfocusedContainerColor = WinampColors.LcdBlack,
                                focusedTextColor = WinampColors.LcdGreen,
                                unfocusedTextColor = WinampColors.LcdGreen,
                                focusedBorderColor = WinampColors.EqGoldBright
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("misc_update_repo_input")
                        )
                        WinampMetallicButton(
                            onClick = { onSaveUpdateRepo(repoText) },
                            testTag = "misc_save_repo_btn",
                            contentDesc = "Guardar repositorio GitHub",
                            minHeight = 48.dp
                        ) {
                            Text("OK", fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold, color = WinampColors.LcdGreen)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WinampMetallicButton(
                            onClick = {
                                onSaveUpdateRepo(repoText)
                                onCheckUpdates()
                            },
                            testTag = "misc_check_updates_btn",
                            contentDesc = "Buscar actualización interna",
                            minHeight = 42.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (appUpdateState is AppUpdateUiState.Checking) "BUSCANDO..." else "BUSCAR ACTUALIZACIÓN",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = WinampColors.TitleGold
                            )
                        }

                        if (appUpdateState is AppUpdateUiState.Available ||
                            appUpdateState is AppUpdateUiState.Downloading ||
                            appUpdateState is AppUpdateUiState.ReadyToInstall ||
                            appUpdateState is AppUpdateUiState.NeedsInstallPermission
                        ) {
                            WinampMetallicButton(
                                onClick = onOpenUpdateDialog,
                                testTag = "misc_open_update_dialog_btn",
                                contentDesc = "Abrir instalador de actualización",
                                minHeight = 42.dp
                            ) {
                                Text(
                                    text = "INSTALAR",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = WinampColors.LcdGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "Nueva Lista",
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name, "")
                showCreateDialog = false
            }
        )
    }

    editingPlaylist?.let { pl ->
        PlaylistNameDialog(
            title = "Renombrar Lista",
            initialName = pl.name,
            onDismiss = { editingPlaylist = null },
            onConfirm = { name ->
                onRenamePlaylist(pl.id, name, pl.description)
                editingPlaylist = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqPresetsSheet(
    activePresetId: Long,
    customPresets: List<EqPreset>,
    onApplyPreset: (EqPreset) -> Unit,
    onResetFlat: () -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSaveDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WinampColors.ChassisSurface,
        contentColor = WinampColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRESETS DE ECUALIZADOR",
                    style = MaterialTheme.typography.titleMedium,
                    color = WinampColors.TitleGold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WinampMetallicButton(
                        onClick = onResetFlat,
                        testTag = "eq_reset_flat",
                        contentDesc = "Restablecer curva plana",
                        minHeight = 38.dp
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = WinampColors.TitleSilver, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PLANO", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                    }
                    WinampMetallicButton(
                        onClick = { showSaveDialog = true },
                        testTag = "eq_save_preset",
                        contentDesc = "Guardar preset actual",
                        minHeight = 38.dp
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, tint = WinampColors.EqGoldBright, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GUARDAR", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                    }
                }
            }

            val allPresets = customPresets + EqConstants.BUILT_IN_PRESETS
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(allPresets, key = { it.id }) { preset ->
                    val selected = preset.id == activePresetId
                    Surface(
                        onClick = {
                            onApplyPreset(preset)
                            onDismiss()
                        },
                        color = if (selected) WinampColors.PlaylistSelectBg else WinampColors.LcdBlack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("eq_preset_item_${preset.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = if (selected) WinampColors.EqGoldBright else WinampColors.LcdGreenDim,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = preset.name,
                                fontFamily = JetBrainsMonoFamily,
                                color = if (selected) WinampColors.TextPrimary else WinampColors.LcdGreen,
                                modifier = Modifier.weight(1f)
                            )
                            if (!preset.isBuiltIn) {
                                IconButton(
                                    onClick = { onDeleteCustomPreset(preset.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Eliminar preset", tint = WinampColors.LcdPeakRed)
                                }
                            } else if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = "Seleccionado", tint = WinampColors.LcdGreen)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        PlaylistNameDialog(
            title = "Guardar Preset EQ",
            initialName = "",
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                onSaveCustomPreset(name)
                showSaveDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListOptsSheet(
    activePlaylist: Playlist?,
    canReorderSelected: Boolean,
    onPlayAll: (Boolean) -> Unit,
    onMoveSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WinampColors.ChassisSurface,
        contentColor = WinampColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "OPCIONES DE LISTA",
                style = MaterialTheme.typography.titleMedium,
                color = WinampColors.TitleGold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WinampMetallicButton(
                    onClick = {
                        onPlayAll(false)
                        onDismiss()
                    },
                    testTag = "list_opts_play_all",
                    contentDesc = "Reproducir toda la lista",
                    minHeight = 48.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = WinampColors.LcdGreen)
                    Spacer(Modifier.width(6.dp))
                    Text("REPRODUCIR", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                }

                WinampMetallicButton(
                    onClick = {
                        onPlayAll(true)
                        onDismiss()
                    },
                    testTag = "list_opts_shuffle_all",
                    contentDesc = "Mezclar toda la lista",
                    minHeight = 48.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, tint = WinampColors.EqGoldBright)
                    Spacer(Modifier.width(6.dp))
                    Text("MEZCLAR", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                }
            }

            if (activePlaylist != null && canReorderSelected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WinampMetallicButton(
                        onClick = { onMoveSelected(-1) },
                        testTag = "list_opts_move_up",
                        contentDesc = "Subir pista seleccionada",
                        minHeight = 46.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = WinampColors.LcdGreen)
                        Spacer(Modifier.width(6.dp))
                        Text("SUBIR PISTA", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                    }
                    WinampMetallicButton(
                        onClick = { onMoveSelected(1) },
                        testTag = "list_opts_move_down",
                        contentDesc = "Bajar pista seleccionada",
                        minHeight = 46.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = WinampColors.EqGoldBright)
                        Spacer(Modifier.width(6.dp))
                        Text("BAJAR PISTA", fontFamily = JetBrainsMonoFamily, color = WinampColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WinampColors.ChassisSurface,
        titleContentColor = WinampColors.TitleGold,
        textContentColor = WinampColors.TextPrimary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Nombre...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = WinampColors.LcdBlack,
                    unfocusedContainerColor = WinampColors.LcdBlack,
                    focusedTextColor = WinampColors.LcdGreen,
                    unfocusedTextColor = WinampColors.LcdGreen,
                    focusedBorderColor = WinampColors.EqGoldBright
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text("Guardar", color = WinampColors.LcdGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = WinampColors.TextSecondary)
            }
        }
    )
}

@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDownloadAndInstall: (ReleaseUpdateInfo) -> Unit,
    onInstallReadyApk: (java.io.File) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onDismiss: (skipVersion: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (state !is AppUpdateUiState.Downloading) onDismiss(false) },
        containerColor = WinampColors.ChassisSurface,
        titleContentColor = WinampColors.TitleGold,
        textContentColor = WinampColors.TextPrimary,
        title = {
            Text(
                text = when (state) {
                    is AppUpdateUiState.Available -> "Actualización AndAmp v${state.info.version}"
                    is AppUpdateUiState.Downloading -> "Descargando v${state.version}..."
                    is AppUpdateUiState.NeedsInstallPermission -> "Permiso de instalación"
                    is AppUpdateUiState.ReadyToInstall -> "Instalación lista (v${state.version})"
                    is AppUpdateUiState.Error -> "Error al actualizar"
                    else -> "Actualización de AndAmp"
                },
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (state) {
                    is AppUpdateUiState.Available -> {
                        val sizeMb = if (state.info.fileSizeBytes > 0L) {
                            String.format(java.util.Locale.US, "%.1f MB", state.info.fileSizeBytes / (1024f * 1024f))
                        } else "APK"
                        Text(
                            text = "Versión actual: v${BuildConfig.VERSION_NAME} → Nueva: v${state.info.version} ($sizeMb)",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdGreen
                        )
                        Text(
                            text = state.info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = WinampColors.TextPrimary
                        )
                    }
                    is AppUpdateUiState.Downloading -> {
                        val pct = (state.progress * 100).toInt()
                        val doneMb = state.downloadedBytes / (1024f * 1024f)
                        val totalMb = state.totalBytes.coerceAtLeast(1L) / (1024f * 1024f)
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = WinampColors.LcdGreen,
                            trackColor = WinampColors.LcdBlack
                        )
                        Text(
                            text = if (state.totalBytes > 0L) {
                                String.format(java.util.Locale.US, "%d%% · %.1f / %.1f MB", pct, doneMb, totalMb)
                            } else {
                                String.format(java.util.Locale.US, "%.1f MB descargados", doneMb)
                            },
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdGreen
                        )
                    }
                    is AppUpdateUiState.NeedsInstallPermission -> {
                        Text(
                            text = "AndAmp ya descargó el paquete APK v${state.version}. Activa «Instalar apps desconocidas» para aplicar la actualización directamente.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is AppUpdateUiState.ReadyToInstall -> {
                        Text(
                            text = "Paquete APK v${state.version} verificado. Pulsa Instalar para actualizar AndAmp.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WinampColors.LcdGreen
                        )
                    }
                    is AppUpdateUiState.Error -> {
                        Text(
                            text = state.message,
                            color = WinampColors.LcdPeakRed,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateUiState.Available -> {
                    TextButton(
                        onClick = { onDownloadAndInstall(state.info) },
                        modifier = Modifier.testTag("update_download_btn")
                    ) {
                        Text("DESCARGAR E INSTALAR", color = WinampColors.LcdGreen, fontWeight = FontWeight.Bold)
                    }
                }
                is AppUpdateUiState.NeedsInstallPermission -> {
                    TextButton(onClick = onOpenPermissionSettings) {
                        Text("PERMITIR E INSTALAR", color = WinampColors.EqGoldBright, fontWeight = FontWeight.Bold)
                    }
                }
                is AppUpdateUiState.ReadyToInstall -> {
                    TextButton(onClick = { onInstallReadyApk(state.apkFile) }) {
                        Text("INSTALAR AHORA", color = WinampColors.LcdGreen, fontWeight = FontWeight.Bold)
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is AppUpdateUiState.Downloading) {
                Row {
                    if (state is AppUpdateUiState.Available) {
                        TextButton(onClick = { onDismiss(true) }) {
                            Text("Omitir", color = WinampColors.TextSecondary)
                        }
                    }
                    TextButton(onClick = { onDismiss(false) }) {
                        Text("Cerrar", color = WinampColors.EqGoldBright)
                    }
                }
            }
        }
    )
}

