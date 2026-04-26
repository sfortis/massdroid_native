package net.asksakis.massdroidv2.ui

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import net.asksakis.massdroidv2.ui.components.ExpandingPlayerSheet
import net.asksakis.massdroidv2.ui.components.LocalIsCar
import net.asksakis.massdroidv2.ui.components.LocalIsConnected
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.isCarMode
import net.asksakis.massdroidv2.ui.components.LocalProviderManifestCache
import net.asksakis.massdroidv2.ui.components.MiniPlayer
import javax.inject.Inject
import net.asksakis.massdroidv2.ui.navigation.MassDroidNavHost
import net.asksakis.massdroidv2.ui.navigation.Routes
import net.asksakis.massdroidv2.ui.permissions.AppPermissions
import net.asksakis.massdroidv2.ui.permissions.AppPermissionRationales
import net.asksakis.massdroidv2.ui.permissions.PermissionRationaleDialog
import net.asksakis.massdroidv2.ui.screens.home.MiniPlayerViewModel
import net.asksakis.massdroidv2.ui.theme.MassDroidTheme

private data class NavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
)

private val navItems = listOf(
    NavItem(Routes.HOME, Icons.Default.Home, "Home"),
    NavItem(Routes.PLAYERS, Icons.Default.Speaker, "Players"),
    NavItem(Routes.LIBRARY, Icons.Default.LibraryMusic, "Library"),
    NavItem(Routes.SEARCH, Icons.Default.Search, "Search")
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var shortcutDispatcher: ShortcutActionDispatcher
    @Inject lateinit var providerManifestCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache
    @Inject lateinit var appUpdateChecker: net.asksakis.massdroidv2.data.update.AppUpdateChecker
    @Inject lateinit var settingsRepository: net.asksakis.massdroidv2.domain.repository.SettingsRepository
    @Inject lateinit var proximityConfigStore: net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
    @Inject lateinit var playerRepository: net.asksakis.massdroidv2.domain.repository.PlayerRepository
    @Inject lateinit var localSpeakerVolumeBridge: net.asksakis.massdroidv2.data.sendspin.LocalSpeakerVolumeBridge
    @Inject lateinit var oauthCallbackBus: net.asksakis.massdroidv2.data.websocket.OAuthCallbackBus
    @Inject lateinit var maAuthRepository: net.asksakis.massdroidv2.domain.repository.MaAuthRepository

    private val volumeStep = 5
    @Volatile private var cachedSsClientId: String? = null
    @Volatile private var hwVolumeChangeUntilMs = 0L

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, nothing to do */ }

    private val followMePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startService(
                Intent(this, net.asksakis.massdroidv2.service.PlaybackService::class.java)
                    .setAction(net.asksakis.massdroidv2.service.PlaybackService.PROXIMITY_REEVALUATE_ACTION)
            )
        }
    }

    private var showNotificationPermissionDialog by mutableStateOf(false)
    private var showFollowMePermissionDialog by mutableStateOf(false)
    private var pendingFollowMePermissions by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Edge-to-edge only on API 30+; on older APIs (e.g. Android 10) Compose
        // WindowInsets don't report system bar sizes, causing layout overlap (#26)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            enableEdgeToEdge()
        }
        if (savedInstanceState == null) {
            requestNotificationPermission()
            requestFollowMePermissionsIfNeeded()
        }
        // The activity may be (re)launched via the OAuth callback deep link.
        handleOAuthCallback(intent)
        checkBatteryOptimization()
        handleShortcutIntent(intent)

        // Cache sendspin client ID and sync phone volume for local player
        lifecycleScope.launch {
            settingsRepository.sendspinClientId.collect { cachedSsClientId = it }
        }
        lifecycleScope.launch {
            var initialized = false
            playerRepository.selectedPlayer.collect { player ->
                val ssId = cachedSsClientId
                if (player == null || ssId == null || player.playerId != ssId) return@collect
                if (!initialized) {
                    // Skip first emission (startup) to avoid overriding phone volume
                    initialized = true
                    return@collect
                }
                // Skip sync if change originated from HW buttons (avoid round-trip bounce)
                if (System.currentTimeMillis() < hwVolumeChangeUntilMs) return@collect
                syncPhoneVolume(player.volumeLevel)
            }
        }

        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = "auto")
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // Update status bar icons to match app theme (not system theme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LaunchedEffect(darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = if (darkTheme) {
                            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        },
                        navigationBarStyle = if (darkTheme) {
                            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                    )
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalProviderManifestCache provides providerManifestCache
            ) {
                MassDroidTheme(darkTheme = darkTheme) {
                    MassDroidApp()
                    UpdatePrompt(appUpdateChecker)
                    if (showNotificationPermissionDialog) {
                        PermissionRationaleDialog(
                            spec = AppPermissionRationales.notifications,
                            onConfirm = {
                                showNotificationPermissionDialog = false
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onDismiss = { showNotificationPermissionDialog = false }
                        )
                    }
                    if (showFollowMePermissionDialog) {
                        PermissionRationaleDialog(
                            spec = AppPermissionRationales.followMe,
                            onConfirm = {
                                showFollowMePermissionDialog = false
                                followMePermissionLauncher.launch(pendingFollowMePermissions.toTypedArray())
                            },
                            onDismiss = { showFollowMePermissionDialog = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
        handleOAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // Detect OAuth abandonment: user pressed back/close in the browser tab
        // without completing the sign-in. The repo handles the grace window.
        maAuthRepository.handleAppResumed()
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "musicassistant" || data.host != "auth") return
        val code = data.getQueryParameter("code") ?: return
        if (code.isBlank()) return
        oauthCallbackBus.publish(code)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val player = playerRepository.selectedPlayer.value
            if (player != null) {
                val isLocal = cachedSsClientId != null && player.playerId == cachedSsClientId
                if (isLocal) {
                    // Local speaker: let system handle HW volume, then mirror to MA
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        hwVolumeChangeUntilMs = System.currentTimeMillis() + 1000
                        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            AudioManager.ADJUST_RAISE
                        } else {
                            AudioManager.ADJUST_LOWER
                        }
                        audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            direction,
                            AudioManager.FLAG_SHOW_UI
                        )
                        val maVol = readPhoneVolumePercent()
                        localSpeakerVolumeBridge.recordLocalPush(maVol)
                        lifecycleScope.launch { playerRepository.setVolume(player.playerId, maVol) }
                        return true
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeStep else -volumeStep
                    val isGroup = player.groupChilds.any { it != player.playerId }
                    val localId = cachedSsClientId
                    val localIsMember = localId != null &&
                        isGroup &&
                        player.groupChilds.any { it == localId }
                    if (localIsMember && localId != null) {
                        // Hardware keys drive ONLY the local speaker's own MA
                        // volume (not the entire group). We change phone
                        // STREAM_MUSIC first, read the resulting phone %, and
                        // push exactly that value to MA so the server echo
                        // confirms rather than competes.
                        hwVolumeChangeUntilMs = System.currentTimeMillis() + 1000
                        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            AudioManager.ADJUST_RAISE
                        } else {
                            AudioManager.ADJUST_LOWER
                        }
                        audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            direction,
                            AudioManager.FLAG_SHOW_UI
                        )
                        val phonePct = readPhoneVolumePercent()
                        localSpeakerVolumeBridge.recordLocalPush(phonePct)
                        playerRepository.applyVolumeOptimistic(localId, phonePct)
                        lifecycleScope.launch {
                            playerRepository.setVolume(localId, phonePct)
                        }
                    } else {
                        // Plain remote speaker / non-local group: adjust the
                        // selected player's volume (or the group volume for a
                        // group-type parent). The group fan-out is handled by
                        // MA's cmd/group_volume.
                        val basis = if (isGroup) player.groupVolume ?: player.volumeLevel
                            else player.volumeLevel
                        val newVol = (basis + delta).coerceIn(0, 100)
                        playerRepository.applyVolumeOptimistic(player.playerId, newVol)
                        lifecycleScope.launch {
                            if (isGroup) {
                                playerRepository.setGroupVolume(player.playerId, newVol)
                            } else {
                                playerRepository.setVolume(player.playerId, newVol)
                            }
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun readPhoneVolumePercent(): Int {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) ((current * 100f / max) + 0.5f).toInt() else 0
    }

    private fun syncPhoneVolume(maVolumePercent: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = ((maVolumePercent * maxVol / 100f) + 0.5f).toInt().coerceIn(0, maxVol)
        val currentVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (targetVol != currentVol) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val shortcutAction = when (action) {
            ACTION_SMART_MIX -> ShortcutAction.SmartMix
            ACTION_PLAY_NOW -> ShortcutAction.PlayNow
            else -> return
        }
        Log.d("MainActivity", "Shortcut action: $action")
        shortcutDispatcher.dispatch(shortcutAction)
        intent.action = null
    }

    companion object {
        private const val ACTION_SMART_MIX = "net.asksakis.massdroidv2.action.SMART_MIX"
        private const val ACTION_PLAY_NOW = "net.asksakis.massdroidv2.action.PLAY_NOW"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            showNotificationPermissionDialog = true
        }
    }

    private fun requestFollowMePermissionsIfNeeded() {
        lifecycleScope.launch {
            proximityConfigStore.load()
            val config = proximityConfigStore.config.value
            if (!config.enabled) return@launch

            val missing = AppPermissions.missing(this@MainActivity, AppPermissions.followMeRequired())
            if (missing.isEmpty()) return@launch
            pendingFollowMePermissions = missing
            showFollowMePermissionDialog = true
        }
    }

    private fun checkBatteryOptimization() {
        // AAOS doesn't surface a per-app battery optimization toggle the same
        // way phones do; the dialog dead-ends to a settings screen that does
        // not exist on car. Skip the prompt entirely when running on a car.
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        }
    }

    @SuppressLint("BatteryLife")
    private fun showBatteryOptimizationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "For reliable background music playback, MassDroid needs to be " +
                "excluded from battery optimization.\n\n" +
                "Without this, Android may stop playback when the screen is off."
            )
            .setPositiveButton("Disable Optimization") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Skip", null)
            .show()
    }
}

