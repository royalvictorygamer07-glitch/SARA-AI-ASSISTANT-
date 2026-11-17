package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.AssistantStatus
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorderBright
import com.example.ui.theme.HudCardElevated
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import com.example.voice.wake.WakePopupUiState

/**
 * Compact, Google/Gemini-style bottom floating popup shown when "Hey Sara" is triggered.
 * Intentionally avoids any large text input field, handing voice straight to Gemini Live.
 */
@Composable
fun WakePopupOverlay(
    popupState: WakePopupUiState,
    assistantStatus: AssistantStatus,
    onInterruptClick: () -> Unit,
    onDismissPopup: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = popupState.visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = HudCardElevated,
                border = BorderStroke(1.2.dp, HudBorderBright),
                shadowElevation = 18.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .testTag("sara_wake_popup")
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(HudMidnight, HudCardElevated, VoidObsidian)
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = CyanPlasma.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, CyanPlasma.copy(alpha = 0.6f)),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = "SARA Wake Core",
                                        tint = CyanPlasma,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = popupState.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyanPlasma
                                )
                                Text(
                                    text = popupState.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldActive,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismissPopup,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("wake_popup_dismiss_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Wake Popup",
                                tint = TextSecondary
                            )
                        }
                    }

                    if (popupState.liveCommandPreview.isNotBlank()) {
                        Text(
                            text = popupState.liveCommandPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    VoiceWaveformStrip(
                        status = assistantStatus,
                        waveAmplitude = popupState.waveAmplitude,
                        modifier = Modifier.height(26.dp)
                    )

                    if (assistantStatus == AssistantStatus.SPEAKING) {
                        FilledTonalButton(
                            onClick = onInterruptClick,
                            modifier = Modifier
                                .align(Alignment.End)
                                .height(48.dp)
                                .testTag("wake_popup_interrupt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = SapphirePulse,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Interrupt & Speak")
                        }
                    }
                }
            }
        }
    }
}
