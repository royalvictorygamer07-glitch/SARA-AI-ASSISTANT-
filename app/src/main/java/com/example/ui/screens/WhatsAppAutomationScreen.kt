package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.automation.whatsapp.AutomationServiceStatus
import com.example.automation.whatsapp.AutomationSettings
import com.example.automation.whatsapp.WhatsAppContactProfileEntity
import com.example.automation.whatsapp.WhatsAppContactRuleEntity
import com.example.ui.SaraUiState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardElevated
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatsAppAutomationScreen(
    uiState: SaraUiState,
    onBack: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestAllCommandPermissions: (Array<String>) -> Unit,
    onToggleAutomation: (Boolean) -> Unit,
    onToggleGeminiBrain: (Boolean) -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleVoiceAnnouncement: (Boolean) -> Unit,
    onToggleReadIncoming: (Boolean) -> Unit,
    onToggleGroupChats: (Boolean) -> Unit,
    onSelectLanguageMode: (String) -> Unit,
    onChangeReplyDelay: (Int) -> Unit,
    onChangeMaxMessages: (Int) -> Unit,
    onSaveRule: (contact: String, keywords: String, reply: String, instruction: String, mode: String, priority: Int) -> Unit,
    onToggleRuleEnabled: (Long, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onSeedSampleRules: () -> Unit,
    onUpdateContactInstructions: (conversationKey: String, prefs: String, instructions: String, restricted: Boolean) -> Unit,
    onResetContactCounter: (String) -> Unit,
    onClearContactMemory: (String) -> Unit,
    onClearAllWhatsAppMemory: () -> Unit,
    onClearActivityLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddRuleDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableStateOf<String?>(null) }

    val settings = uiState.waAutomationSettings
    val status = uiState.waServiceStatus
    val statusColor = when (status) {
        AutomationServiceStatus.RUNNING,
        AutomationServiceStatus.WAITING_FOR_NOTIFICATION,
        AutomationServiceStatus.REPLY_SENT -> EmeraldActive
        AutomationServiceStatus.PROCESSING_MESSAGE,
        AutomationServiceStatus.GENERATING_REPLY -> AmberThinking
        AutomationServiceStatus.REPLY_FAILED -> CrimsonAlert
        AutomationServiceStatus.STOPPED -> TextMuted
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoidObsidian, HudMidnight, VoidObsidian)))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Settings",
                        tint = CyanPlasma
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WHATSAPP AUTOMATION",
                        style = MaterialTheme.typography.titleLarge,
                        color = CyanPlasma
                    )
                    Text(
                        text = "Settings → Automation → WhatsApp Automation",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = settings.automationEnabled,
                    onCheckedChange = onToggleAutomation,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidObsidian,
                        checkedTrackColor = CyanPlasma
                    ),
                    modifier = Modifier.testTag("wa_master_switch")
                )
            }

            // Live Service Status Banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS: ${status.label.uppercase()}",
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            modifier = Modifier.testTag("wa_service_status_label")
                        )
                        Text(
                            text = if (uiState.waListenerConnected) "Listener Bound" else "Listener Unbound",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.waListenerConnected) EmeraldActive else AmberThinking
                        )
                    }
                    Text(
                        text = uiState.waStatusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Tabs: Controls | Rules | Memory | Logs
            val tabs = listOf("Controls", "Rules (${uiState.waContactRules.size})", "Memory (${uiState.waContactProfiles.size})", "Logs")
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = HudCardSurface,
                contentColor = CyanPlasma
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.testTag("wa_tab_$index")
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> ControlsTabContent(
                        uiState = uiState,
                        settings = settings,
                        onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRequestAllCommandPermissions = onRequestAllCommandPermissions,
                        onToggleAutomation = onToggleAutomation,
                        onToggleGeminiBrain = onToggleGeminiBrain,
                        onToggleAutoReply = onToggleAutoReply,
                        onToggleVoiceAnnouncement = onToggleVoiceAnnouncement,
                        onToggleReadIncoming = onToggleReadIncoming,
                        onToggleGroupChats = onToggleGroupChats,
                        onSelectLanguageMode = onSelectLanguageMode,
                        onChangeReplyDelay = onChangeReplyDelay,
                        onChangeMaxMessages = onChangeMaxMessages
                    )
                    1 -> RulesTabContent(
                        rules = uiState.waContactRules,
                        onOpenAddRule = { showAddRuleDialog = true },
                        onSeedSampleRules = onSeedSampleRules,
                        onToggleRuleEnabled = onToggleRuleEnabled,
                        onDeleteRule = onDeleteRule
                    )
                    2 -> MemoryTabContent(
                        profiles = uiState.waContactProfiles,
                        recentMessages = uiState.waRecentMessages,
                        onEditProfile = { editingProfile = it },
                        onResetCounter = onResetContactCounter,
                        onClearContact = onClearContactMemory,
                        onClearAll = onClearAllWhatsAppMemory
                    )
                    3 -> LogsTabContent(
                        logs = uiState.waActivityLogs,
                        onClearLogs = onClearActivityLogs
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddRuleDialog) {
        AddContactRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onSave = { contact, keywords, reply, instruction, mode, priority ->
                onSaveRule(contact, keywords, reply, instruction, mode, priority)
                showAddRuleDialog = false
            }
        )
    }

    editingProfile?.let { convKey ->
        val profile = uiState.waContactProfiles.firstOrNull { it.conversationKey == convKey }
        if (profile != null) {
            EditContactMemoryDialog(
                profile = profile,
                onDismiss = { editingProfile = null },
                onSave = { prefs, instr, restricted ->
                    onUpdateContactInstructions(convKey, prefs, instr, restricted)
                    editingProfile = null
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsTabContent(
    uiState: SaraUiState,
    settings: AutomationSettings,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestAllCommandPermissions: (Array<String>) -> Unit,
    onToggleAutomation: (Boolean) -> Unit,
    onToggleGeminiBrain: (Boolean) -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleVoiceAnnouncement: (Boolean) -> Unit,
    onToggleReadIncoming: (Boolean) -> Unit,
    onToggleGroupChats: (Boolean) -> Unit,
    onSelectLanguageMode: (String) -> Unit,
    onChangeReplyDelay: (Int) -> Unit,
    onChangeMaxMessages: (Int) -> Unit
) {
    // Permissions & Reliability Card
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
        border = BorderStroke(1.dp, HudBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (uiState.waNotificationAccessGranted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = if (uiState.waNotificationAccessGranted) EmeraldActive else AmberThinking
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Android Notification Access & Reliability",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyanPlasma
                )
            }

            Text(
                text = if (uiState.waNotificationAccessGranted) {
                    "Notification Access is GRANTED. Sara can detect incoming WhatsApp notifications and send inline RemoteInput replies."
                } else {
                    "Notification Access is REQUIRED so Sara can read WhatsApp notifications and send automatic replies when minimized or screen-locked."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenNotificationAccessSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.waNotificationAccessGranted) HudCardElevated else CyanPlasma,
                        contentColor = if (uiState.waNotificationAccessGranted) CyanPlasma else VoidObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("wa_notification_access_button")
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (uiState.waNotificationAccessGranted) "Listener Settings" else "Grant Access")
                }

                OutlinedButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("wa_battery_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (uiState.isIgnoringBatteryOptimizations) EmeraldActive else AmberThinking,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (uiState.isIgnoringBatteryOptimizations) "Unrestricted" else "Battery Guide")
                }
            }

            Text(
                text = "Note: Disabling Battery Optimization helps keep background replies responsive. If Android Force Stop is tapped, OS stops all background listeners until you reopen SARA.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            val perms = uiState.commandPermissions
            val missing = perms.missingPermissions()
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = HudCardElevated,
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Command & Call-Guard Permissions",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanPlasma
                    )
                    Text(
                        text = "Mic: ${if (perms.recordAudio) "OK" else "Missing"} • Contacts: ${if (perms.readContacts) "OK" else "Missing"} • Call: ${if (perms.callPhone) "OK" else "Missing"}\nCall Guard: ${if (perms.readPhoneState) "OK" else "Missing"} • Alerts: ${if (perms.postNotifications) "OK" else "Missing"} • Camera: ${if (perms.camera) "OK" else "Missing"} • Bluetooth: ${if (perms.bluetoothConnect) "OK" else "Missing"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (missing.isNotEmpty()) {
                        Button(
                            onClick = { onRequestAllCommandPermissions(missing) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SapphirePulse
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("wa_grant_all_command_permissions")
                        ) {
                            Text("Grant All Command Permissions (${missing.size})")
                        }
                    } else {
                        Text(
                            text = "All runtime command & call-safety permissions are granted.",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldActive
                        )
                    }
                }
            }
        }
    }

    // Main Automation Switches Card
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
        border = BorderStroke(1.dp, HudBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Automation Behavior Controls",
                style = MaterialTheme.typography.titleSmall,
                color = CyanPlasma
            )

            AutomationSwitchRow(
                title = "WhatsApp & Business Automation",
                subtitle = "Monitors both WhatsApp & WhatsApp Business notifications in background.",
                checked = settings.automationEnabled,
                onCheckedChange = onToggleAutomation,
                tag = "wa_toggle_automation"
            )
            if (!uiState.geminiCredentials.isConfigured) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CrimsonAlert.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Gemini API Key missing: Smart AI auto-replies need your Gemini API Key saved in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonAlert,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            AutomationSwitchRow(
                title = "Gemini AI Brain",
                subtitle = if (uiState.geminiCredentials.isConfigured) {
                    "Context-aware multilingual AI (${uiState.geminiCredentials.modelName}) active."
                } else {
                    "Configure Gemini API key in Settings to enable AI replies."
                },
                checked = settings.geminiBrainEnabled,
                onCheckedChange = onToggleGeminiBrain,
                tag = "wa_toggle_gemini"
            )
            AutomationSwitchRow(
                title = "Automatic Reply Sending",
                subtitle = "Send matched rule / Gemini replies via WhatsApp inline RemoteInput.",
                checked = settings.autoReplyEnabled,
                onCheckedChange = onToggleAutoReply,
                tag = "wa_toggle_autoreply"
            )
            AutomationSwitchRow(
                title = "Voice Announcement",
                subtitle = "Announce new messages & sent replies aloud (paused during phone calls).",
                checked = settings.voiceAnnouncementEnabled,
                onCheckedChange = onToggleVoiceAnnouncement,
                tag = "wa_toggle_voice_announce"
            )
            AutomationSwitchRow(
                title = "Read Incoming Messages Aloud",
                subtitle = "Speak sender message text aloud in addition to notification alert.",
                checked = settings.readIncomingMessagesAloud,
                onCheckedChange = onToggleReadIncoming,
                tag = "wa_toggle_read_incoming"
            )
            AutomationSwitchRow(
                title = "Reply in Group Chats",
                subtitle = "Allow auto-replies inside WhatsApp group notifications (OFF recommended).",
                checked = settings.replyToGroupChats,
                onCheckedChange = onToggleGroupChats,
                tag = "wa_toggle_groups"
            )
        }
    }

    // Language, Delay & Caps Card
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
        border = BorderStroke(1.dp, HudBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Language, Batching & Safety Limits",
                style = MaterialTheme.typography.titleSmall,
                color = SapphirePulse
            )

            Text(
                text = "Reply Language",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AutomationSettings.SUPPORTED_LANGUAGE_MODES.forEach { lang ->
                    FilterChip(
                        selected = settings.replyLanguageMode == lang,
                        onClick = { onSelectLanguageMode(lang) },
                        label = { Text(lang) }
                    )
                }
            }

            Text(
                text = if (settings.aiReplyDelaySeconds == 0) {
                    "AI Reply Delay: Instant (0s — replies immediately)"
                } else {
                    "AI Reply Delay: ${settings.aiReplyDelaySeconds}s (batches consecutive messages)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Slider(
                value = settings.aiReplyDelaySeconds.toFloat(),
                onValueChange = { onChangeReplyDelay(it.toInt()) },
                valueRange = 0f..15f,
                steps = 14,
                colors = SliderDefaults.colors(thumbColor = CyanPlasma, activeTrackColor = CyanPlasma)
            )

            Text(
                text = "Max Auto-Replies Per Conversation: ${settings.maxMessagesPerConversation}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Slider(
                value = settings.maxMessagesPerConversation.toFloat(),
                onValueChange = { onChangeMaxMessages(it.toInt()) },
                valueRange = 1f..50f,
                colors = SliderDefaults.colors(thumbColor = SapphirePulse, activeTrackColor = SapphirePulse)
            )
        }
    }
}

