package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.commands.ActionCategory
import com.example.ui.SaraUiState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsHubScreen(
    uiState: SaraUiState,
    onRunCommand: (String) -> Unit,
    onRefreshIncomingReport: () -> Unit,
    onSimulateIncomingCall: (String) -> Unit,
    onSimulateIncomingDecision: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permVersion by remember { mutableIntStateOf(0) }

    val multiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permVersion++
        onRefreshIncomingReport()
    }

    val permissions = remember(permVersion) {
        listOf(
            "Microphone (STT)" to Manifest.permission.RECORD_AUDIO,
            "Camera (Vision)" to Manifest.permission.CAMERA,
            "Contacts (Call/Msg)" to Manifest.permission.READ_CONTACTS,
            "Phone Call" to Manifest.permission.CALL_PHONE,
            "Phone State (Incoming)" to Manifest.permission.READ_PHONE_STATE
        ).map { (label, perm) ->
            Triple(
                label,
                perm,
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            )
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "ACTION MATRIX",
                style = MaterialTheme.typography.headlineMedium,
                color = CyanPlasma
            )
            Text(
                text = "Modular Command Routing, Permissions & Telecom Diagnostics",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Quick Command Launcher
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
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = CyanPlasma)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Test Modular Voice Commands",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = "Tap any command to route through Gemini -> Command Parser -> Action Handler -> ElevenLabs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "SARA, YouTube kholo.",
                            "SARA, YouTube par Arijit Singh ka gana chalao.",
                            "Mummy ko call karo.",
                            "Rahul ko WhatsApp par message karo ki main 10 minute mein aa raha hoon."
                        ).forEach { cmd ->
                            AssistChip(
                                onClick = { onRunCommand(cmd) },
                                label = { Text(cmd, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }

            // Supported Action Categories
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Registered Command Categories",
                        style = MaterialTheme.typography.titleMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ActionCategory.entries.forEach { cat ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = SapphirePulse.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, SapphirePulse.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanPlasma,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Runtime Permissions Readiness
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Android Permission Readiness",
                        style = MaterialTheme.typography.titleMedium
                    )
                    permissions.forEach { (label, perm, granted) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = if (granted) EmeraldActive else AmberThinking,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!granted) {
                                OutlinedButton(
                                    onClick = { multiPermissionLauncher.launch(arrayOf(perm)) },
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text("Grant", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text(
                                    text = "GRANTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldActive
                                )
                            }
                        }
                    }
                }
            }

            // Incoming Call Assistant Architecture & Capability Check
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
                        Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = AmberThinking)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Incoming Call Assistant (Telecom Readiness)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    uiState.incomingCallReport?.let { report ->
                        Text(
                            text = "${report.androidVersionLabel} • Default Dialer Role: ${if (report.isDefaultDialerApp) "YES" else "NO"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (report.isDefaultDialerApp) EmeraldActive else AmberThinking
                        )
                        Text(
                            text = report.summaryStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = report.platformLimitationNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSimulateIncomingCall("Papa") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPlasma,
                                contentColor = VoidObsidian
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("simulate_incoming_call_button")
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preview Prompt")
                        }
                        OutlinedButton(
                            onClick = { onSimulateIncomingDecision(true) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Say 'Ha'")
                        }
                        OutlinedButton(
                            onClick = { onSimulateIncomingDecision(false) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Decline")
                        }
                    }

                    uiState.incomingCallSimResult?.let { sim ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = VoidObsidian.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, CyanPlasma.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = sim,
                                style = MaterialTheme.typography.bodySmall,
                                color = CyanPlasma,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