@Composable
private fun UpdatePrompt(checker: net.asksakis.massdroidv2.data.update.AppUpdateChecker) {
    val pendingUpdate by checker.pendingUpdate.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }

    pendingUpdate?.let { info ->
        val fileSizeMb = info.fileSizeBytes / (1024 * 1024)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!isDownloading) checker.dismissPendingUpdate() },
            title = { androidx.compose.material3.Text("Update Available") },
            text = {
                androidx.compose.material3.Text(
                    "Version ${info.version} is available${if (fileSizeMb > 0) " (${fileSizeMb}MB)" else ""}.\n\n${info.releaseNotes.take(300)}"
                )
            },
            confirmButton = {
                MdTextButton(
                    onClick = {
                        isDownloading = true
                        scope.launch {
                            checker.downloadUpdate(info) { }
                                .onSuccess { file ->
                                    context.startActivity(checker.buildInstallIntent(file))
                                }
                            isDownloading = false
                            checker.dismissPendingUpdate()
                        }
                    },
                    enabled = !isDownloading
                ) {
                    if (isDownloading) {
                        androidx.compose.material3.Text("Downloading...")
                    } else {
                        androidx.compose.material3.Text("Download & Install")
                    }
                }
            },
            dismissButton = {
                MdTextButton(
                    onClick = { checker.dismissPendingUpdate() },
                    enabled = !isDownloading
                ) {
                    androidx.compose.material3.Text("Later")
                }
            }
        )
    }
}

