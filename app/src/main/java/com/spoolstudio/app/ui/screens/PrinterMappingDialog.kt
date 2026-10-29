package com.spoolstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.ui.PrinterToolheadLiveStatus
import com.spoolstudio.app.ui.components.filterSpoolmanDropdownFilaments
import com.spoolstudio.app.ui.components.spoolmanDropdownListLabel
import com.spoolstudio.app.ui.components.SearchableDropdownDialog
import com.spoolstudio.app.ui.components.spoolmanDropdownLabel
import com.spoolstudio.app.ui.components.spoolmanDropdownDetails
import com.spoolstudio.app.ui.theme.SpoolStudioColors
import com.spoolstudio.app.ui.theme.SpoolStudioShape
import com.spoolstudio.app.ui.theme.spoolStudioBackground

@Composable
fun PrinterMappingDialogHost(
    visible: Boolean,
    spools: List<FilamentSpool>,
    isMoonrakerReachable: Boolean,
    isLoadingPrinterMapping: Boolean,
    printerIntegrationModeLabel: String?,
    inlineStatusText: String?,
    inlineStatusColor: Color,
    toolhead1SpoolId: Int?,
    toolhead2SpoolId: Int?,
    toolhead3SpoolId: Int?,
    toolhead4SpoolId: Int?,
    toolheadStatuses: List<PrinterToolheadLiveStatus>,
    onCancel: () -> Unit,
    onLoadCurrentPrinterMapping: () -> Unit,
    onAssignPrinterToolhead: (Int, Int?) -> Unit
) {
    if (!visible) return

    PrinterMappingDialog(
        spools = spools,
        isMoonrakerReachable = isMoonrakerReachable,
        isLoadingPrinterMapping = isLoadingPrinterMapping,
        printerIntegrationModeLabel = printerIntegrationModeLabel,
        inlineStatusText = inlineStatusText,
        inlineStatusColor = inlineStatusColor,
        toolhead1SpoolId = toolhead1SpoolId,
        toolhead2SpoolId = toolhead2SpoolId,
        toolhead3SpoolId = toolhead3SpoolId,
        toolhead4SpoolId = toolhead4SpoolId,
        toolheadStatuses = toolheadStatuses,
        onCancel = onCancel,
        onLoadCurrentPrinterMapping = onLoadCurrentPrinterMapping,
        onAssignPrinterToolhead = onAssignPrinterToolhead
    )
}

