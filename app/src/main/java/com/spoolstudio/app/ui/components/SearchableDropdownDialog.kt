package com.spoolstudio.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spoolstudio.app.ui.theme.SpoolStudioColors
import com.spoolstudio.app.ui.theme.SpoolStudioShape

@Composable
fun <T> SearchableDropdownDialog(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    items: List<T>,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    topContent: @Composable ColumnScope.() -> Unit = {},
    showDefaultDivider: Boolean = false,
    itemContent: (@Composable (T) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            // The window fills the screen, so the visible margin needs a dismiss target.
            Box(
                Modifier.matchParentSize()
                    .testTag("searchable-dialog-backdrop")
                    .clickable(onClick = onDismiss)
            )
            Card(
                modifier = modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .testTag("searchable-dialog-card")
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                shape = SpoolStudioShape.Dialog,
                colors = CardDefaults.cardColors(containerColor = SpoolStudioColors.Graphite),
                elevation = CardDefaults.cardElevation(defaultElevation = 18.dp),
                border = BorderStroke(1.dp, SpoolStudioColors.GraphiteMuted.copy(alpha = 0.85f))
            ) {
                // Headers scroll too: large text and the keyboard cannot push rows out of bounds.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("searchable-dialog-list"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = SpoolStudioColors.OnGraphite,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close $title",
                                    tint = SpoolStudioColors.OnGraphite,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { onSearchQueryChange(it.take(60)) },
                            placeholder = { Text("Search") },
                            singleLine = true,
                            shape = SpoolStudioShape.Field,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                                .testTag("searchable-dialog-search"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SpoolStudioColors.OnGraphite,
                                unfocusedTextColor = SpoolStudioColors.OnGraphite,
                                focusedPlaceholderColor = SpoolStudioColors.OnGraphiteMuted,
                                unfocusedPlaceholderColor = SpoolStudioColors.OnGraphiteMuted.copy(alpha = 0.75f),
                                focusedBorderColor = SpoolStudioColors.AccentCyan,
                                unfocusedBorderColor = SpoolStudioColors.GraphiteMuted,
                                cursorColor = SpoolStudioColors.AccentCyan,
                                focusedContainerColor = SpoolStudioColors.GraphiteRaised.copy(alpha = 0.48f),
                                unfocusedContainerColor = SpoolStudioColors.GraphiteRaised.copy(alpha = 0.48f)
                            )
                        )
                    }
                    item { Column { topContent() } }
                    if (items.isEmpty()) {
                        item {
                            Text("No matches", color = SpoolStudioColors.OnGraphiteMuted,
                                modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                    itemsIndexed(items) { index, item ->
                        Column {
                            if (itemContent != null) itemContent(item)
                            else DropdownDialogItem(text = itemLabel(item), onClick = { onItemSelected(item) })
                            if (showDefaultDivider && searchQuery.isBlank() && index == 0 && items.size > 1) {
                                HorizontalDivider(
                                    color = SpoolStudioColors.GraphiteMuted.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DropdownDialogItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SpoolStudioColors.GraphiteRaised.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .border(1.dp, SpoolStudioColors.GraphiteMuted.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = SpoolStudioColors.OnGraphite)
    }
}
