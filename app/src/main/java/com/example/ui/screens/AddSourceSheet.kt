package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.model.DiscoveredSmbHost
import com.example.domain.model.Playlist
import com.example.domain.model.SmbBrowserEntry
import com.example.domain.model.SmbServerProfile
import com.example.domain.model.Track
import com.example.domain.model.YouTubePlaylistSummary
import com.example.ui.components.WinampMetallicButton
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WinampColors
import com.example.ui.viewmodel.LanScanUiState
import com.example.ui.viewmodel.SmbBrowserUiState
import com.example.ui.viewmodel.YouTubeSearchUiState

private enum class AddSourceTab(val label: String) {
    LOCAL("LOCAL"),
    NAS("NAS / SMB"),
    YOUTUBE("YOUTUBE")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceSheet(
    activePlaylist: Playlist?,
    allTracks: List<Track>,
    isImporting: Boolean,
    smbServers: List<SmbServerProfile>,
    smbBrowserState: SmbBrowserUiState,
    lanScanState: LanScanUiState,
    youTubeSearchState: YouTubeSearchUiState,
    recentYouTubePlaylists: List<YouTubePlaylistSummary>,
    onDismiss: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onAddTrackToPlaylist: (Long, Long) -> Unit,
    onScanLanSmb: () -> Unit,
    onConnectDiscoveredSmb: (DiscoveredSmbHost, String, Boolean, String, String) -> Unit,
    onSaveAndConnectSmb: (SmbServerProfile) -> Unit,
    onDeleteSmbServer: (Long) -> Unit,
    onOpenSmbServer: (SmbServerProfile) -> Unit,
    onNavigateSmbEntry: (SmbBrowserEntry) -> Unit,
    onNavigateSmbUp: () -> Unit,
    onCloseSmbBrowser: () -> Unit,
    onImportAllSmbInFolder: () -> Unit,
    onYouTubeQueryChange: (String) -> Unit,
    onSearchYouTube: () -> Unit,
    onPlayYouTubePlaylist: (YouTubePlaylistSummary) -> Unit,
    onDeleteRecentYouTube: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableStateOf(AddSourceTab.LOCAL) }

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
            Text(
                text = "AÑADIR AUDIO / RED / YOUTUBE",
                style = MaterialTheme.typography.titleMedium,
                color = WinampColors.TitleGold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AddSourceTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    WinampMetallicButton(
                        onClick = { activeTab = tab },
                        testTag = "add_tab_${tab.name.lowercase()}",
                        contentDesc = "Fuente ${tab.label}",
                        showLed = true,
                        ledActive = selected,
                        minHeight = 40.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = tab.label,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (selected) WinampColors.LcdGreen else WinampColors.TitleSilver
                        )
                    }
                }
            }

            when (activeTab) {
                AddSourceTab.LOCAL -> LocalImportTabContent(
                    activePlaylist = activePlaylist,
                    allTracks = allTracks,
                    isImporting = isImporting,
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    onAddTrackToPlaylist = onAddTrackToPlaylist
                )

                AddSourceTab.NAS -> SambaNasTabContent(
                    servers = smbServers,
                    browserState = smbBrowserState,
                    lanScanState = lanScanState,
                    onScanLan = onScanLanSmb,
                    onConnectDiscovered = onConnectDiscoveredSmb,
                    onSaveAndConnectServer = onSaveAndConnectSmb,
                    onDeleteServer = onDeleteSmbServer,
                    onOpenServer = onOpenSmbServer,
                    onNavigateEntry = onNavigateSmbEntry,
                    onNavigateUp = onNavigateSmbUp,
                    onCloseBrowser = onCloseSmbBrowser,
                    onImportAllInFolder = onImportAllSmbInFolder,
                    onPlayStarted = onDismiss
                )

                AddSourceTab.YOUTUBE -> YouTubeSearchTabContent(
                    searchState = youTubeSearchState,
                    recentPlaylists = recentYouTubePlaylists,
                    onQueryChange = onYouTubeQueryChange,
                    onSearch = onSearchYouTube,
                    onPlayPlaylist = { pl ->
                        onPlayYouTubePlaylist(pl)
                        onDismiss()
                    },
                    onDeleteRecent = onDeleteRecentYouTube
                )
            }
        }
    }
}

