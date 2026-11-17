package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.memory.ConversationSessionEntity
import com.example.memory.ConversationTurnEntity
import com.example.memory.MemoryCategory
import com.example.memory.MemoryRecord
import com.example.ui.SaraUiState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    uiState: SaraUiState,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onAddMemory: (MemoryCategory, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearAll: () -> Unit,
    onSelectHistorySession: (String?) -> Unit,
    onDeleteHistorySession: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedCategory by rememberSaveable { mutableStateOf<MemoryCategory?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(uiState.memories, selectedCategory) {
        if (selectedCategory == null) {
            uiState.memories
        } else {
            uiState.memories.filter { it.category == selectedCategory }
        }
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
                .fillMaxSize()
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MEMORY & HISTORY",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CyanPlasma
                    )
                    Text(
                        text = "Storage: ${uiState.memoryBackendName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Switch(
                    checked = uiState.behaviorSettings.memoryEnabled,
                    onCheckedChange = onToggleMemoryEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidObsidian,
                        checkedTrackColor = CyanPlasma
                    ),
                    modifier = Modifier.testTag("memory_enabled_switch")
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = HudCardSurface,
                contentColor = CyanPlasma
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Long-Term Memory") },
                    icon = { Icon(Icons.Filled.Psychology, contentDescription = null) },
                    modifier = Modifier.testTag("tab_long_term_memory")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Past Chats (${uiState.pastSessions.size})") },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    modifier = Modifier.testTag("tab_conversation_history")
                )
            }

            if (selectedTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPlasma,
                            contentColor = VoidObsidian
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("add_memory_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Memory")
                    }

                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        enabled = uiState.memories.isNotEmpty(),
                        border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("clear_memory_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear All")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All (${uiState.memories.size})") }
                    )
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                        border = BorderStroke(1.dp, HudBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = CyanPlasma,
                                modifier = Modifier.size(40.dp)
                            )
                            Text("No memories stored yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Sara automatically remembers key preferences, user details, and tasks when Memory is enabled, or you can add entries manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            MemoryItemCard(
                                record = item,
                                onDelete = { onDeleteMemory(item.id) }
                            )
                        }
                    }
                }
            } else {
                ConversationHistoryTabContent(
                    sessions = uiState.pastSessions,
                    selectedSessionId = uiState.selectedSessionId,
                    selectedSessionTurns = uiState.selectedSessionTurns,
                    onSelectSession = onSelectHistorySession,
                    onDeleteSession = onDeleteHistorySession,
                    onClearAllHistory = onClearAllHistory,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onSave = { cat, content ->
                onAddMemory(cat, content)
                showAddDialog = false
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Memories?") },
            text = { Text("This will permanently remove all locally stored Sara memories.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearConfirm = false
                    }
                ) {
                    Text("Clear All", color = CrimsonAlert)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConversationHistoryTabContent(
    sessions: List<ConversationSessionEntity>,
    selectedSessionId: String?,
    selectedSessionTurns: List<ConversationTurnEntity>,
    onSelectSession: (String?) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selectedSessionId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSelectSession(null) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to past sessions",
                        tint = CyanPlasma
                    )
                }
                Text(
                    text = "Session Transcript (${selectedSessionTurns.size} turns)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanPlasma
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedSessionTurns, key = { it.id }) { turn ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (turn.isUser) {
                                SapphirePulse.copy(alpha = 0.16f)
                            } else {
                                HudCardSurface
                            }
                        ),
                        border = BorderStroke(1.dp, HudBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (turn.isUser) "YOU" else "SARA (${turn.sourceMode})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (turn.isUser) SapphirePulse else CyanPlasma
                                )
                                Text(
                                    text = formatter.format(Date(turn.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            if (sessions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onClearAllHistory,
                        border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear History")
                    }
                }
            }

            if (sessions.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                    border = BorderStroke(1.dp, HudBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = CyanPlasma,
                            modifier = Modifier.size(40.dp)
                        )
                        Text("No past conversations yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Every voice or text conversation with Sara is saved locally in Room so you can review past sessions anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                            border = BorderStroke(1.dp, HudBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(session.sessionId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${session.mode} • ${formatter.format(Date(session.updatedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSession(session.sessionId) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete conversation session",
                                        tint = CrimsonAlert
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryItemCard(
    record: MemoryRecord,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
        border = BorderStroke(1.dp, HudBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = CyanPlasma.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = record.category.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPlasma,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = formatter.format(Date(record.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete memory",
                    tint = CrimsonAlert
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (MemoryCategory, String) -> Unit
) {
    var content by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(MemoryCategory.USER_INFO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Information to remember") },
                    placeholder = { Text("e.g., Papa's birthday is 12 May") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = { onSave(category, content) }
            ) {
                Text("Save", color = CyanPlasma)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
