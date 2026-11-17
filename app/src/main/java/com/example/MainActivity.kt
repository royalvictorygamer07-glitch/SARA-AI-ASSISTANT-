package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.SaraViewModel
import com.example.ui.components.WakePopupOverlay
import com.example.ui.screens.ActionsHubScreen
import com.example.ui.screens.AssistantHomeScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VisionScreen
import com.example.ui.screens.WhatsAppAutomationScreen
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SaraTheme
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import com.example.voice.wake.WakeDetectionService

object SaraRoutes {
    const val ROUTE_ASSISTANT = "assistant"
    const val ROUTE_VISION = "vision"
    const val ROUTE_MEMORY = "memory"
    const val ROUTE_ACTIONS = "actions"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_WHATSAPP_AUTOMATION = "settings_whatsapp_automation"
}

private data class SaraNavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val mainTabs = listOf(
    SaraNavTab(
        route = SaraRoutes.ROUTE_ASSISTANT,
        label = "Core",
        selectedIcon = Icons.Filled.GraphicEq,
        unselectedIcon = Icons.Outlined.GraphicEq
    ),
    SaraNavTab(
        route = SaraRoutes.ROUTE_VISION,
        label = "Vision",
        selectedIcon = Icons.Filled.CameraAlt,
        unselectedIcon = Icons.Outlined.CameraAlt
    ),
    SaraNavTab(
        route = SaraRoutes.ROUTE_MEMORY,
        label = "Memory",
        selectedIcon = Icons.Filled.Psychology,
        unselectedIcon = Icons.Outlined.Psychology
    ),
    SaraNavTab(
        route = SaraRoutes.ROUTE_ACTIONS,
        label = "Actions",
        selectedIcon = Icons.Filled.Hub,
        unselectedIcon = Icons.Outlined.Hub
    )
)

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { (application as SaraApplication).container }
    private val saraViewModel: SaraViewModel by viewModels {
        SaraViewModel.Factory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        setContent {
            SaraTheme {
                SaraRootApp(viewModel = saraViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeDetectionService.EXTRA_WAKE_TRIGGERED, false) == true) {
            intent.removeExtra(WakeDetectionService.EXTRA_WAKE_TRIGGERED)
            val phrase = intent.getStringExtra(WakeDetectionService.EXTRA_WAKE_PHRASE) ?: "hey sara"
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            saraViewModel.handleWakePhraseTriggered(phrase, hasMic)
        }
    }
}

