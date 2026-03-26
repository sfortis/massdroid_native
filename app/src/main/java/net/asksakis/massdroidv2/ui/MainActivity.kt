package net.asksakis.massdroidv2.ui

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            requestNotificationPermission()
            requestFollowMePermissionsIfNeeded()
        }
        checkBatteryOptimization()
        handleShortcutIntent(intent)
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = "auto")
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // Update status bar icons to match app theme (not system theme)
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
                androidx.compose.material3.TextButton(
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
                androidx.compose.material3.TextButton(
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

    val showNav = currentRoute in navItems.map { it.route }
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

    if (isLandscape) {
        LandscapeLayout(
            navController = navController,
            currentRoute = currentRoute,
            showNav = showNav,
            showMiniPlayer = showMiniPlayer,
            miniPlayerViewModel = miniPlayerViewModel,
            snackbarHostState = snackbarHostState
        )
    } else {
        PortraitLayout(
            navController = navController,
            currentRoute = currentRoute,
            showNav = showNav,
            showMiniPlayer = showMiniPlayer,
            miniPlayerViewModel = miniPlayerViewModel,
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
private fun PortraitLayout(
    navController: NavHostController,
    currentRoute: String?,
    showNav: Boolean,
    showMiniPlayer: Boolean,
    miniPlayerViewModel: MiniPlayerViewModel,
    snackbarHostState: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(
                modifier = if (!showNav) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier
            ) {
                MiniPlayerContainer(
                    showMiniPlayer = showMiniPlayer,
                    miniPlayerViewModel = miniPlayerViewModel,
                    onQueue = { navController.navigate(Routes.QUEUE) { launchSingleTop = true } },
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
    snackbarHostState: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                MiniPlayerContainer(
                    showMiniPlayer = showMiniPlayer,
                    miniPlayerViewModel = miniPlayerViewModel,
                    onQueue = { navController.navigate(Routes.QUEUE) { launchSingleTop = true } },
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
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiniPlayerContainer(
    showMiniPlayer: Boolean,
    miniPlayerViewModel: MiniPlayerViewModel,
    onQueue: () -> Unit,
    onClick: () -> Unit
) {
    val miniPlayerUiState by miniPlayerViewModel.miniPlayerUiState.collectAsStateWithLifecycle()
    val hasMiniPlayer = showMiniPlayer && miniPlayerUiState.connected && miniPlayerUiState.hasPlayer
    if (!hasMiniPlayer) return

    MiniPlayer(
        title = miniPlayerUiState.title,
        artist = miniPlayerUiState.artist,
        imageUrl = miniPlayerUiState.imageUrl,
        isPlaying = miniPlayerUiState.isPlaying,
        onPlayPause = { miniPlayerViewModel.playPause() },
        onNext = { miniPlayerViewModel.next() },
        onQueue = onQueue,
        onClick = onClick
    )
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
                        if (item.route == Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        } else {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
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
                        if (item.route == Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        } else {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
    }
}