@Composable
private fun MassDroidApp(
    miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val hideNav = currentRoute == Routes.NOW_PLAYING ||
        currentRoute == Routes.SETTINGS ||
        currentRoute == Routes.RECOMMENDATION_INSIGHTS ||
        currentRoute?.startsWith("room_setup") == true
    val showNav = !hideNav && currentRoute != null
    val showMiniPlayer = currentRoute != Routes.NOW_PLAYING &&
        currentRoute != Routes.SETTINGS &&
        currentRoute != Routes.RECOMMENDATION_INSIGHTS &&
        currentRoute?.startsWith("room_setup") != true

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        if (!miniPlayerViewModel.isServerConfigured()) {
            navController.navigate(Routes.PLAYERS) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        miniPlayerViewModel.noPlayerSelectedEvent.collect {
            navController.navigate(Routes.PLAYERS) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
            snackbarHostState.showSnackbar(
                message = "Select a player first",
                duration = SnackbarDuration.Short
            )
        }
    }

    val miniPlayerCollapsedHeight = 72.dp
    val miniPlayerMargin = 8.dp
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val miniPlayerUiState by miniPlayerViewModel.miniPlayerUiState.collectAsStateWithLifecycle()
    val isConnected = miniPlayerUiState.connected
    // Mirror the visibility predicate used by MiniPlayerContainer so any FAB or
    // scrollable content that compensates for the mini player only does so when
    // one is actually rendered. Otherwise (no player selected, etc.) the slot
    // sits empty and content like the Smart Mix FAB ends up floating too high.
    val hasActiveMiniPlayer = showMiniPlayer && miniPlayerUiState.hasPlayer
    val miniPlayerPadding = if (hasActiveMiniPlayer) {
        miniPlayerCollapsedHeight + miniPlayerMargin
    } else {
        0.dp
    }

    val isCar = isCarMode()
    CompositionLocalProvider(
        LocalMiniPlayerPadding provides miniPlayerPadding,
        LocalIsConnected provides isConnected,
        LocalIsCar provides isCar
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            LandscapeLayout(
                navController = navController,
                currentRoute = currentRoute,
                showNav = showNav,
                showMiniPlayer = false,
                miniPlayerViewModel = miniPlayerViewModel,
                snackbarHostState = snackbarHostState,
                extraBottomPadding = 0.dp,
                onBottomBarHeightChanged = { bottomBarHeight = it }
            )
        } else {
            PortraitLayout(
                navController = navController,
                currentRoute = currentRoute,
                showNav = showNav,
                showMiniPlayer = false,
                miniPlayerViewModel = miniPlayerViewModel,
                snackbarHostState = snackbarHostState,
                extraBottomPadding = 0.dp,
                onBottomBarHeightChanged = { bottomBarHeight = it }
            )
        }

        // Floating expanding player overlay (on top of everything, both orientations)
        ExpandingPlayerSheet(
            miniPlayerViewModel = miniPlayerViewModel,
            navController = navController,
            showMiniPlayer = showMiniPlayer,
            showNav = showNav,
            bottomBarHeight = if (showNav) bottomBarHeight else 0.dp
        )
    }
    } // CompositionLocalProvider
}

