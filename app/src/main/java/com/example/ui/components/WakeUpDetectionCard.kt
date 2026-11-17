package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.SaraUiState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardElevated
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import com.example.voice.wake.WakePhraseEnrollmentManager
import com.example.voice.wake.WakeServiceState

@Composable
fun WakeUpDetectionCard(
    uiState: SaraUiState,
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
    modifier: Modifier = Modifier
) {
    val inspection = uiState.voskInspection
    val enrollment = uiState.wakeEnrollmentState
    val micTest = uiState.micTestState
    val behavior = uiState.behaviorSettings

    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
        border = BorderStroke(1.dp, HudBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + Master Toggle ("Wake Up Detection")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Hearing, contentDescription = null, tint = CyanPlasma)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Wake Up Detection",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanPlasma
                        )
                        Text(
                            text = "Hands-free \"Hey Sara\" background service",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Switch(
                    checked = behavior.wakeUpDetectionEnabled,
                    onCheckedChange = onToggleWakeUpDetection,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidObsidian,
                        checkedTrackColor = CyanPlasma
                    ),
                    modifier = Modifier.testTag("wake_up_detection_switch")
                )
            }

            // Live Service State Pill
            val (badgeColor, badgeText) = when (uiState.wakeServiceState) {
                WakeServiceState.LISTENING -> EmeraldActive to "FOREGROUND MIC ACTIVE"
                WakeServiceState.PAUSED_FOR_SESSION -> SapphirePulse to "PAUSED FOR VOICE SESSION"
                WakeServiceState.STARTING -> AmberThinking to "LOADING MODEL"
                WakeServiceState.WAKE_TRIGGERED -> EmeraldActive to "WAKE TRIGGERED"
                WakeServiceState.MODEL_MISSING -> AmberThinking to "VOSK MODEL REQUIRED"
                WakeServiceState.PERMISSION_MISSING -> CrimsonAlert to "MIC PERMISSION REQUIRED"
                WakeServiceState.ERROR -> CrimsonAlert to "SERVICE ERROR"
                WakeServiceState.DISABLED -> TextMuted to "DISABLED"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = badgeColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                    val detail = uiState.wakeServiceDiagnostic
                        ?: "When enabled, a persistent Android notification indicates active microphone monitoring."
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Vosk Local Model Inspector
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HudCardElevated,
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (inspection?.isReady == true) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = if (inspection?.isReady == true) EmeraldActive else AmberThinking,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Offline Vosk Model Status",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (inspection != null) {
                        Text(
                            text = "Source: ${inspection.sourceLabel} • Size: ${inspection.formattedSizeMb} • Lang: ${inspection.detectedLanguageHint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanPlasma
                        )
                        Text(
                            text = inspection.diagnosticMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportVoskModelClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("import_vosk_model_button")
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Vosk Folder")
                        }

                        if (inspection?.isReady == true && inspection.sourceLabel.startsWith("Imported")) {
                            TextButton(
                                onClick = onClearVoskModelClick,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Remove", color = CrimsonAlert)
                            }
                        }
                    }
                }
            }

            // 5-Phrase Wake Enrollment ("Hey Sara" 1/5 .. 5/5)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HudCardElevated,
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "\"Hey Sara\" Voice Enrollment",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${enrollment.profile.enrolledCount}/5",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (enrollment.profile.isComplete) EmeraldActive else CyanPlasma
                        )
                    }

                    // 5 visual progress segments
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (idx in 1..5) {
                            val filled = idx <= enrollment.profile.enrolledCount
                            val recordingThis = enrollment.isRecordingSample && enrollment.activeSampleNumber == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            recordingThis -> AmberThinking
                                            filled -> EmeraldActive
                                            else -> HudBorder
                                        }
                                    )
                            )
                        }
                    }

                    Text(
                        text = enrollment.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    AnimatedVisibility(visible = enrollment.isRecordingSample) {
                        LinearProgressIndicator(
                            progress = { enrollment.liveAmplitude.coerceIn(0f, 1f) },
                            color = AmberThinking,
                            trackColor = HudBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (enrollment.isRecordingSample) {
                            Button(
                                onClick = onStopEnrollmentRecording,
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("stop_enrollment_button")
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop Recording")
                            }
                        } else {
                            Button(
                                onClick = onRecordEnrollmentSample,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanPlasma,
                                    contentColor = VoidObsidian
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("record_wake_sample_button")
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                val label = if (enrollment.profile.isComplete) {
                                    "Re-record Sample 5/5"
                                } else {
                                    "Record \"Hey Sara\" (${enrollment.profile.enrolledCount + 1}/5)"
                                }
                                Text(label)
                            }
                        }

                        if (enrollment.profile.enrolledCount > 0 && !enrollment.isRecordingSample) {
                            OutlinedButton(
                                onClick = onClearEnrollment,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("reset_wake_enrollment_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Reset wake enrollment", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Sensitivity + Barge-In Interrupt Control
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Wake Sensitivity (${(behavior.wakeSensitivity * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Slider(
                    value = behavior.wakeSensitivity,
                    onValueChange = onSensitivityChanged,
                    valueRange = 0.35f..0.95f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanPlasma,
                        activeTrackColor = CyanPlasma
                    ),
                    modifier = Modifier.testTag("wake_sensitivity_slider")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speak-to-Interrupt (Barge-In)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Interrupt Sara promptly when you start a new command while she is speaking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = behavior.bargeInEnabled,
                    onCheckedChange = onToggleBargeIn,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidObsidian,
                        checkedTrackColor = CyanPlasma
                    ),
                    modifier = Modifier.testTag("barge_in_switch")
                )
            }

            // Microphone Test + Battery Optimization Status
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HudCardElevated,
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Microphone Diagnostic & Battery Reliability",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Hardware AEC: ${if (micTest.aecSupported) "Supported" else "Not reported"} • Noise Suppressor: ${if (micTest.nsSupported) "Supported" else "Not reported"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanPlasma
                    )
                    Text(
                        text = micTest.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    AnimatedVisibility(visible = micTest.isTesting) {
                        LinearProgressIndicator(
                            progress = { micTest.liveAmplitude.coerceIn(0f, 1f) },
                            color = EmeraldActive,
                            trackColor = HudBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { if (micTest.isTesting) onStopMicTest() else onStartMicTest() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("test_microphone_button")
                        ) {
                            Icon(
                                imageVector = if (micTest.isTesting) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (micTest.isTesting) "Stop Mic Test" else "Test Microphone")
                        }

                        OutlinedButton(
                            onClick = onOpenBatterySettings,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("battery_optimization_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (uiState.isIgnoringBatteryOptimizations) EmeraldActive else AmberThinking,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (uiState.isIgnoringBatteryOptimizations) "Unrestricted" else "Battery Rules"
                            )
                        }
                    }
                }
            }
        }
    }
}
