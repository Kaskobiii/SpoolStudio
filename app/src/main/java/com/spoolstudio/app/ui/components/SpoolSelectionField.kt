package com.spoolstudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spoolstudio.app.ui.theme.SpoolStudioColors
import com.spoolstudio.app.ui.theme.SpoolStudioShape

@Composable
internal fun SpoolSelectionField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    details: String? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clip(SpoolStudioShape.Small)
            .background(SpoolStudioColors.GraphiteRaised)
            .border(1.dp, SpoolStudioColors.GraphiteMuted, SpoolStudioShape.Small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = SpoolStudioColors.OnGraphiteMuted,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp))
            Text(value, color = SpoolStudioColors.OnGraphite,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
            if (!details.isNullOrBlank()) {
                Text(details, color = SpoolStudioColors.OnGraphiteMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
            }
        }
        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
        else Icon(Icons.Default.ArrowDropDown, contentDescription = null,
            tint = SpoolStudioColors.OnGraphiteMuted, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun OwnerFilterDropdown(
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    SpoolSelectionField(
        label = "Show owner", value = value,
        modifier = Modifier.testTag("owner-selector"),
        onClick = { focusManager.clearFocus(); query = ""; expanded = true }
    )
    if (expanded) {
        SearchableDropdownDialog(
            title = "Show owner",
            searchQuery = query,
            onSearchQueryChange = { query = it },
            items = options.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) },
            itemLabel = { it },
            onItemSelected = { onSelected(it); focusManager.clearFocus(); expanded = false },
            onDismiss = { focusManager.clearFocus(); expanded = false }
        )
    }
}