@Composable
private fun PortraitLayout(
    navController: NavHostController,
    currentRoute: String?,
    showNav: Boolean,
    showMiniPlayer: Boolean,
    miniPlayerViewModel: MiniPlayerViewModel,
    snackbarHostState: SnackbarHostState,
    extraBottomPadding: Dp = 0.dp,
    onBottomBarHeightChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(
                modifier = (if (!showNav) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                    .onSizeChanged { size ->
                        onBottomBarHeightChanged(with(density) { size.height.toDp() })
                    }
            ) {
                if (extraBottomPadding > 0.dp) {
                    Spacer(modifier = Modifier.height(extraBottomPadding))
                }
                MiniPlayerContainer(
                    showMiniPlayer = showMiniPlayer,
                    miniPlayerViewModel = miniPlayerViewModel,
                    onClick = { navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } }
                )
                if (showNav) {
                    BottomNavBar(navController, currentRoute)
                }
            }
        }
    ) { paddingValues ->
        MassDroidNavHost(
            navController = navController,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
private fun LandscapeLayout(
    navController: NavHostController,
    currentRoute: String?,
    showNav: Boolean,
    showMiniPlayer: Boolean,
    miniPlayerViewModel: MiniPlayerViewModel,
    snackbarHostState: SnackbarHostState,
    extraBottomPadding: Dp = 0.dp,
    onBottomBarHeightChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .onSizeChanged { size ->
                    onBottomBarHeightChanged(with(density) { size.height.toDp() })
                }
            ) {
                if (extraBottomPadding > 0.dp) {
                    Spacer(modifier = Modifier.height(extraBottomPadding))
                }
                MiniPlayerContainer(
                    showMiniPlayer = showMiniPlayer,
                    miniPlayerViewModel = miniPlayerViewModel,
                    onClick = { navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } }
                )
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showNav) {
                SideNavRail(navController, currentRoute)
            }
            MassDroidNavHost(
                navController = navController,
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.End)
                    )
            )
        }
    }
}

@Composable
private fun MiniPlayerContainer(
    showMiniPlayer: Boolean,
    miniPlayerViewModel: MiniPlayerViewModel,
    onClick: () -> Unit
) {
    val miniPlayerUiState by miniPlayerViewModel.miniPlayerUiState.collectAsStateWithLifecycle()
    var showQueueSheet by remember { mutableStateOf(false) }
    val hasMiniPlayer = showMiniPlayer && miniPlayerUiState.hasPlayer
    if (!hasMiniPlayer) return

    MiniPlayer(
        title = miniPlayerUiState.title,
        artist = miniPlayerUiState.artist,
        imageUrl = miniPlayerUiState.imageUrl,
        isPlaying = miniPlayerUiState.isPlaying,
        onPlayPause = { miniPlayerViewModel.playPause() },
        onNext = { miniPlayerViewModel.next() },
        onQueue = { showQueueSheet = true },
        onClick = onClick
    )

    if (showQueueSheet) {
        net.asksakis.massdroidv2.ui.screens.queue.QueueSheet(
            onDismiss = { showQueueSheet = false }
        )
    }
}

@Composable
private fun BottomNavBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        for (item in navItems) {
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
private fun SideNavRail(navController: NavHostController, currentRoute: String?) {
    NavigationRail {
        for (item in navItems) {
            NavigationRailItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
