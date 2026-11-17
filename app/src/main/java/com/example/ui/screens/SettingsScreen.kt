package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.security.GeminiCredentials
import com.example.core.security.UserProfile
import com.example.ui.SaraUiState
import com.example.ui.components.WakeUpDetectionCard
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: SaraUiState,
    onBack: () -> Unit,
    onSaveUserProfile: (name: String, preferredLanguage: String, preferencesNote: String) -> Unit,
    onSaveElevenLabs: (apiKey: String, voiceId: String) -> Unit,
    onClearElevenLabs: () -> Unit,
    onFetchElevenLabsVoices: (apiKey: String) -> Unit,
    onSaveGemini: (apiKey: String, modelName: String, liveModelName: String, liveVoiceName: String) -> Unit,
    onClearGemini: () -> Unit,
    onTestGemini: (apiKey: String, modelName: String) -> Unit,
    onToggleGeminiLive: (Boolean) -> Unit,
    onToggleContinuous: (Boolean) -> Unit,
    onToggleLiveCall: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onToggleConfirmBeforeAction: (Boolean) -> Unit,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onToggleWakeUpDetection: (Boolean) -> Unit,
    onImportVoskModelClick: () -> Unit,
    onClearVoskModelClick: () -> Unit,
    onRecordEnrollmentSample: () -> Unit,
    onStopEnrollmentRecording: () -> Unit,
    onClearEnrollment: () -> Unit,
    onToggleBargeIn: (Boolean) -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    onStartMicTest: () -> Unit,
    onStopMicTest: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onToggleWhatsAppAutomation: (Boolean) -> Unit,
    onOpenWhatsAppAutomation: () -> Unit,
    onRequestAllCommandPermissions: (Array<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var profileName by rememberSaveable { mutableStateOf(uiState.userProfile.name) }
    var profileLang by rememberSaveable { mutableStateOf(uiState.userProfile.preferredLanguage) }
    var profilePrefs by rememberSaveable { mutableStateOf(uiState.userProfile.preferencesNote) }
    var profileSavedNotice by rememberSaveable { mutableStateOf(false) }

    var elevenApiKey by rememberSaveable { mutableStateOf(uiState.elevenLabsCredentials.apiKey) }
    var elevenVoiceId by rememberSaveable { mutableStateOf(uiState.elevenLabsCredentials.voiceId) }
    var showElevenKey by rememberSaveable { mutableStateOf(false) }

    var geminiApiKey by rememberSaveable { mutableStateOf(uiState.geminiCredentials.apiKey) }
    var geminiModel by rememberSaveable { mutableStateOf(uiState.geminiCredentials.modelName) }
    var geminiLiveModel by rememberSaveable { mutableStateOf(uiState.geminiCredentials.liveModelName) }
    var geminiLiveVoice by rememberSaveable { mutableStateOf(uiState.geminiCredentials.liveVoiceName) }
    var showGeminiKey by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.userProfile) {
        if (profileName.isEmpty() && uiState.userProfile.name.isNotEmpty()) {
            profileName = uiState.userProfile.name
        }
        if (profileLang.isBlank()) {
            profileLang = uiState.userProfile.preferredLanguage
        }
        if (profilePrefs.isEmpty() && uiState.userProfile.preferencesNote.isNotEmpty()) {
            profilePrefs = uiState.userProfile.preferencesNote
        }
    }

    LaunchedEffect(uiState.elevenLabsCredentials) {
        if (elevenApiKey.isEmpty() && uiState.elevenLabsCredentials.apiKey.isNotEmpty()) {
            elevenApiKey = uiState.elevenLabsCredentials.apiKey
        }
        if (elevenVoiceId.isEmpty() && uiState.elevenLabsCredentials.voiceId.isNotEmpty()) {
            elevenVoiceId = uiState.elevenLabsCredentials.voiceId
        }
    }

    LaunchedEffect(uiState.geminiCredentials) {
        if (geminiApiKey.isEmpty() && uiState.geminiCredentials.apiKey.isNotEmpty()) {
            geminiApiKey = uiState.geminiCredentials.apiKey
        }
        if (geminiModel.isBlank()) {
            geminiModel = uiState.geminiCredentials.modelName
        }
        if (geminiLiveModel.isBlank()) {
            geminiLiveModel = uiState.geminiCredentials.liveModelName
        }
        if (geminiLiveVoice.isBlank()) {
            geminiLiveVoice = uiState.geminiCredentials.liveVoiceName
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoidObsidian, HudMidnight, VoidObsidian)))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CyanPlasma
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CyanPlasma
                    )
                    Text(
                        text = "Profile • Gemini Live Brain • ElevenLabs • Privacy",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Security Info Banner
            Surface(
                shape = MaterialTheme.shapes.small,
                color = EmeraldActive.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = EmeraldActive,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Gemini and ElevenLabs credentials are encrypted with separate Android Keystore AES-256-GCM keys in isolated local storage and are never logged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // SECTION 0: USER PROFILE & PREFERENCES
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = CyanPlasma)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "User Profile & Language Preferences",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanPlasma
                        )
                    }

                    OutlinedTextField(
                        value = profileName,
                        onValueChange = {
                            profileName = it
                            profileSavedNotice = false
                        },
                        label = { Text("Your Name") },
                        placeholder = { Text("How Sara should address you", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("user_profile_name_input")
                    )

                    Text(
                        text = "Preferred Language",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UserProfile.LANGUAGE_OPTIONS.forEach { lang ->
                            FilterChip(
                                selected = profileLang == lang,
                                onClick = {
                                    profileLang = lang
                                    profileSavedNotice = false
                                },
                                label = { Text(lang) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = profilePrefs,
                        onValueChange = {
                            profilePrefs = it
                            profileSavedNotice = false
                        },
                        label = { Text("Conversation Preferences") },
                        placeholder = {
                            Text("e.g., Keep answers concise, warm tone, software dev context", color = TextMuted)
                        },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("user_profile_prefs_input")
                    )

                    Button(
                        onClick = {
                            onSaveUserProfile(profileName, profileLang, profilePrefs)
                            profileSavedNotice = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPlasma,
                            contentColor = VoidObsidian
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_user_profile_button")
                    ) {
                        Text("Save Profile & Preferences")
                    }

                    AnimatedVisibility(visible = profileSavedNotice) {
                        Text(
                            text = "Profile saved. Sara will use this in your next conversation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldActive
                        )
                    }
                }
            }

            // SECTION 1: GEMINI AI & LIVE VOICE BRAIN SETTINGS
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = SapphirePulse
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gemini AI & Live Brain Settings",
                                style = MaterialTheme.typography.titleMedium,
                                color = SapphirePulse
                            )
                        }
                        if (uiState.geminiCredentials.isConfigured) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Gemini Configured",
                                tint = EmeraldActive,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    SettingToggleRow(
                        title = "Gemini Live API (Real-Time Voice)",
                        subtitle = "Low-latency bidirectional audio conversation via WebSocket.",
                        checked = uiState.behaviorSettings.geminiLiveEnabled,
                        onCheckedChange = onToggleGeminiLive
                    )

                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIza...", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (showGeminiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                Icon(
                                    imageVector = if (showGeminiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showGeminiKey) "Hide Gemini API Key" else "Show Gemini API Key"
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SapphirePulse,
                            unfocusedBorderColor = HudBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input")
                    )

                    OutlinedTextField(
                        value = geminiLiveModel,
                        onValueChange = { geminiLiveModel = it },
                        label = { Text("Gemini Live Voice Model ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SapphirePulse,
                            unfocusedBorderColor = HudBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_live_model_input")
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            GeminiCredentials.DEFAULT_GEMINI_LIVE_MODEL,
                            "gemini-2.0-flash-live-001"
                        ).forEach { preset ->
                            FilterChip(
                                selected = geminiLiveModel == preset,
                                onClick = { geminiLiveModel = preset },
                                label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Text(
                        text = "Sara Live Voice (Female Persona)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeminiCredentials.FEMALE_LIVE_VOICES.forEach { voice ->
                            FilterChip(
                                selected = geminiLiveVoice == voice,
                                onClick = { geminiLiveVoice = voice },
                                label = { Text(voice) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it },
                        label = { Text("Standard / Vision Gemini Model ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SapphirePulse,
                            unfocusedBorderColor = HudBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_model_input")
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            GeminiCredentials.DEFAULT_GEMINI_MODEL,
                            "gemini-2.5-pro",
                            "gemini-2.5-flash-lite"
                        ).forEach { preset ->
                            FilterChip(
                                selected = geminiModel == preset,
                                onClick = { geminiModel = preset },
                                label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onSaveGemini(
                                    geminiApiKey,
                                    geminiModel,
                                    geminiLiveModel,
                                    geminiLiveVoice
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SapphirePulse),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_gemini_button")
                        ) {
                            Text("Save Gemini")
                        }

                        OutlinedButton(
                            onClick = { onTestGemini(geminiApiKey, geminiModel) },
                            enabled = geminiApiKey.isNotBlank() && !uiState.isTestingGemini,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("test_gemini_button")
                        ) {
                            if (uiState.isTestingGemini) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Verify Key")
                            }
                        }

                        IconButton(
                            onClick = {
                                geminiApiKey = ""
                                onClearGemini()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Clear Gemini API key"
                            )
                        }
                    }

                    uiState.geminiTestResult?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.error else EmeraldActive
                        )
                    }
                }
            }

            // SECTION 2: ELEVENLABS SETTINGS (STRICTLY SEPARATE FOR CLASSIC VOICE MODE)
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = null,
                                tint = CyanPlasma
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ElevenLabs Voice Settings (Classic Mode)",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyanPlasma
                            )
                        }
                        if (uiState.elevenLabsCredentials.isConfigured) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "ElevenLabs Configured",
                                tint = EmeraldActive,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = elevenApiKey,
                        onValueChange = { elevenApiKey = it },
                        label = { Text("ElevenLabs API Key") },
                        placeholder = { Text("xi-...", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (showElevenKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showElevenKey = !showElevenKey }) {
                                Icon(
                                    imageVector = if (showElevenKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showElevenKey) "Hide ElevenLabs API Key" else "Show ElevenLabs API Key"
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPlasma,
                            unfocusedBorderColor = HudBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("elevenlabs_api_key_input")
                    )

                    OutlinedTextField(
                        value = elevenVoiceId,
                        onValueChange = { elevenVoiceId = it },
                        label = { Text("ElevenLabs Voice ID") },
                        placeholder = { Text("Enter Voice ID (e.g. 21m00Tcm4TlvDq8ikWAM)", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPlasma,
                            unfocusedBorderColor = HudBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("elevenlabs_voice_id_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSaveElevenLabs(elevenApiKey, elevenVoiceId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPlasma,
                                contentColor = VoidObsidian
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_elevenlabs_button")
                        ) {
                            Text("Save ElevenLabs")
                        }

                        OutlinedButton(
                            onClick = { onFetchElevenLabsVoices(elevenApiKey) },
                            enabled = elevenApiKey.isNotBlank() && !uiState.isFetchingVoices,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("fetch_voices_button")
                        ) {
                            if (uiState.isFetchingVoices) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Fetch Voices")
                            }
                        }

                        IconButton(
                            onClick = {
                                elevenApiKey = ""
                                elevenVoiceId = ""
                                onClearElevenLabs()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Clear ElevenLabs credentials"
                            )
                        }
                    }

                    AnimatedVisibility(visible = uiState.availableVoices.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Select Voice ID from your ElevenLabs account:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.availableVoices.take(12).forEach { voice ->
                                    FilterChip(
                                        selected = elevenVoiceId == voice.voiceId,
                                        onClick = {
                                            elevenVoiceId = voice.voiceId
                                            onSaveElevenLabs(elevenApiKey, voice.voiceId)
                                        },
                                        label = { Text(voice.name) }
                                    )
                                }
                            }
                        }
                    }

                    uiState.voicesFetchMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanPlasma
                        )
                    }
                }
            }

            // SECTION 3: ASSISTANT & SAFETY BEHAVIOR
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CloudSync,
                            contentDescription = null,
                            tint = CyanPlasma
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Conversation & Safety Controls",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    SettingToggleRow(
                        title = "Contextual Memory System",
                        subtitle = "Retain user preferences, facts, and conversation context locally.",
                        checked = uiState.behaviorSettings.memoryEnabled,
                        onCheckedChange = onToggleMemoryEnabled
                    )
                    SettingToggleRow(
                        title = "Continuous Conversation Loop (Classic)",
                        subtitle = "Automatically resume listening after Sara finishes speaking.",
                        checked = uiState.behaviorSettings.continuousConversationEnabled,
                        onCheckedChange = onToggleContinuous
                    )
                    SettingToggleRow(
                        title = "Low-Latency Call Tuning",
                        subtitle = "Keep Classic/TTS responses crisp for rapid voice turnaround.",
                        checked = uiState.behaviorSettings.liveCallModeEnabled,
                        onCheckedChange = onToggleLiveCall
                    )
                    SettingToggleRow(
                        title = "Speak Classic Responses via ElevenLabs",
                        subtitle = "Synthesize Classic & Vision answers with ElevenLabs.",
                        checked = uiState.behaviorSettings.autoSpeakResponses,
                        onCheckedChange = onToggleAutoSpeak
                    )
                    SettingToggleRow(
                        title = "Confirm Calls & WhatsApp Messages",
                        subtitle = "Always verify contact and message before initiating external actions.",
                        checked = uiState.behaviorSettings.confirmBeforeExternalAction,
                        onCheckedChange = onToggleConfirmBeforeAction
                    )
                }
            }

            // SECTION 4: ADVANCED VOICE SYSTEM & WAKE UP DETECTION
            WakeUpDetectionCard(
                uiState = uiState,
                onToggleWakeUpDetection = onToggleWakeUpDetection,
                onImportVoskModelClick = onImportVoskModelClick,
                onClearVoskModelClick = onClearVoskModelClick,
                onRecordEnrollmentSample = onRecordEnrollmentSample,
                onStopEnrollmentRecording = onStopEnrollmentRecording,
                onClearEnrollment = onClearEnrollment,
                onToggleBargeIn = onToggleBargeIn,
                onSensitivityChanged = onSensitivityChanged,
                onStartMicTest = onStartMicTest,
                onStopMicTest = onStopMicTest,
                onOpenBatterySettings = onOpenBatterySettings
            )

            // SECTION 5: AUTOMATION -> WHATSAPP AUTOMATION
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CyanPlasma)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Automation → WhatsApp Automation",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyanPlasma
                                )
                                Text(
                                    text = "Status: ${uiState.waServiceStatus.label} • ${if (uiState.waNotificationAccessGranted) "Access Granted" else "Permission Needed"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = uiState.waAutomationSettings.automationEnabled,
                            onCheckedChange = onToggleWhatsAppAutomation,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = VoidObsidian,
                                checkedTrackColor = CyanPlasma
                            ),
                            modifier = Modifier.testTag("settings_wa_automation_switch")
                        )
                    }

                    Text(
                        text = uiState.waStatusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    val missingPerms = uiState.commandPermissions.missingPermissions()
                    Text(
                        text = if (missingPerms.isEmpty()) {
                            "Command Permissions: All Ready (Mic, Contacts, Calling, Call Guard, Bluetooth, Alerts, Camera)"
                        } else {
                            "Missing Command Permissions: ${missingPerms.size} (Contacts/Call/Mic/Phone/Bluetooth/Alerts)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (missingPerms.isEmpty()) EmeraldActive else CyanPlasma
                    )

                    if (missingPerms.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { onRequestAllCommandPermissions(missingPerms) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_all_command_permissions_button")
                        ) {
                            Text("Grant All Command Permissions (${missingPerms.size})")
                        }
                    }

                    Button(
                        onClick = onOpenWhatsAppAutomation,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPlasma,
                            contentColor = VoidObsidian
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_whatsapp_automation_button")
                    ) {
                        Text("Open WhatsApp Automation Panel")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidObsidian,
                checkedTrackColor = CyanPlasma
            )
        )
    }
}
