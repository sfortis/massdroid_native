package net.asksakis.massdroidv2.ui.screens.home

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.ui.components.EqualizerBars
import net.asksakis.massdroidv2.ui.components.SoundWaveIcon
import net.asksakis.massdroidv2.ui.components.PlayerNameWithBadge
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.SubcomposeAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRoomSetup: (roomId: String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    val selectedPlayer by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val suppressConnectionPrompt by viewModel.suppressConnectionPrompt.collectAsStateWithLifecycle()
    val sendspinClientId by viewModel.sendspinClientId.collectAsStateWithLifecycle()
    val isAaProjecting by viewModel.isAaProjecting.collectAsStateWithLifecycle()
    val sleepTimerTargetPlayerId by viewModel.sleepTimerTargetPlayerId.collectAsStateWithLifecycle()
    val dstmStates by viewModel.queueDstmStates.collectAsStateWithLifecycle()
    val proximityConfig by viewModel.proximityConfig.collectAsStateWithLifecycle(
        initialValue = net.asksakis.massdroidv2.data.proximity.ProximityConfig()
    )
    val currentDetectedRoom by viewModel.currentDetectedRoom.collectAsStateWithLifecycle()
    val playerRoomMap = remember(proximityConfig) {
        proximityConfig.rooms.groupBy { it.playerId }.mapValues { (_, rooms) -> rooms.map { it.name } }
    }
    val playerRoomIdMap = remember(proximityConfig) {
        proximityConfig.rooms.associate { it.playerId to it.id }
    }
    // The MA UI lists BOTH the group and its individual players, and every player
    // stays independently playable. We mirror that: individual players and groups go
    // into two labelled sections and nothing is ever hidden. We used to drop any
    // player that appeared in some parent's group_childs, which permanently hid
    // Cast/sync-group members so you could not play to them on their own (#55).
    // group_childs is now used only to label a group card with its member names.
    val available = remember(players) { players.filter { it.available } }
    val playerCards = remember(available) {
        available.filter { it.type != PlayerType.GROUP }
            .sortedBy { it.displayName.lowercase() }
    }
    val groupCards = remember(available) {
        available.filter { it.type == PlayerType.GROUP }
            .sortedBy { it.displayName.lowercase() }
    }
    val groupMembers = remember(available) {
        val playerById = available.associateBy { it.playerId }
        available.filter { it.type == PlayerType.GROUP }.associate { group ->
            group.playerId to group.groupChilds
                .filter { it != group.playerId }
                .mapNotNull { playerById[it] }
                .sortedBy { it.displayName.lowercase() }
        }
    }
    val activePlayerCount = remember(available) {
        available.count { it.state == PlaybackState.PLAYING }
    }
    val assignedRoomCount = remember(playerCards, playerRoomMap) {
        playerCards.flatMap { playerRoomMap[it.playerId].orEmpty() }.distinct().size
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isInitializing || connectionState is ConnectionState.Connecting || suppressConnectionPrompt) {
                ConnectingIndicator()
            } else when (connectionState) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    ConnectionPrompt(
                        state = connectionState,
                        onSettings = onNavigateToSettings,
                    )
                }
                is ConnectionState.Connecting -> { /* handled above */ }
                is ConnectionState.Connected -> {
                    var iconPickerPlayer by remember { mutableStateOf<Player?>(null) }
                    var queueMenuPlayer by remember { mutableStateOf<Player?>(null) }
                    var settingsPlayer by remember { mutableStateOf<Player?>(null) }
                    var groupPlayer by remember { mutableStateOf<Player?>(null) }
                    var syncSpeakersFor by remember { mutableStateOf<Player?>(null) }
                    var showCreateGroup by remember { mutableStateOf(false) }

                    val groupCandidates = remember(players) {
                        players.filter { it.available && it.type != PlayerType.GROUP }
                            .sortedBy { it.displayName.lowercase() }
                    }

                    PlayersHeader(
                        totalPlayers = playerCards.size,
                        activePlayers = activePlayerCount,
                        assignedRooms = assignedRoomCount,
                        groups = groupCards.size,
                        onCreateGroup = if (groupCandidates.size >= 2) {
                            { showCreateGroup = true }
                        } else null
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f).fadingEdges(),
                        contentPadding = PaddingValues(start = 0.dp, top = 6.dp, end = 0.dp, bottom = LocalMiniPlayerPadding.current)
                    ) {
                        if (playerCards.isNotEmpty()) {
                            item(key = "header_players") {
                                PlayersSectionHeader("PLAYERS")
                            }
                            items(playerCards, key = { it.playerId }) { player ->
                                PlayerListItem(
                                    player = player,
                                    isSelected = player.playerId == selectedPlayer?.playerId,
                                    isLocalPlayer = sendspinClientId != null &&
                                        player.playerId == sendspinClientId,
                                    isAndroidAutoPlayer = isAaProjecting &&
                                        sendspinClientId != null &&
                                        player.playerId == sendspinClientId,
                                    isFollowMeSelected = proximityConfig.enabled &&
                                        currentDetectedRoom?.playerId == player.playerId,
                                    hasSleepTimer = sleepTimerTargetPlayerId == player.playerId,
                                    roomNames = playerRoomMap[player.playerId] ?: emptyList(),
                                    onClick = { viewModel.selectPlayer(player) },
                                    onIconLongPress = { iconPickerPlayer = player },
                                    onQueueMenuClick = { queueMenuPlayer = player },
                                    onVolumeChange = { viewModel.setVolume(player.playerId, it) }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        if (groupCards.isNotEmpty()) {
                            item(key = "header_groups") {
                                PlayersSectionHeader("GROUPS")
                            }
                            items(groupCards, key = { it.playerId }) { group ->
                                PlayerListItem(
                                    player = group,
                                    isSelected = group.playerId == selectedPlayer?.playerId,
                                    isFollowMeSelected = proximityConfig.enabled &&
                                        currentDetectedRoom?.playerId == group.playerId,
                                    hasSleepTimer = sleepTimerTargetPlayerId == group.playerId,
                                    roomNames = playerRoomMap[group.playerId] ?: emptyList(),
                                    isGroup = true,
                                    members = groupMembers[group.playerId].orEmpty(),
                                    onClick = { viewModel.selectPlayer(group) },
                                    onIconLongPress = { iconPickerPlayer = group },
                                    onQueueMenuClick = { queueMenuPlayer = group },
                                    onVolumeChange = { viewModel.setGroupVolume(group.playerId, it) },
                                    onMemberVolumeChange = { memberId, volume ->
                                        viewModel.onMemberVolumeChanged(group.playerId, memberId, volume)
                                    }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }

                    iconPickerPlayer?.let { player ->
                        IconPickerDialog(
                            playerName = player.displayName,
                            currentIcon = player.icon,
                            onIconSelected = { mdiName ->
                                viewModel.updatePlayerIcon(player.playerId, mdiName)
                                iconPickerPlayer = null
                            },
                            onDismiss = { iconPickerPlayer = null }
                        )
                    }

                    queueMenuPlayer?.let { player ->
                        PlayerQueueSheet(
                            player = player,
                            allPlayers = players.filter { it.available },
                            sendspinClientId = sendspinClientId,
                            playerRoomMap = playerRoomMap,
                            onPlayerSettings = {
                                settingsPlayer = player
                                queueMenuPlayer = null
                            },
                            onConfigureRoom = playerRoomIdMap[player.playerId]?.let { roomId ->
                                { onNavigateToRoomSetup(roomId) }
                            },
                            onGroupWith = {
                                groupPlayer = player
                                queueMenuPlayer = null
                            },
                            onSyncSpeakers = {
                                syncSpeakersFor = player
                                queueMenuPlayer = null
                            },
                            onDeleteGroup = if (player.type == PlayerType.GROUP) {
                                {
                                    viewModel.deleteGroup(player.playerId)
                                    queueMenuPlayer = null
                                }
                            } else null,
                            // Show "Break sync" when the protocol sync is active OUTSIDE
                            // a managed group (i.e. stale sendspin sync left over after
                            // a sync_group was removed). Inside a managed group the user
                            // edits via the group parent card, not the member.
                            onBreakSync = if (
                                player.type != PlayerType.GROUP &&
                                player.activeGroup.isNullOrEmpty() &&
                                (player.syncedTo != null ||
                                    player.groupChilds.any { it != player.playerId })
                            ) {
                                {
                                    viewModel.breakSyncForPlayer(player.playerId)
                                    queueMenuPlayer = null
                                }
                            } else null,
                            onPowerToggle = if ("power" in player.supportedFeatures) {
                                { powered ->
                                    viewModel.setPlayerPower(player.playerId, powered)
                                    queueMenuPlayer = null
                                }
                            } else null,
                            onClearQueue = {
                                viewModel.clearQueue(player.playerId)
                                queueMenuPlayer = null
                            },
                            onTransferQueue = { targetId ->
                                viewModel.transferQueue(player.playerId, targetId)
                                queueMenuPlayer = null
                            },
                            onStartSongRadio = {
                                player.currentMedia?.uri?.let { uri ->
                                    viewModel.startSongRadio(player.playerId, uri)
                                }
                            },
                            onDismiss = { queueMenuPlayer = null }
                        )
                    }

                    settingsPlayer?.let { player ->
                        val audioFormat by viewModel.sendspinAudioFormat.collectAsStateWithLifecycle()
                        val syncDelayMs by viewModel.sendspinSyncDelayMs.collectAsStateWithLifecycle(initialValue = 0)
                        val syncHistory by viewModel.sendspinSyncHistory.collectAsStateWithLifecycle()
                        // Acoustic calibration shares the same coordinator that
                        // backs the NowPlaying player-settings sheet, so the
                        // Players screen surfaces an identical Bluetooth row
                        // and saves are visible to both screens immediately.
                        // isBtRoute / route name come from the routed AudioTrack
                        // device (SendspinManager's snapshot), not from a UI
                        // flag, so they reflect the actual output path.
                        val isLocal = sendspinClientId != null && player.playerId == sendspinClientId
                        val isBt = viewModel.acoustic.isBtRoute()
                        val calibrations by viewModel.acoustic.acousticRouteCalibrations.collectAsStateWithLifecycle(initialValue = emptyMap())
                        val micPathUs by viewModel.acoustic.acousticMicPathUs.collectAsStateWithLifecycle(initialValue = 0L)
                        val btRouteKey = viewModel.acoustic.getBtRouteKey()
                        val acousticCorrectionMs = (calibrations[btRouteKey]?.correctionUs ?: 0L) / 1000
                        if (audioFormat == null) return@let // wait for DataStore
                        net.asksakis.massdroidv2.ui.components.PlayerSettingsDialog(
                            player = player,
                            initialDstmEnabled = dstmStates[player.playerId] ?: false,
                            isSendspinPlayer = player.provider == "sendspin",
                            isLocalPlayer = isLocal,
                            initialAudioFormat = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(audioFormat!!),
                            initialSyncDelayMs = syncDelayMs,
                            onLoadConfig = { viewModel.getPlayerConfig(it) },
                            onSave = { id, values -> viewModel.savePlayerConfig(id, values) },
                            onDstmChanged = { viewModel.setDontStopTheMusic(player.playerId, it) },
                            onAudioFormatChanged = { viewModel.setAudioFormat(it) },
                            onSyncDelayChanged = { viewModel.setSendspinSyncDelayMs(it) },
                            isBtRoute = isBt,
                            acousticCorrectionMs = acousticCorrectionMs.toInt(),
                            acoustic = viewModel.acoustic,
                            micPathCalibratedMs = micPathUs / 1000,
                            isPlaybackActive = viewModel.acoustic.isPlaybackActive(),
                            onPausePlayback = { viewModel.acoustic.pauseForCalibration() },
                            onResumePlayback = { viewModel.acoustic.resumeAfterCalibration() },
                            btRouteName = viewModel.acoustic.getBtRouteName(),
                            onResetBtCalibration = { viewModel.acoustic.resetCalibration() },
                            onResetMicPath = { viewModel.acoustic.resetMicPath() },
                            syncHistory = syncHistory,
                            onDismiss = { settingsPlayer = null }
                        )
                    }

                    groupPlayer?.let { player ->
                        net.asksakis.massdroidv2.ui.components.GroupPlayersSheet(
                            targetPlayer = player,
                            allPlayers = players.filter { it.available },
                            onApply = { selectedIds ->
                                android.util.Log.d("PlayersScreen", "GroupSheet onApply: target=${player.playerId} selected=$selectedIds childs=${player.groupChilds}")
                                viewModel.applyGroupMembers(player.playerId, player.groupChilds, selectedIds)
                            },
                            onJoinLeader = { leaderId ->
                                viewModel.addPlayerToGroup(leaderId, player.playerId)
                            },
                            onDismiss = { groupPlayer = null }
                        )
                    }

                    syncSpeakersFor?.let { gp ->
                        // Members of the sync group the opened player belongs to:
                        // its own group_childs if it is the leader, else the
                        // leader's. MA lists the leader inside its own childs.
                        val groupIds = remember(gp.playerId, players) {
                            when {
                                gp.groupChilds.isNotEmpty() -> gp.groupChilds.toSet()
                                gp.activeGroup != null ->
                                    players.firstOrNull { gp.playerId in it.groupChilds }
                                        ?.groupChilds?.toSet() ?: setOf(gp.playerId)
                                else -> setOf(gp.playerId)
                            }
                        }
                        val members = remember(groupIds, players) {
                            players.filter { it.playerId in groupIds && it.available }
                        }
                        val localSyncDelayMs by viewModel.sendspinSyncDelayMs
                            .collectAsStateWithLifecycle(initialValue = 0)
                        net.asksakis.massdroidv2.ui.components.SyncSpeakersSheet(
                            members = members,
                            ourPlayerId = sendspinClientId,
                            localSyncDelayMs = localSyncDelayMs,
                            onLocalSyncDelayChanged = { viewModel.setSendspinSyncDelayMs(it) },
                            onLoadConfig = { viewModel.getPlayerConfig(it) },
                            onSave = { id, values -> viewModel.savePlayerConfig(id, values) },
                            onDismiss = { syncSpeakersFor = null }
                        )
                    }

                    if (showCreateGroup) {
                        net.asksakis.massdroidv2.ui.components.CreateGroupDialog(
                            candidates = groupCandidates,
                            loadProviders = { viewModel.loadGroupProviders() },
                            onConfirm = { provider, name, memberIds ->
                                viewModel.createGroup(provider, name, memberIds)
                            },
                            onDismiss = { showCreateGroup = false }
                        )
                    }

                }
            }
        }
    }

}

@Composable
private fun PlayersHeader(
    totalPlayers: Int,
    activePlayers: Int,
    assignedRooms: Int,
    groups: Int = 0,
    onCreateGroup: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$totalPlayers players · $activePlayers active · $groups groups · $assignedRooms rooms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (onCreateGroup != null) {
            MdIconButton(onClick = onCreateGroup) {
                Icon(
                    Icons.Default.SpeakerGroup,
                    contentDescription = "Create group",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerListItem(
    player: Player,
    isSelected: Boolean,
    isLocalPlayer: Boolean = false,
    isAndroidAutoPlayer: Boolean = false,
    isFollowMeSelected: Boolean = false,
    hasSleepTimer: Boolean = false,
    roomNames: List<String> = emptyList(),
    isGroup: Boolean = false,
    members: List<Player> = emptyList(),
    onClick: () -> Unit,
    onIconLongPress: () -> Unit,
    onQueueMenuClick: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onMemberVolumeChange: (String, Int) -> Unit = { _, _ -> }
) {
    // For group players the server maintains a separate `group_volume` (average of
    // children). Plain players use their own volume_level.
    val effectiveVolume = player.groupVolume ?: player.volumeLevel
    var volumeSliderValue by remember { mutableFloatStateOf(effectiveVolume.toFloat()) }

    LaunchedEffect(effectiveVolume) {
        volumeSliderValue = effectiveVolume.toFloat()
    }

    val isPlaying = player.state == PlaybackState.PLAYING
    val accentColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isPlaying -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Neutral-grey weighting: idle cards read darker, the actively-playing card
    // reads lighter, and the SELECTED player is marked by a primary border rather
    // than a tinted fill (clearer affordance, theme-agnostic).
    val containerColor = when {
        isPlaying -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val selectionBorder = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    val title = player.currentMedia?.title
    val artist = player.currentMedia?.artist
    val groupAccentColor = MaterialTheme.colorScheme.tertiary

    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = selectionBorder,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SoundWaveIcon(
                        isPlaying = isPlaying,
                        waveColor = accentColor,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onIconLongPress
                        )
                    ) {
                        PlayerIcon(
                            player = player,
                            modifier = Modifier.size(20.dp),
                            tint = accentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlayerNameWithBadge(
                            name = player.displayName,
                            isLocalPlayer = isLocalPlayer,
                            isAndroidAutoPlayer = isAndroidAutoPlayer,
                            isFollowMePlayer = isFollowMeSelected,
                            fontWeight = FontWeight.Bold
                        )
                        if (isGroup) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                tint = groupAccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (hasSleepTimer) {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = "Sleep timer active",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                MdIconButton(onClick = onQueueMenuClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Player options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (!title.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (!artist.isNullOrBlank()) "$title • $artist" else title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (roomNames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    roomNames.forEach { room ->
                        RoomChip(roomName = room, isActive = isSelected)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Slider(
                    value = volumeSliderValue,
                    onValueChange = { volumeSliderValue = it },
                    onValueChangeFinished = { onVolumeChange(volumeSliderValue.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                    thumb = {},
                    track = { sliderState ->
                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start))
                            .coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(accentColor)
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${volumeSliderValue.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    textAlign = TextAlign.End
                )
            }

            // Per-member volume right inside the group card (handy for balancing the
            // group). The members are also their own cards in the PLAYERS section, so
            // these rows are volume-only - no per-member menu or select.
            if (isGroup && members.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
                members.forEach { member ->
                    GroupMemberVolumeRow(
                        member = member,
                        onVolumeChange = { onMemberVolumeChange(member.playerId, it) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMemberVolumeRow(
    member: Player,
    onVolumeChange: (Int) -> Unit
) {
    var volume by remember { mutableFloatStateOf(member.volumeLevel.toFloat()) }
    LaunchedEffect(member.volumeLevel) { volume = member.volumeLevel.toFloat() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIcon(
            player = member,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Slider(
            value = volume,
            onValueChange = { volume = it },
            onValueChangeFinished = { onVolumeChange(volume.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            thumb = {},
            track = { sliderState ->
                val fraction = ((sliderState.value - sliderState.valueRange.start) /
                    (sliderState.valueRange.endInclusive - sliderState.valueRange.start))
                    .coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${volume.toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PlayersSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp)
    )
}

@Composable
private fun RoomChip(roomName: String, isActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (isActive) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)
    ) {
        Text(
            text = roomName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerQueueSheet(
    player: Player,
    allPlayers: List<Player>,
    sendspinClientId: String?,
    playerRoomMap: Map<String, List<String>> = emptyMap(),
    onPlayerSettings: () -> Unit,
    onConfigureRoom: (() -> Unit)? = null,
    onGroupWith: () -> Unit,
    onSyncSpeakers: (() -> Unit)? = null,
    onDeleteGroup: (() -> Unit)? = null,
    onBreakSync: (() -> Unit)? = null,
    onPowerToggle: ((powered: Boolean) -> Unit)? = null,
    onClearQueue: () -> Unit,
    onTransferQueue: (targetId: String) -> Unit,
    onStartSongRadio: () -> Unit,
    onDismiss: () -> Unit
) {
    var showTransferList by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val otherPlayers = remember(allPlayers, player.playerId) {
        allPlayers.filter { it.playerId != player.playerId }.sortedBy { it.displayName.lowercase() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column {
                SheetDefaults.HeaderTitle(
                    text = player.displayName,
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            }
            if (!showTransferList) {
                // Section flags so dividers fall only BETWEEN logical groups.
                // Hide grouping entry when this player is managed by a sync_group
                // parent: editing members here would bypass the group and cause
                // the server to repeatedly re-sync the removed members.
                val canEditGroup = "set_members" in player.supportedFeatures &&
                    player.activeGroup == null
                val canSyncSpeakers = onSyncSpeakers != null &&
                    (player.groupChilds.isNotEmpty() || player.activeGroup != null)
                val hasSyncSection = canEditGroup || canSyncSpeakers || onBreakSync != null
                val hasDangerSection = onPowerToggle != null || onDeleteGroup != null

                // --- Config ---
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Player Settings") },
                    leadingContent = {
                        Icon(Icons.Default.Tune, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onPlayerSettings)
                )
                if (onConfigureRoom != null) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Configure Room") },
                        leadingContent = {
                            Icon(Icons.Default.MeetingRoom, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onConfigureRoom()
                            onDismiss()
                        }
                    )
                }

                // --- Queue ---
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (otherPlayers.isNotEmpty()) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Transfer Queue") },
                        leadingContent = {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable { showTransferList = true }
                    )
                }
                if (player.currentMedia?.uri != null) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Start Song Radio") },
                        leadingContent = {
                            Icon(Icons.Default.Radio, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onStartSongRadio()
                            onDismiss()
                        }
                    )
                }
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Clear Queue") },
                    leadingContent = {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onClearQueue)
                )

                // --- Sync / Group ---
                if (hasSyncSection) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (canEditGroup) {
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = { Text(if (player.groupChilds.isNotEmpty()) "Edit Sync Group..." else "Synchronize with...") },
                            leadingContent = {
                                Icon(Icons.Default.SpeakerGroup, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                onGroupWith()
                                onDismiss()
                            }
                        )
                    }
                    // Tune each member's sync delay to align the group by ear.
                    if (canSyncSpeakers) {
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = { Text("Sync speakers...") },
                            leadingContent = {
                                Icon(Icons.Default.GraphicEq, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                onSyncSpeakers!!()
                                onDismiss()
                            }
                        )
                    }
                    if (onBreakSync != null) {
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = { Text("Break sync") },
                            supportingContent = {
                                Text(
                                    "Leave the sendspin sync group",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.LinkOff, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                onBreakSync()
                                onDismiss()
                            }
                        )
                    }
                }

                // --- Power / destructive ---
                if (hasDangerSection) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (onPowerToggle != null) {
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = {
                                Text(if (player.powered) "Power off" else "Power on")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = if (player.powered) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable {
                                onPowerToggle(!player.powered)
                                onDismiss()
                            }
                        )
                    }
                    if (onDeleteGroup != null) {
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = {
                                Text(
                                    "Delete Group",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            modifier = Modifier.clickable {
                                onDeleteGroup()
                                onDismiss()
                            }
                        )
                    }
                }
            } else {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = {
                        Text("Transfer queue to", style = MaterialTheme.typography.labelMedium)
                    },
                        leadingContent = {
                            MdIconButton(onClick = { showTransferList = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                otherPlayers.forEach { target ->
                    val isPlaying = target.state == PlaybackState.PLAYING
                    val iconTint = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = {
                            PlayerNameWithBadge(
                                name = target.displayName,
                                isLocalPlayer = sendspinClientId != null && target.playerId == sendspinClientId
                            )
                        },
                        leadingContent = {
                            SoundWaveIcon(
                                isPlaying = isPlaying,
                                waveColor = iconTint
                            ) {
                                PlayerIcon(
                                    player = target,
                                    modifier = Modifier.size(32.dp),
                                    tint = iconTint
                                )
                            }
                        },
                        modifier = Modifier.clickable { onTransferQueue(target.playerId) }
                    )
                }
            }
        }
    }
}

private data class IconOption(val mdiName: String, val label: String, val icon: ImageVector)

private val iconOptions = listOf(
    IconOption("mdi-speaker", "Speaker", Icons.Default.Speaker),
    IconOption("mdi-speaker-multiple", "Group", Icons.Default.SpeakerGroup),
    IconOption("mdi-cast", "Cast", Icons.Default.Cast),
    IconOption("mdi-cast-connected", "Cast Connected", Icons.Default.CastConnected),
    IconOption("mdi-television", "TV", Icons.Default.Tv),
    IconOption("mdi-cellphone", "Phone", Icons.Default.PhoneAndroid),
    IconOption("mdi-laptop", "Laptop", Icons.Default.Laptop),
    IconOption("mdi-radio", "Radio", Icons.Default.Radio),
    IconOption("mdi-headphones", "Headphones", Icons.Default.Headphones),
    IconOption("mdi-bluetooth", "Bluetooth", Icons.Default.Bluetooth),
    IconOption("mdi-music-note", "Music", Icons.Default.MusicNote),
    IconOption("mdi-monitor", "Monitor", Icons.Default.Monitor)
)

@Composable
private fun IconPickerDialog(
    playerName: String,
    currentIcon: String?,
    onIconSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentNormalized = currentIcon?.replace(":", "-")?.lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playerName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(iconOptions) { option ->
                    val isCurrentIcon = currentNormalized == option.mdiName
                    val containerColor = if (isCurrentIcon)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                    val contentColor = if (isCurrentIcon)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant

                    Surface(
                        onClick = { onIconSelected(option.mdiName) },
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = option.label,
                                modifier = Modifier.size(32.dp),
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MdTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


@Composable
private fun ConnectingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EqualizerBars(
                modifier = Modifier.height(48.dp),
                barWidth = 8.dp,
                spacing = 6.dp,
                bpm = 130
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connecting...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// EqualizerBars moved to net.asksakis.massdroidv2.ui.components.EqualizerBars

@Composable
private fun ConnectionPrompt(
    state: ConnectionState,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (state) {
                is ConnectionState.Error -> "Connection error: ${state.message}"
                else -> "Not connected to Music Assistant"
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        MdButton(onClick = onSettings) {
            Text("Configure Server")
        }
    }
}

@Composable
internal fun PlayerIcon(
    player: Player,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val iconValue = player.icon
    if (iconValue != null && (iconValue.startsWith("http://") || iconValue.startsWith("https://"))) {
        SubcomposeAsyncImage(
            model = iconValue,
            contentDescription = null,
            modifier = modifier.clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Fit,
            error = {
                val fallback = typeBasedIcon(player.type)
                Icon(imageVector = fallback, contentDescription = null, modifier = modifier, tint = tint)
            }
        )
    } else {
        val imageVector = mapMdiIcon(iconValue) ?: typeBasedIcon(player.type)
        Icon(imageVector = imageVector, contentDescription = null, modifier = modifier, tint = tint)
    }
}

private fun typeBasedIcon(type: PlayerType): ImageVector = when (type) {
    PlayerType.GROUP -> Icons.Default.SpeakerGroup
    PlayerType.STEREO_PAIR -> Icons.Default.Speaker
    PlayerType.PLAYER -> Icons.Default.Speaker
}

private fun mapMdiIcon(mdiName: String?): ImageVector? {
    if (mdiName == null) return null
    val normalized = mdiName.replace(":", "-").lowercase()
    return when {
        normalized == "mdi-speaker-group" || normalized == "mdi-speaker-multiple" -> Icons.Default.SpeakerGroup
        normalized == "mdi-speaker" -> Icons.Default.Speaker
        normalized == "mdi-cast" -> Icons.Default.Cast
        normalized == "mdi-cast-connected" -> Icons.Default.CastConnected
        normalized == "mdi-television" || normalized == "mdi-tv" -> Icons.Default.Tv
        normalized == "mdi-cellphone" || normalized == "mdi-phone" || normalized == "mdi-cellphone-sound" -> Icons.Default.PhoneAndroid
        normalized == "mdi-laptop" -> Icons.Default.Laptop
        normalized == "mdi-radio" -> Icons.Default.Radio
        normalized == "mdi-headphones" -> Icons.Default.Headphones
        normalized == "mdi-bluetooth" || normalized == "mdi-bluetooth-audio" -> Icons.Default.Bluetooth
        normalized == "mdi-music" || normalized == "mdi-music-note" -> Icons.Default.MusicNote
        normalized == "mdi-monitor" -> Icons.Default.Monitor
        else -> null
    }
}