@Composable
private fun LocalImportTabContent(
    activePlaylist: Playlist?,
    allTracks: List<Track>,
    isImporting: Boolean,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onAddTrackToPlaylist: (Long, Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isImporting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WinampColors.LcdGreen,
                trackColor = WinampColors.LcdBlack
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WinampMetallicButton(
                onClick = onImportFiles,
                testTag = "sheet_import_files",
                contentDesc = "Importar archivos de audio",
                minHeight = 48.dp,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = null,
                    tint = WinampColors.EqGoldBright,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ARCHIVOS",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = WinampColors.TextPrimary
                )
            }

            WinampMetallicButton(
                onClick = onImportFolder,
                testTag = "sheet_import_folder",
                contentDesc = "Importar carpeta completa",
                minHeight = 48.dp,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.CreateNewFolder,
                    contentDescription = null,
                    tint = WinampColors.LcdGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "CARPETA",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = WinampColors.TextPrimary
                )
            }
        }

        if (activePlaylist != null) {
            HorizontalDivider(color = WinampColors.BevelHighlight.copy(alpha = 0.4f))
            Text(
                text = "Añadir pistas existentes a «${activePlaylist.name}»",
                style = MaterialTheme.typography.titleSmall,
                color = WinampColors.TitleSilver
            )
            val existingIds = remember(activePlaylist) { activePlaylist.tracks.map { it.id }.toSet() }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(allTracks, key = { it.id }) { track ->
                    val alreadyIn = track.id in existingIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !alreadyIn) {
                                onAddTrackToPlaylist(activePlaylist.id, track.id)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${track.artist} - ${track.title}",
                                fontFamily = JetBrainsMonoFamily,
                                color = if (alreadyIn) WinampColors.LcdGreenDim else WinampColors.LcdGreen,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.technicalSpecLabel,
                                fontFamily = JetBrainsMonoFamily,
                                color = WinampColors.TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Icon(
                            imageVector = if (alreadyIn) Icons.Filled.Check else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = if (alreadyIn) "En lista" else "Añadir a lista",
                            tint = if (alreadyIn) WinampColors.LcdGreenDim else WinampColors.EqGoldBright
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SambaNasTabContent(
    servers: List<SmbServerProfile>,
    browserState: SmbBrowserUiState,
    lanScanState: LanScanUiState,
    onScanLan: () -> Unit,
    onConnectDiscovered: (DiscoveredSmbHost, String, Boolean, String, String) -> Unit,
    onSaveAndConnectServer: (SmbServerProfile) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenServer: (SmbServerProfile) -> Unit,
    onNavigateEntry: (SmbBrowserEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onCloseBrowser: () -> Unit,
    onImportAllInFolder: () -> Unit,
    onPlayStarted: () -> Unit
) {
    var showInlineForm by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SmbServerProfile?>(null) }
    var selectedHostForAuth by remember { mutableStateOf<DiscoveredSmbHost?>(null) }

    val activeServer = browserState.activeServer

    LaunchedEffect(Unit) {
        if (!lanScanState.hasScannedOnce && !lanScanState.isScanning) {
            onScanLan()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (activeServer == null) {
            // Streamlined top action bar: Scan LAN + Manual inline form
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WinampMetallicButton(
                    onClick = onScanLan,
                    testTag = "smb_scan_lan_button",
                    contentDesc = "Escanear red Wi-Fi buscando servidores NAS y Samba",
                    showLed = true,
                    ledActive = lanScanState.isScanning,
                    minHeight = 46.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (lanScanState.isScanning) Icons.Filled.Refresh else Icons.Filled.WifiFind,
                        contentDescription = null,
                        tint = WinampColors.LcdGreen,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (lanScanState.isScanning) "ESCANEANDO..." else "ESCANEAR RED LAN",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = WinampColors.LcdGreen
                    )
                }

                WinampMetallicButton(
                    onClick = {
                        editingServer = null
                        showInlineForm = !showInlineForm
                    },
                    testTag = "smb_manual_toggle_button",
                    contentDesc = "Agregar servidor manualmente",
                    showLed = showInlineForm,
                    ledActive = showInlineForm,
                    minHeight = 46.dp
                ) {
                    Icon(
                        imageVector = if (showInlineForm) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = null,
                        tint = WinampColors.EqGoldBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "MANUAL",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = WinampColors.TitleGold
                    )
                }
            }

            if (lanScanState.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = WinampColors.LcdGreen,
                    trackColor = WinampColors.LcdBlack
                )
            }

            // Inline compact manual / edit form (replaces clunky nested dialog)
            if (showInlineForm) {
                InlineSmbFormCard(
                    initial = editingServer,
                    onCancel = {
                        showInlineForm = false
                        editingServer = null
                    },
                    onSaveAndConnect = { profile ->
                        onSaveAndConnectServer(profile)
                        showInlineForm = false
                        editingServer = null
                    }
                )
            }

            // Discovered hosts on LAN
            if (lanScanState.discoveredHosts.isNotEmpty() || lanScanState.errorMessage != null) {
                Text(
                    text = if (lanScanState.discoveredHosts.isNotEmpty()) {
                        "Equipos detectados en tu red (${lanScanState.discoveredHosts.size})"
                    } else {
                        "Escaneo de red"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = WinampColors.TitleSilver
                )
            }

            lanScanState.errorMessage?.takeIf { lanScanState.discoveredHosts.isEmpty() }?.let { msg ->
                Surface(
                    color = WinampColors.LcdBlack,
                    border = BorderStroke(1.dp, WinampColors.BevelShadow),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$msg. Usa MANUAL si tu NAS está en otra subred o puerto.",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.5.sp,
                        color = WinampColors.LcdGreenDim,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (lanScanState.discoveredHosts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lanScanState.discoveredHosts.forEach { host ->
                        val isExpanded = selectedHostForAuth?.host == host.host
                        Surface(
                            color = WinampColors.ChassisSurfaceBottom,
                            border = BorderStroke(
                                1.dp,
                                if (isExpanded) WinampColors.EqGoldBright else WinampColors.BevelHighlight.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("lan_discovered_host_${host.host}")
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedHostForAuth = if (isExpanded) null else host
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Storage,
                                        contentDescription = null,
                                        tint = WinampColors.LcdGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = host.displayName,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp,
                                            color = WinampColors.LcdGreen,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "smb://${host.host}:${host.port} · ${host.source}",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 9.5.sp,
                                            color = WinampColors.TextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                    WinampMetallicButton(
                                        onClick = {
                                            onConnectDiscovered(host, "", true, "", "")
                                        },
                                        testTag = "lan_quick_guest_${host.host}",
                                        contentDesc = "Conectar en 1 toque como invitado a ${host.displayName}",
                                        minHeight = 34.dp
                                    ) {
                                        Text(
                                            text = "ENTRAR",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp,
                                            color = WinampColors.TitleGold
                                        )
                                    }
                                }

                                if (isExpanded) {
                                    QuickDiscoveredAuthRow(
                                        host = host,
                                        onConnect = { share, isGuest, user, pass ->
                                            onConnectDiscovered(host, share, isGuest, user, pass)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (servers.isNotEmpty()) {
                Text(
                    text = "NAS guardados (${servers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = WinampColors.TitleSilver
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    servers.forEach { srv ->
                        Surface(
                            onClick = { onOpenServer(srv) },
                            color = WinampColors.ChassisSurfaceBottom,
                            border = BorderStroke(1.dp, WinampColors.BevelHighlight.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("smb_server_card_${srv.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = WinampColors.LcdGreen)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = srv.alias,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = WinampColors.LcdGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append("smb://${srv.host}")
                                            if (srv.defaultShare.isNotBlank()) append("/${srv.defaultShare}")
                                            append(if (srv.isGuest) " · Invitado" else " · ${srv.username}")
                                        },
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                        color = WinampColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingServer = srv
                                        showInlineForm = true
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Editar NAS", tint = WinampColors.EqGoldBright)
                                }
                                IconButton(
                                    onClick = { onDeleteServer(srv.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Eliminar NAS", tint = WinampColors.LcdPeakRed)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Active SMB Directory Browser
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WinampMetallicButton(
                    onClick = onNavigateUp,
                    testTag = "smb_nav_up",
                    contentDesc = "Subir nivel o volver a servidores",
                    minHeight = 38.dp
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = WinampColors.TitleSilver,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${activeServer.alias} // ${browserState.currentShare}",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = WinampColors.TitleGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (browserState.protocolLabel.isNotBlank()) {
                            Text(
                                text = browserState.protocolLabel,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.5.sp,
                                color = WinampColors.LcdBlack,
                                modifier = Modifier
                                    .background(WinampColors.LcdGreen, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = "/${browserState.currentPath.replace('\\', '/')}",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = WinampColors.LcdGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                WinampMetallicButton(
                    onClick = onImportAllInFolder,
                    testTag = "smb_import_folder",
                    contentDesc = "Importar audios de esta carpeta NAS",
                    minHeight = 38.dp
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = WinampColors.EqGoldBright, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("TODO", fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = WinampColors.TextPrimary)
                }

                TextButton(onClick = onCloseBrowser) {
                    Text("Red", color = WinampColors.TextSecondary)
                }
            }

            if (browserState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = WinampColors.LcdGreen,
                    trackColor = WinampColors.LcdBlack
                )
            }

            browserState.errorMessage?.let { err ->
                Surface(
                    color = WinampColors.LcdBlack,
                    border = BorderStroke(1.dp, WinampColors.LcdPeakRed.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = err,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdPeakRed
                        )
                        WinampMetallicButton(
                            onClick = { onOpenServer(activeServer) },
                            testTag = "smb_retry_btn",
                            contentDesc = "Reintentar conexión con servidor NAS",
                            minHeight = 36.dp
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = WinampColors.EqGoldBright, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "REINTENTAR",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp,
                                color = WinampColors.TitleGold
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!browserState.isLoading && browserState.entries.isEmpty() && browserState.errorMessage == null) {
                    item {
                        Text(
                            text = "Carpeta vacía o sin pistas de audio compatibles.",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdGreenDim,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                items(browserState.entries, key = { "${it.shareName}/${it.relativePath}" }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigateEntry(entry)
                                if (!entry.isDirectory) onPlayStarted()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.AudioFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) WinampColors.EqGoldBright else WinampColors.LcdGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = entry.name,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.5.sp,
                            color = if (entry.isDirectory) WinampColors.TitleGold else WinampColors.LcdGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        entry.formatHint?.let { fmt ->
                            Text(
                                text = fmt.label,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.5.sp,
                                color = WinampColors.EqGoldBright
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDiscoveredAuthRow(
    host: DiscoveredSmbHost,
    onConnect: (String, Boolean, String, String) -> Unit
) {
    var share by remember(host.host) { mutableStateOf("") }
    var useCredentials by remember(host.host) { mutableStateOf(false) }
    var username by remember(host.host) { mutableStateOf("") }
    var password by remember(host.host) { mutableStateOf("") }

    val textColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = WinampColors.LcdBlack,
        unfocusedContainerColor = WinampColors.LcdBlack,
        focusedTextColor = WinampColors.LcdGreen,
        unfocusedTextColor = WinampColors.LcdGreen,
        focusedBorderColor = WinampColors.EqGoldBright
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(color = WinampColors.BevelHighlight.copy(alpha = 0.25f))
        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Carpeta compartida (opcional)") },
            placeholder = { Text("Auto-detecta Music / Media / Shared") },
            singleLine = true,
            colors = textColors,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { useCredentials = !useCredentials }
        ) {
            Checkbox(
                checked = useCredentials,
                onCheckedChange = { useCredentials = it },
                colors = CheckboxDefaults.colors(checkedColor = WinampColors.EqGoldBright)
            )
            Text("Usar usuario y contraseña", fontFamily = JetBrainsMonoFamily, fontSize = 11.sp)
        }
        if (useCredentials) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    colors = textColors,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Clave") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = textColors,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        WinampMetallicButton(
            onClick = { onConnect(share, !useCredentials, username, password) },
            testTag = "lan_connect_with_opts_${host.host}",
            contentDesc = "Conectar a ${host.displayName}",
            minHeight = 40.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "CONECTAR Y EXPLORAR",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = WinampColors.LcdGreen
            )
        }
    }
}

@Composable
private fun InlineSmbFormCard(
    initial: SmbServerProfile?,
    onCancel: () -> Unit,
    onSaveAndConnect: (SmbServerProfile) -> Unit
) {
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var alias by remember(initial) { mutableStateOf(initial?.alias ?: "") }
    var share by remember(initial) { mutableStateOf(initial?.defaultShare ?: "") }
    var portText by remember(initial) { mutableStateOf((initial?.port ?: 445).toString()) }
    var isGuest by remember(initial) { mutableStateOf(initial?.isGuest ?: true) }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }

    val textColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = WinampColors.LcdBlack,
        unfocusedContainerColor = WinampColors.LcdBlack,
        focusedTextColor = WinampColors.LcdGreen,
        unfocusedTextColor = WinampColors.LcdGreen,
        focusedBorderColor = WinampColors.EqGoldBright
    )

    Surface(
        color = WinampColors.ChassisSurfaceBottom,
        border = BorderStroke(1.dp, WinampColors.EqGoldBright.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (initial == null) "Conexión manual Samba / NAS" else "Editar ${initial.alias}",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = WinampColors.TitleGold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP o Host") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    colors = textColors,
                    modifier = Modifier.weight(0.72f).testTag("smb_form_host")
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Puerto") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textColors,
                    modifier = Modifier.weight(0.28f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Nombre (opcional)") },
                    singleLine = true,
                    colors = textColors,
                    modifier = Modifier.weight(1f).testTag("smb_form_alias")
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text("Share / Subcarpeta (opcional)") },
                    placeholder = { Text("USB1_1/MUSICA") },
                    singleLine = true,
                    colors = textColors,
                    modifier = Modifier.weight(1f).testTag("smb_form_share")
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isGuest = !isGuest }
            ) {
                Checkbox(
                    checked = isGuest,
                    onCheckedChange = { isGuest = it },
                    colors = CheckboxDefaults.colors(checkedColor = WinampColors.LcdGreen)
                )
                Text("Acceso como Invitado (sin usuario)", fontFamily = JetBrainsMonoFamily, fontSize = 11.sp)
            }
            if (!isGuest) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Usuario") },
                        singleLine = true,
                        colors = textColors,
                        modifier = Modifier.weight(1f).testTag("smb_form_user")
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = textColors,
                        modifier = Modifier.weight(1f).testTag("smb_form_pass")
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(0.35f)) {
                    Text("Cancelar", color = WinampColors.TextSecondary)
                }
                WinampMetallicButton(
                    onClick = {
                        if (host.isNotBlank()) {
                            onSaveAndConnect(
                                SmbServerProfile(
                                    id = initial?.id ?: 0L,
                                    alias = alias.trim().ifBlank { host.trim() },
                                    host = host.trim(),
                                    port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 445,
                                    defaultShare = share.trim(),
                                    username = if (isGuest) "" else username.trim(),
                                    password = if (isGuest) "" else password,
                                    isGuest = isGuest
                                )
                            )
                        }
                    },
                    testTag = "smb_form_save",
                    contentDesc = "Guardar y conectar servidor NAS",
                    minHeight = 42.dp,
                    modifier = Modifier.weight(0.65f)
                ) {
                    Text(
                        text = "GUARDAR Y CONECTAR",
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

@Composable
private fun YouTubeSearchTabContent(
    searchState: YouTubeSearchUiState,
    recentPlaylists: List<YouTubePlaylistSummary>,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlayPlaylist: (YouTubePlaylistSummary) -> Unit,
    onDeleteRecent: (String) -> Unit
) {
    val displayed = searchState.results.ifEmpty { recentPlaylists }
    val showingRecent = searchState.results.isEmpty() && recentPlaylists.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Buscar playlist o pegar URL...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = WinampColors.LcdGreen) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = WinampColors.LcdBlack,
                    unfocusedContainerColor = WinampColors.LcdBlack,
                    focusedTextColor = WinampColors.LcdGreen,
                    unfocusedTextColor = WinampColors.LcdGreen,
                    focusedBorderColor = WinampColors.EqGoldBright,
                    unfocusedBorderColor = WinampColors.BevelHighlight
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("youtube_search_input")
            )
            WinampMetallicButton(
                onClick = onSearch,
                testTag = "youtube_search_button",
                contentDesc = "Buscar playlists de YouTube",
                minHeight = 52.dp
            ) {
                Text("BUSCAR", fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = WinampColors.TitleGold)
            }
        }

        if (searchState.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WinampColors.LcdGreen,
                trackColor = WinampColors.LcdBlack
            )
        }

        searchState.errorMessage?.let { err ->
            Text(
                text = err,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = WinampColors.LcdPeakRed
            )
        }

        if (showingRecent) {
            Text(
                text = "Playlists recientes de YouTube",
                style = MaterialTheme.typography.titleSmall,
                color = WinampColors.TitleSilver
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .background(WinampColors.LcdBlack, RoundedCornerShape(3.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (displayed.isEmpty() && !searchState.isSearching) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = "Escribe un género, artista o pega un enlace de playlist de YouTube para reproducir remotamente.",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = WinampColors.LcdGreenDim
                        )
                    }
                }
            }
            items(displayed, key = { it.playlistId }) { pl ->
                Surface(
                    onClick = { onPlayPlaylist(pl) },
                    color = WinampColors.ChassisSurfaceBottom,
                    border = BorderStroke(1.dp, WinampColors.BevelHighlight.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("yt_playlist_row_${pl.playlistId}")
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (pl.thumbnailUrl.isNotBlank()) {
                            AsyncImage(
                                model = pl.thumbnailUrl,
                                contentDescription = pl.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 56.dp, height = 42.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.VideoLibrary,
                                contentDescription = null,
                                tint = WinampColors.EqGoldBright,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.title,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = WinampColors.LcdGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(pl.channelTitle)
                                    if (pl.videoCountText.isNotBlank()) append(" · ${pl.videoCountText}")
                                },
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.5.sp,
                                color = WinampColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (showingRecent) {
                            IconButton(
                                onClick = { onDeleteRecent(pl.playlistId) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Quitar historial", tint = WinampColors.LcdPeakRed)
                            }
                        }

                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Reproducir playlist",
                            tint = WinampColors.EqGoldBright
                        )
                    }
                }
            }
        }
    }
}
