package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.commands.PendingConfirmation
import com.example.ui.AssistantStatus
import com.example.ui.ConversationMessage
import com.example.ui.SaraUiState
import com.example.ui.components.SaraCoreVisualizer
import com.example.ui.components.VoiceWaveformStrip
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.voice.wake.SaraPowerMode
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudBorderBright
import com.example.ui.theme.HudCardElevated
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian

@Composable
fun AssistantHomeScreen(
    uiState: SaraUiState,
    hasMicPermission: Boolean,
    onMicClick: () -> Unit,
    onEndSessionClick: () -> Unit,
    onInterruptClick: () -> Unit,
    onSleepClick: () -> Unit,
    onWakeClick: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onNewChatClick: () -> Unit,
    onSendText: (String) -> Unit,
    onToggleGeminiLive: (Boolean) -> Unit,
    onConfirmAction: () -> Unit,
    onCancelAction: () -> Unit,
    onDismissBanner: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var typedInput by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    val isLiveBrain = uiState.behaviorSettings.geminiLiveEnabled
    val isSessionActive = uiState.status != AssistantStatus.IDLE &&
        uiState.status != AssistantStatus.ERROR

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(VoidObsidian, HudMidnight, VoidObsidian)
                )
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.sara_hud_banner_1794875513132),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .alpha(0.18f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top HUD Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SARA",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CyanPlasma
                    )
                    val profileSuffix = if (uiState.userProfile.name.isNotBlank()) {
                        " • ${uiState.userProfile.name}"
                    } else {
                        ""
                    }
                    Text(
                        text = if (isLiveBrain) {
                            "GEMINI LIVE BRAIN$profileSuffix"
                        } else {
                            "CLASSIC + ELEVENLABS$profileSuffix"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = isLiveBrain,
                        onClick = { onToggleGeminiLive(!isLiveBrain) },
                        label = {
                            Text(
                                text = if (isLiveBrain) "LIVE" else "CLASSIC",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldActive.copy(alpha = 0.18f),
                            selectedLabelColor = EmeraldActive,
                            selectedLeadingIconColor = EmeraldActive
                        ),
                        modifier = Modifier.testTag("gemini_live_mode_toggle")
                    )

                    IconButton(
                        onClick = onNewChatClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("new_chat_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = HudCardSurface,
                            contentColor = TextSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddComment,
                            contentDescription = "Start new conversation session"
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = HudCardSurface,
                            contentColor = CyanPlasma
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Open API and Assistant Settings"
                        )
                    }
                }
            }

            // API Readiness Banner
            val needsSetup = if (isLiveBrain) {
                !uiState.geminiCredentials.isConfigured
            } else {
                !uiState.geminiCredentials.isConfigured || !uiState.elevenLabsCredentials.isConfigured
            }
            AnimatedVisibility(visible = needsSetup) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = AmberThinking.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberThinking.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("setup_prompt_banner"),
                    onClick = onOpenSettings
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = AmberThinking,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val missing = buildList {
                            if (!uiState.geminiCredentials.isConfigured) add("Gemini API Key")
                            if (!isLiveBrain && !uiState.elevenLabsCredentials.isConfigured) {
                                add("ElevenLabs Key/Voice")
                            }
                        }.joinToString(" & ")
                        Text(
                            text = "Configure $missing in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberThinking,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.behaviorSettings.backgroundAssistantEnabled &&
                    !uiState.commandPermissions.canDrawOverlays &&
                    uiState.geminiCredentials.isConfigured
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = SapphirePulse.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, SapphirePulse.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("overlay_prompt_banner"),
                    onClick = onOpenOverlayPermission
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = CyanPlasma,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tap to allow 'Display over other apps' so Sara can open apps from background",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = uiState.statusBannerMessage != null) {
                val msg = uiState.statusBannerMessage.orEmpty()
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CrimsonAlert.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = CrimsonAlert,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissBanner,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss alert",
                                tint = CrimsonAlert,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Central Circular Futuristic AI Core
            SaraCoreVisualizer(
                status = uiState.status,
                waveAmplitude = uiState.waveAmplitude,
                liveCallMode = uiState.behaviorSettings.liveCallModeEnabled || isLiveBrain,
                onCoreClick = onMicClick
            )

            VoiceWaveformStrip(
                status = uiState.status,
                waveAmplitude = uiState.waveAmplitude
            )

            // Live transcripts (user & Sara streaming)
            AnimatedVisibility(visible = uiState.partialTranscript.isNotBlank()) {
                Text(
                    text = "You: \"${uiState.partialTranscript}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanPlasma,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 12.dp)
                )
            }
            AnimatedVisibility(visible = uiState.liveSaraTranscript.isNotBlank()) {
                Text(
                    text = "Sara: ${uiState.liveSaraTranscript}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SapphirePulse,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 12.dp)
                )
            }

            // Pending Action Confirmation Card
            AnimatedVisibility(visible = uiState.pendingConfirmation != null) {
                uiState.pendingConfirmation?.let { pending ->
                    PendingConfirmationCard(
                        pending = pending,
                        onConfirm = onConfirmAction,
                        onCancel = onCancelAction
                    )
                }
            }

            // Conversation History Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    ConversationBubble(message = msg)
                }
            }

            // Start / End Session Controls + Text Command Input
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // End Conversation Button
                    FilledTonalButton(
                        onClick = onEndSessionClick,
                        enabled = isSessionActive,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CrimsonAlert.copy(alpha = 0.22f),
                            contentColor = CrimsonAlert,
                            disabledContainerColor = HudCardSurface,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("stop_speaking_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "End conversation session",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("END", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // Primary Start / Active Voice Button
                    Button(
                        onClick = onMicClick,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                uiState.status == AssistantStatus.LISTENING ||
                                    uiState.status == AssistantStatus.CONNECTED -> EmeraldActive
                                uiState.status == AssistantStatus.SPEAKING -> SapphirePulse
                                uiState.status == AssistantStatus.CONNECTING ||
                                    uiState.status == AssistantStatus.RECONNECTING -> AmberThinking
                                else -> CyanPlasma
                            },
                            contentColor = VoidObsidian
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .size(62.dp)
                            .testTag("mic_button")
                    ) {
                        Icon(
                            imageVector = when {
                                !hasMicPermission -> Icons.Filled.MicOff
                                isSessionActive -> Icons.Filled.GraphicEq
                                else -> Icons.Filled.Mic
                            },
                            contentDescription = if (isSessionActive) {
                                "Conversation active, tap to end"
                            } else {
                                "Start voice conversation with Sara"
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // Interrupt while speaking OR Sleep/Wake Power Mode Pill
                    if (uiState.status == AssistantStatus.SPEAKING) {
                        FilledTonalButton(
                            onClick = onInterruptClick,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("interrupt_speaking_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = SapphirePulse,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("INTERRUPT", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (uiState.powerMode == SaraPowerMode.SLEEP || uiState.status == AssistantStatus.SLEEPING) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = SapphirePulse.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, SapphirePulse.copy(alpha = 0.65f)),
                            onClick = onWakeClick,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("power_mode_pill")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = "WAKE UP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanPlasma
                                )
                            }
                        }
                    } else if (uiState.powerMode == SaraPowerMode.ONLINE || isSessionActive) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = EmeraldActive.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.55f)),
                            onClick = onSleepClick,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("power_mode_pill")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                val bgTag = if (uiState.behaviorSettings.backgroundAssistantEnabled) "BG • " else ""
                                Text(
                                    text = "${bgTag}SLEEP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldActive
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = HudCardSurface,
                            border = BorderStroke(1.dp, HudBorder),
                            onClick = onSleepClick,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("power_mode_pill")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                val bgSuffix = if (uiState.behaviorSettings.backgroundAssistantEnabled) " • BG" else ""
                                Text(
                                    text = when {
                                        isLiveBrain -> "LIVE$bgSuffix"
                                        uiState.behaviorSettings.continuousConversationEnabled -> "LOOP$bgSuffix"
                                        else -> "READY$bgSuffix"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanPlasma
                                )
                            }
                        }
                    }
                }

                // Compact Command / Chat Input Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = typedInput,
                        onValueChange = { typedInput = it },
                        placeholder = {
                            Text(
                                "Talk or type to Sara...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (typedInput.isNotBlank()) {
                                    onSendText(typedInput)
                                    typedInput = ""
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPlasma,
                            unfocusedBorderColor = HudBorder,
                            focusedContainerColor = HudCardSurface,
                            unfocusedContainerColor = HudCardSurface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_input")
                    )

                    FilledIconButton(
                        onClick = {
                            if (typedInput.isNotBlank()) {
                                onSendText(typedInput)
                                typedInput = ""
                            }
                        },
                        enabled = typedInput.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SapphirePulse,
                            contentColor = Color.White,
                            disabledContainerColor = HudCardSurface,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("send_command_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingConfirmationCard(
    pending: PendingConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardElevated),
        border = BorderStroke(1.dp, AmberThinking),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("pending_confirmation_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = pending.summary,
                style = MaterialTheme.typography.titleMedium,
                color = AmberThinking
            )
            if (pending.resolvedDetail.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pending.resolvedDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldActive,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("confirm_action_button")
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Confirm")
                }
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, CrimsonAlert),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cancel_action_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ConversationBubble(message: ConversationMessage) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val containerColor = when {
        message.isError -> CrimsonAlert.copy(alpha = 0.14f)
        message.isUser -> SapphirePulse.copy(alpha = 0.22f)
        else -> HudCardSurface
    }
    val borderColor = when {
        message.isError -> CrimsonAlert.copy(alpha = 0.55f)
        message.isUser -> SapphirePulse.copy(alpha = 0.6f)
        else -> HudBorderBright
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.isUser) 18.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 18.dp
            ),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (message.isUser) "YOU" else "SARA",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isUser) SapphirePulse else CyanPlasma
                    )
                    if (!message.actionTag.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyanPlasma.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = message.actionTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPlasma,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isError) CrimsonAlert else MaterialTheme.colorScheme.onSurface
                )
                if (!message.actionStatus.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "▸ ${message.actionStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldActive
                    )
                }
            }
        }
    }
}