@Composable
fun PrinterMappingDialog(
    spools: List<FilamentSpool>,
    isMoonrakerReachable: Boolean,
    isLoadingPrinterMapping: Boolean,
    printerIntegrationModeLabel: String?,
    inlineStatusText: String?,
    inlineStatusColor: Color,
    toolhead1SpoolId: Int?,
    toolhead2SpoolId: Int?,
    toolhead3SpoolId: Int?,
    toolhead4SpoolId: Int?,
    toolheadStatuses: List<PrinterToolheadLiveStatus>,
    onCancel: () -> Unit,
    onLoadCurrentPrinterMapping: () -> Unit,
    onAssignPrinterToolhead: (Int, Int?) -> Unit
) {
    var showAppComposedData by remember { mutableStateOf(true) }
    var assigningToolheadIndex by remember { mutableStateOf<Int?>(null) }
    var assignSearchQuery by remember { mutableStateOf("") }
    val toolheads = listOf(
        ToolheadDialogRow("Toolhead 1", spools.firstOrNull { it.id == toolhead1SpoolId }, toolheadStatuses.firstOrNull { it.toolheadIndex == 0 }),
        ToolheadDialogRow("Toolhead 2", spools.firstOrNull { it.id == toolhead2SpoolId }, toolheadStatuses.firstOrNull { it.toolheadIndex == 1 }),
        ToolheadDialogRow("Toolhead 3", spools.firstOrNull { it.id == toolhead3SpoolId }, toolheadStatuses.firstOrNull { it.toolheadIndex == 2 }),
        ToolheadDialogRow("Toolhead 4", spools.firstOrNull { it.id == toolhead4SpoolId }, toolheadStatuses.firstOrNull { it.toolheadIndex == 3 })
    )
    val assignedCount = toolheads.count { it.spool != null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .spoolStudioBackground()
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            shape = SpoolStudioShape.Dialog,
            colors = CardDefaults.cardColors(
                containerColor = SpoolStudioColors.Graphite
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Toolhead status",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = SpoolStudioColors.OnGraphite
                        )
                        Text(
                            text = "Current spool data reported by the printer",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpoolStudioColors.OnGraphiteMuted
                        )
                    }

                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = SpoolStudioColors.OnGraphite
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SpoolStudioShape.Field,
                    colors = CardDefaults.cardColors(
                        containerColor = SpoolStudioColors.GraphiteRaised.copy(alpha = 0.62f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isMoonrakerReachable) "Printer connected" else "Printer not reachable",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isMoonrakerReachable) SpoolStudioColors.GoldSoft else SpoolStudioColors.Error
                            )
                            Text(
                                text = "$assignedCount of 4 toolheads assigned",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpoolStudioColors.OnGraphiteMuted,
                            )
                        }

                        if (isLoadingPrinterMapping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = SpoolStudioColors.AccentCyan
                            )
                        }
                    }
                }

                inlineStatusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = inlineStatusColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showAppComposedData = true },
                        shape = SpoolStudioShape.Button,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (showAppComposedData) SpoolStudioColors.AccentCyan else Color.Transparent,
                            contentColor = if (showAppComposedData) SpoolStudioColors.OnGraphite else SpoolStudioColors.AccentCyan
                        )
                    ) {
                        Text(
                            text = "App labels",
                        )
                    }
                    OutlinedButton(
                        onClick = { showAppComposedData = false },
                        shape = SpoolStudioShape.Button,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!showAppComposedData) SpoolStudioColors.AccentCyan else Color.Transparent,
                            contentColor = if (!showAppComposedData) SpoolStudioColors.OnGraphite else SpoolStudioColors.AccentCyan
                        )
                    ) {
                        Text(
                            text = "SpoolLink",
                        )
                    }
                }

                HorizontalDivider(color = SpoolStudioColors.GraphiteMuted.copy(alpha = 0.75f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    toolheads.forEachIndexed { index, row ->
                        MappingToolheadStatusCard(
                            label = row.label,
                            spool = row.spool,
                            liveStatus = row.liveStatus,
                            showAppComposedData = showAppComposedData,
                            assignEnabled = isMoonrakerReachable && !isLoadingPrinterMapping,
                            onAssignClick = {
                                assignSearchQuery = ""
                                assigningToolheadIndex = index
                            }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onLoadCurrentPrinterMapping,
                    enabled = isMoonrakerReachable && !isLoadingPrinterMapping,
                    shape = SpoolStudioShape.Button,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SpoolStudioColors.AccentCyan,
                        disabledContentColor = SpoolStudioColors.OnGraphiteMuted.copy(alpha = 0.55f)
                    )
                ) {
                    Text(
                        text = "Refresh toolhead status",
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        assigningToolheadIndex?.let { index ->
            AssignToolheadSpoolDialog(
                toolheadLabel = "Toolhead ${index + 1}",
                spools = spools,
                searchQuery = assignSearchQuery,
                onSearchQueryChange = { assignSearchQuery = it },
                onDismiss = { assigningToolheadIndex = null },
                onSpoolSelected = { spoolId ->
                    assigningToolheadIndex = null
                    onAssignPrinterToolhead(index, spoolId)
                }
            )
        }
    }
}

private data class ToolheadDialogRow(
    val label: String,
    val spool: FilamentSpool?,
    val liveStatus: PrinterToolheadLiveStatus?
)

@Composable
private fun AssignToolheadSpoolDialog(
    toolheadLabel: String,
    spools: List<FilamentSpool>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSpoolSelected: (Int?) -> Unit
) {
    val assignableSpools = remember(spools) {
        spools.sortedWith(compareBy<FilamentSpool> { it.id ?: Int.MAX_VALUE }
            .thenBy { it.brand.lowercase() }
            .thenBy { it.spoolmanName.orEmpty().lowercase() }
        )
    }
    val filteredSpools = filterSpoolmanDropdownFilaments(assignableSpools, searchQuery)

    SearchableDropdownDialog(
        title = "Assign spool · $toolheadLabel",
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        items = filteredSpools,
        itemLabel = ::spoolmanDropdownListLabel,
        onItemSelected = { onSpoolSelected(it.id) },
        onDismiss = onDismiss,
        topContent = {
            Text(
                text = "Manual assignment is temporary. Spools without UID data or v3 field conversion can be selected, but the printer may replace them after the next RFID read.",
                style = MaterialTheme.typography.bodySmall,
                color = SpoolStudioColors.OnGraphiteMuted,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            AssignSpoolRow(
                title = "Clear printer assignment",
                subtitle = "Empty · Keeps tag links",
                warningText = "RFID scans can assign it again",
                accentColor = SpoolStudioColors.OnGraphiteMuted.copy(alpha = 0.35f),
                onClick = { onSpoolSelected(null) }
            )
            HorizontalDivider(
                color = SpoolStudioColors.GraphiteMuted.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 10.dp)
            )
        },
        itemContent = { spool ->
            val warningText = when {
                !spool.isPaxx12FieldFormat -> "Needs v3 conversion"
                spool.cardUids.isEmpty() -> "No UID stored"
                else -> null
            }
            AssignSpoolRow(
                title = spoolmanDropdownLabel(spool),
                subtitle = spoolmanDropdownDetails(spool),
                warningText = warningText,
                accentColor = resolveSpoolColor(spool.colorHex),
                onClick = { onSpoolSelected(spool.id) }
            )
        }
    )
}

@Composable
private fun AssignSpoolRow(
    title: String,
    subtitle: String,
    warningText: String? = null,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(SpoolStudioShape.Field)
            .background(SpoolStudioColors.GraphiteRaised.copy(alpha = 0.72f))
            .border(1.dp, SpoolStudioColors.GraphiteMuted.copy(alpha = 0.85f), SpoolStudioShape.Field)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(accentColor)
                .border(1.dp, SpoolStudioColors.OnGraphiteMuted, CircleShape)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = SpoolStudioColors.OnGraphite)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SpoolStudioColors.OnGraphiteMuted)
            if (warningText != null) {
                Text(warningText, style = MaterialTheme.typography.bodySmall, color = SpoolStudioColors.GoldSoft)
            }
        }
    }
}