@Composable
private fun RulesTabContent(
    rules: List<WhatsAppContactRuleEntity>,
    onOpenAddRule: () -> Unit,
    onSeedSampleRules: () -> Unit,
    onToggleRuleEnabled: (Long, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onOpenAddRule,
            colors = ButtonDefaults.buttonColors(containerColor = CyanPlasma, contentColor = VoidObsidian),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("wa_add_rule_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add Contact Rule")
        }

        OutlinedButton(
            onClick = onSeedSampleRules,
            modifier = Modifier
                .height(48.dp)
                .testTag("wa_seed_rahul_rules_button")
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Sample Rules")
        }
    }

    if (rules.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = HudCardSurface),
            border = BorderStroke(1.dp, HudBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = null, tint = CyanPlasma)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("No Contact Rules Configured", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Personalized rules always take priority over Gemini AI replies. Tap 'Add Contact Rule' or load the sample Rahul rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    } else {
        rules.forEach { rule ->
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rule.contactNamePattern} • Priority ${rule.priority}",
                                style = MaterialTheme.typography.titleSmall,
                                color = CyanPlasma
                            )
                            Text(
                                text = if (rule.triggerKeywords.isBlank()) "Trigger: Any message" else "Triggers: ${rule.triggerKeywords}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onToggleRuleEnabled(rule.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = VoidObsidian, checkedTrackColor = CyanPlasma)
                        )
                        IconButton(onClick = { onDeleteRule(rule.id) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Rule", tint = CrimsonAlert)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = HudCardElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when (rule.behaviorMode) {
                                WhatsAppContactRuleEntity.MODE_BLOCK_AUTO_REPLY -> "Action: Block automatic replies for this contact"
                                WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION -> "AI Instruction: ${rule.aiInstruction.ifBlank { rule.customReply }}"
                                else -> "Reply: ${rule.customReply}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldActive,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTabContent(
    profiles: List<WhatsAppContactProfileEntity>,
    recentMessages: List<com.example.automation.whatsapp.WhatsAppMessageEntity>,
    onEditProfile: (String) -> Unit,
    onResetCounter: (String) -> Unit,
    onClearContact: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (profiles.isNotEmpty()) {
        OutlinedButton(
            onClick = onClearAll,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = CrimsonAlert)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Clear All WhatsApp Memory", color = CrimsonAlert)
        }
    }

    if (profiles.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = HudCardSurface),
            border = BorderStroke(1.dp, HudBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, tint = CyanPlasma)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("No Contact Memory Yet", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Contact-wise history, detected languages, and custom instructions will appear here as WhatsApp notifications arrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    } else {
        profiles.forEach { profile ->
            val contactMsgs = recentMessages.filter { it.conversationKey == profile.conversationKey }.take(4)
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = CyanPlasma
                            )
                            Text(
                                text = "Lang: ${profile.preferredLanguage} • Replies sent: ${profile.autoReplyCount}${if (profile.isRestricted) " • RESTRICTED" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (profile.isRestricted) CrimsonAlert else TextSecondary
                            )
                        }
                        TextButton(onClick = { onEditProfile(profile.conversationKey) }) {
                            Text("Instructions")
                        }
                    }

                    if (profile.customInstructions.isNotBlank() || profile.senderPreferences.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = HudCardElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (profile.senderPreferences.isNotBlank()) {
                                    Text("Prefs: ${profile.senderPreferences}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                                if (profile.customInstructions.isNotBlank()) {
                                    Text("Instructions: ${profile.customInstructions}", style = MaterialTheme.typography.labelSmall, color = EmeraldActive)
                                }
                            }
                        }
                    }

                    contactMsgs.reversed().forEach { m ->
                        val prefix = if (m.isOutgoing) "Sara (${m.status}):" else "${m.senderName}:"
                        Text(
                            text = "$prefix ${m.messageText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (m.isOutgoing) CyanPlasma else TextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onResetCounter(profile.conversationKey) }) {
                            Text("Reset Cap")
                        }
                        TextButton(onClick = { onClearContact(profile.conversationKey) }) {
                            Text("Clear Contact", color = CrimsonAlert)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTabContent(
    logs: List<com.example.automation.whatsapp.WhatsAppActivityLogEntity>,
    onClearLogs: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, contentDescription = null, tint = CyanPlasma)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Automation Activity Logs", style = MaterialTheme.typography.titleSmall)
        }
        if (logs.isNotEmpty()) {
            TextButton(onClick = onClearLogs) {
                Text("Clear Logs", color = CrimsonAlert)
            }
        }
    }

    if (logs.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = HudCardSurface),
            border = BorderStroke(1.dp, HudBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "No WhatsApp automation activity recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        logs.forEach { log ->
            val accent = when (log.eventType) {
                "SENT", "RULE_MATCHED" -> EmeraldActive
                "FAILED" -> CrimsonAlert
                "SKIPPED" -> AmberThinking
                else -> CyanPlasma
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${log.senderName} • ${log.eventType}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                        Text(
                            text = formatter.format(Date(log.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = "${log.status}: ${log.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddContactRuleDialog(
    onDismiss: () -> Unit,
    onSave: (contact: String, keywords: String, reply: String, instruction: String, mode: String, priority: Int) -> Unit
) {
    var contact by rememberSaveable { mutableStateOf("") }
    var keywords by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(WhatsAppContactRuleEntity.MODE_EXACT_REPLY) }
    var priority by rememberSaveable { mutableIntStateOf(80) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Personalized Contact Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("Contact Name (or * for any)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Trigger Keywords (comma-separated, optional)") },
                    placeholder = { Text("e.g. Boss kahan, call") },
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = mode == WhatsAppContactRuleEntity.MODE_EXACT_REPLY,
                        onClick = { mode = WhatsAppContactRuleEntity.MODE_EXACT_REPLY },
                        label = { Text("Exact Reply") }
                    )
                    FilterChip(
                        selected = mode == WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION,
                        onClick = { mode = WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION },
                        label = { Text("AI Instruction") }
                    )
                    FilterChip(
                        selected = mode == WhatsAppContactRuleEntity.MODE_BLOCK_AUTO_REPLY,
                        onClick = { mode = WhatsAppContactRuleEntity.MODE_BLOCK_AUTO_REPLY },
                        label = { Text("Block Reply") }
                    )
                }
                if (mode == WhatsAppContactRuleEntity.MODE_EXACT_REPLY) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        label = { Text("Custom Priority Reply") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (mode == WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION) {
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text("Instruction for Gemini Brain") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("Priority: $priority", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt() },
                    valueRange = 1f..100f
                )
            }
        },
        confirmButton = {
            Button(
                enabled = contact.isNotBlank() &&
                    (mode == WhatsAppContactRuleEntity.MODE_BLOCK_AUTO_REPLY ||
                        (mode == WhatsAppContactRuleEntity.MODE_EXACT_REPLY && reply.isNotBlank()) ||
                        (mode == WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION && instruction.isNotBlank())),
                onClick = { onSave(contact.trim(), keywords.trim(), reply.trim(), instruction.trim(), mode, priority) }
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditContactMemoryDialog(
    profile: WhatsAppContactProfileEntity,
    onDismiss: () -> Unit,
    onSave: (prefs: String, instructions: String, restricted: Boolean) -> Unit
) {
    var prefs by rememberSaveable { mutableStateOf(profile.senderPreferences) }
    var instructions by rememberSaveable { mutableStateOf(profile.customInstructions) }
    var restricted by rememberSaveable { mutableStateOf(profile.isRestricted) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contact Context: ${profile.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prefs,
                    onValueChange = { prefs = it },
                    label = { Text("Sender Preferences / Relationship") },
                    placeholder = { Text("e.g. Manager, prefers polite Hinglish") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Custom Contact Instructions") },
                    placeholder = { Text("e.g. Mention office hours 10 AM - 6 PM") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Block Auto-Reply for Contact")
                    }
                    Switch(checked = restricted, onCheckedChange = { restricted = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(prefs, instructions, restricted) }) {
                Text("Save Context")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AutomationSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidObsidian,
                checkedTrackColor = CyanPlasma
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
