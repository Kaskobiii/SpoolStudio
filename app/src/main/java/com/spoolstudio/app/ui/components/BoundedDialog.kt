package com.spoolstudio.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Keeps dialog content within the usable viewport, with a real outside-tap target. */
@Composable
internal fun BoundedDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize()
                    .testTag("bounded-dialog-backdrop")
                    .clickable(onClick = onDismissRequest)
            )
            Box(
                Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 600.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            ) {
                content()
            }
        }
    }
}
