package com.spoolstudio.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spoolstudio.app.ui.theme.SpoolStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise actual viewport constraints rather than only the picker label helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h320dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogViewportLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun boundedDialog_shortViewportAndLargeFont_keepsItsDoneActionReachable() {
        var actions = 0
        showScrollableDialog(onDone = { actions++ })

        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, actions)
        compose.onNodeWithTag("bounded-test-content").assertDoesNotExist()
    }

    @Test
    fun boundedDialog_outsideTapDismissesWithoutRunningTheChildAction() {
        var actions = 0
        showScrollableDialog(onDone = { actions++ })
        assertContentFitsWindow("bounded-test-content")

        compose.onNodeWithTag("bounded-dialog-backdrop").performTouchInput { click(Offset(2f, 2f)) }

        assertEquals(0, actions)
        compose.onNodeWithTag("bounded-test-content").assertDoesNotExist()
    }

    @Test
    fun colorPhoto_keepsImageCoordinatesAndLongColorName_andCanReachUseColor() {
        var photoSize = IntSize.Zero
        var useColor = 0
        var dismissals = 0
        val colorName = "Very deep midnight charcoal with a subtle blue undertone"
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        setTestFontScale(compose, 2f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SpoolStudioTheme {
                    ColorPhotoDialog(
                        bitmap = bitmap,
                        detectedHex = "151C24",
                        detectedName = colorName,
                        onPhotoViewSizeChange = { photoSize = it },
                        onPhotoTap = {},
                        onUseDetectedColor = { useColor++ },
                        onDismiss = { dismissals++ }
                    )
                }
            }
        }

        compose.waitForIdle()
        assertEquals("The photo sampling surface must retain its 320 dp height", 320, photoSize.height)
        assertTrue(photoSize.width > 0)
        assertContentFitsWindow("color-photo-dialog-content")
        val nameNode = compose.onNodeWithText(colorName).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        nameNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertRenderedTextFits(it, colorName, expectedFontScale = 2f) }

        compose.onNodeWithText("Use detected color").performScrollTo().assertIsDisplayed().performClick()
        saveRenderedWindow(compose, "color-photo-short-window-large-text.png")
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, useColor)
        assertEquals(1, dismissals)
    }

    private fun showScrollableDialog(onDone: () -> Unit) {
        setTestFontScale(compose, 2f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SpoolStudioTheme {
                    var open by remember { mutableStateOf(true) }
                    if (open) {
                        BoundedDialog(onDismissRequest = { open = false }) {
                            Card(Modifier.fillMaxWidth().testTag("bounded-test-content")) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    repeat(12) { Text("A long detail for this spool, row ${it + 1}") }
                                    Button(onClick = { onDone(); open = false }) { Text("Done") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertContentFitsWindow(tag: String) {
        val content = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("bounded-dialog-backdrop").fetchSemanticsNode().boundsInRoot
        assertTrue(content.left >= window.left && content.right <= window.right)
        assertTrue(content.top >= window.top && content.bottom <= window.bottom)
    }
}