@Composable
fun SaraRootApp(viewModel: SaraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasMicPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    viewModel.onAppForegrounded()
                    viewModel.refreshBatteryOptimizationStatus()
                    viewModel.refreshCommandPermissions()
                }
                Lifecycle.Event.ON_STOP -> {
                    val changingConfig = (context as? Activity)?.isChangingConfigurations == true
                    if (!changingConfig) {
                        viewModel.onAppBackgrounded()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        viewModel.onMicPermissionResult(granted)
        viewModel.refreshCommandPermissions()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.setWakeUpDetectionEnabled(true, hasMicPermission)
        viewModel.refreshCommandPermissions()
    }

    val voskFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.importVoskModelTreeUri(uri)
        }
    }

    val dynamicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onDynamicPermissionResult(granted)
        viewModel.refreshCommandPermissions()
    }

    val commandPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.refreshCommandPermissions()
    }

    LaunchedEffect(Unit) {
        viewModel.requestMicPermissionEvent.collect {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showNavigationChrome = currentDestination?.route != SaraRoutes.ROUTE_SETTINGS &&
        currentDestination?.route != SaraRoutes.ROUTE_WHATSAPP_AUTOMATION

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = VoidObsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (showNavigationChrome && !useNavRail) {
                    NavigationBar(
                        containerColor = HudMidnight,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        mainTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyanPlasma,
                                    selectedTextColor = CyanPlasma,
                                    indicatorColor = HudCardSurface,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showNavigationChrome && useNavRail) {
                    NavigationRail(
                        containerColor = HudMidnight
                    ) {
                        mainTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CyanPlasma,
                                    selectedTextColor = CyanPlasma,
                                    indicatorColor = HudCardSurface,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_rail_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = SaraRoutes.ROUTE_ASSISTANT
                    ) {
                        composable(SaraRoutes.ROUTE_ASSISTANT) {
                            AssistantHomeScreen(
                                uiState = uiState,
                                hasMicPermission = hasMicPermission,
                                onMicClick = {
                                    hasMicPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    viewModel.onMicButtonPressed(hasMicPermission)
                                },
                                onEndSessionClick = { viewModel.endConversationSession() },
                                onInterruptClick = { viewModel.interruptSpeechAndListen() },
                                onSleepClick = { viewModel.enterSleepMode() },
                                onWakeClick = { viewModel.wakeUpFromSleep() },
                                onOpenOverlayPermission = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                },
                                onNewChatClick = { viewModel.startNewConversationSession() },
                                onSendText = { viewModel.sendTextCommand(it) },
                                onToggleGeminiLive = { viewModel.setGeminiLiveEnabled(it) },
                                onConfirmAction = { viewModel.confirmPendingAction() },
                                onCancelAction = { viewModel.cancelPendingAction() },
                                onDismissBanner = { viewModel.dismissBanner() },
                                onOpenSettings = {
                                    navController.navigate(SaraRoutes.ROUTE_SETTINGS) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(SaraRoutes.ROUTE_VISION) {
                            VisionScreen(
                                uiState = uiState,
                                onAnalyzeBitmap = { bmp, prompt ->
                                    viewModel.analyzeImageWithGemini(bmp, prompt)
                                },
                                onResetVision = { viewModel.resetVisionState() }
                            )
                        }

                        composable(SaraRoutes.ROUTE_MEMORY) {
                            MemoryScreen(
                                uiState = uiState,
                                onToggleMemoryEnabled = { viewModel.setMemoryEnabled(it) },
                                onAddMemory = { cat, content -> viewModel.addManualMemory(cat, content) },
                                onDeleteMemory = { viewModel.deleteMemory(it) },
                                onClearAll = { viewModel.clearAllMemories() },
                                onSelectHistorySession = { viewModel.selectHistorySession(it) },
                                onDeleteHistorySession = { viewModel.deleteHistorySession(it) },
                                onClearAllHistory = { viewModel.clearAllHistory() }
                            )
                        }

                        composable(SaraRoutes.ROUTE_ACTIONS) {
                            ActionsHubScreen(
                                uiState = uiState,
                                onRunCommand = { cmd ->
                                    viewModel.sendTextCommand(cmd)
                                    navController.navigate(SaraRoutes.ROUTE_ASSISTANT) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onRefreshIncomingReport = { viewModel.refreshIncomingCallReport() },
                                onSimulateIncomingCall = { caller -> viewModel.simulateIncomingCallPrompt(caller) },
                                onSimulateIncomingDecision = { yes -> viewModel.simulateIncomingCallDecision(yes) }
                            )
                        }

                        composable(SaraRoutes.ROUTE_SETTINGS) {
                            SettingsScreen(
                                uiState = uiState,
                                onBack = { navController.popBackStack() },
                                onSaveUserProfile = { name, lang, prefs ->
                                    viewModel.saveUserProfile(name, lang, prefs)
                                },
                                onSaveElevenLabs = { key, voiceId ->
                                    viewModel.saveElevenLabsSettings(key, voiceId)
                                },
                                onClearElevenLabs = { viewModel.clearElevenLabsSettings() },
                                onFetchElevenLabsVoices = { key ->
                                    viewModel.fetchElevenLabsVoices(key)
                                },
                                onSaveGemini = { key, model, liveModel, liveVoice ->
                                    viewModel.saveGeminiSettings(key, model, liveModel, liveVoice)
                                },
                                onClearGemini = { viewModel.clearGeminiKey() },
                                onTestGemini = { key, model ->
                                    viewModel.testGeminiConnection(key, model)
                                },
                                onToggleGeminiLive = { viewModel.setGeminiLiveEnabled(it) },
                                onToggleContinuous = { viewModel.setContinuousConversation(it) },
                                onToggleLiveCall = { viewModel.setLiveCallMode(it) },
                                onToggleAutoSpeak = { viewModel.setAutoSpeakResponses(it) },
                                onToggleConfirmBeforeAction = { viewModel.setConfirmBeforeAction(it) },
                                onToggleMemoryEnabled = { viewModel.setMemoryEnabled(it) },
                                onToggleWakeUpDetection = { enabled ->
                                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.setWakeUpDetectionEnabled(enabled, hasMicPermission)
                                    }
                                },
                                onToggleBackgroundAssistant = { viewModel.setBackgroundAssistantEnabled(it) },
                                onOpenOverlayPermissionSettings = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                },
                                onImportVoskModelClick = { voskFolderPickerLauncher.launch(null) },
                                onClearVoskModelClick = { viewModel.clearImportedVoskModel() },
                                onRecordEnrollmentSample = { viewModel.recordNextWakeEnrollmentSample() },
                                onStopEnrollmentRecording = { viewModel.stopWakeEnrollmentRecording() },
                                onClearEnrollment = { viewModel.clearWakeEnrollment() },
                                onToggleBargeIn = { viewModel.setBargeInEnabled(it) },
                                onSensitivityChanged = { viewModel.setWakeSensitivity(it) },
                                onStartMicTest = { viewModel.startMicrophoneTest() },
                                onStopMicTest = { viewModel.stopMicrophoneTest() },
                                onOpenBatterySettings = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        )
                                    }
                                },
                                onToggleWhatsAppAutomation = { viewModel.setWhatsAppAutomationEnabled(it) },
                                onOpenWhatsAppAutomation = {
                                    navController.navigate(SaraRoutes.ROUTE_WHATSAPP_AUTOMATION) {
                                        launchSingleTop = true
                                    }
                                },
                                onRequestAllCommandPermissions = { missing ->
                                    if (missing.isNotEmpty()) {
                                        commandPermissionsLauncher.launch(missing)
                                    }
                                }
                            )
                        }

                        composable(SaraRoutes.ROUTE_WHATSAPP_AUTOMATION) {
                            WhatsAppAutomationScreen(
                                uiState = uiState,
                                onBack = { navController.popBackStack() },
                                onOpenNotificationAccessSettings = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        )
                                    }
                                },
                                onOpenBatterySettings = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        )
                                    }
                                },
                                onRequestAllCommandPermissions = { missing ->
                                    if (missing.isNotEmpty()) {
                                        commandPermissionsLauncher.launch(missing)
                                    }
                                },
                                onToggleAutomation = { viewModel.setWhatsAppAutomationEnabled(it) },
                                onToggleGeminiBrain = { viewModel.setWhatsAppGeminiBrainEnabled(it) },
                                onToggleAutoReply = { viewModel.setWhatsAppAutoReplyEnabled(it) },
                                onToggleVoiceAnnouncement = { viewModel.setWhatsAppVoiceAnnouncementEnabled(it) },
                                onToggleReadIncoming = { viewModel.setWhatsAppReadIncomingEnabled(it) },
                                onToggleGroupChats = { viewModel.setWhatsAppReplyToGroups(it) },
                                onSelectLanguageMode = { viewModel.setWhatsAppReplyLanguageMode(it) },
                                onChangeReplyDelay = { viewModel.setWhatsAppReplyDelaySeconds(it) },
                                onChangeMaxMessages = { viewModel.setWhatsAppMaxMessagesPerConversation(it) },
                                onSaveRule = { contact, keywords, reply, instruction, mode, priority ->
                                    viewModel.saveWhatsAppContactRule(
                                        contactNamePattern = contact,
                                        triggerKeywords = keywords,
                                        customReply = reply,
                                        aiInstruction = instruction,
                                        behaviorMode = mode,
                                        priority = priority
                                    )
                                },
                                onToggleRuleEnabled = { id, enabled -> viewModel.toggleWhatsAppRuleEnabled(id, enabled) },
                                onDeleteRule = { id -> viewModel.deleteWhatsAppRule(id) },
                                onSeedSampleRules = { viewModel.seedSampleRahulRules() },
                                onUpdateContactInstructions = { convKey, prefs, instr, restricted ->
                                    viewModel.updateWhatsAppContactInstructions(convKey, prefs, instr, restricted)
                                },
                                onResetContactCounter = { viewModel.resetWhatsAppContactCounter(it) },
                                onClearContactMemory = { viewModel.clearWhatsAppContactMemory(it) },
                                onClearAllWhatsAppMemory = { viewModel.clearAllWhatsAppMemory() },
                                onClearActivityLogs = { viewModel.clearWhatsAppActivityLogs() }
                            )
                        }
                    }
                }
            }
        }

        WakePopupOverlay(
            popupState = uiState.wakePopupState,
            assistantStatus = uiState.status,
            onInterruptClick = { viewModel.interruptSpeechAndListen() },
            onDismissPopup = { viewModel.dismissWakePopup() }
        )
    }

    uiState.pendingPermissionRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionPrompt() },
            title = { Text("Permission Required") },
            text = { Text(req.rationale) },
            confirmButton = {
                TextButton(
                    onClick = { dynamicPermissionLauncher.launch(req.permission) }
                ) {
                    Text("Allow", color = CyanPlasma)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onDynamicPermissionResult(false) }
                ) {
                    Text("Not Now")
                }
            }
        )
    }
}
