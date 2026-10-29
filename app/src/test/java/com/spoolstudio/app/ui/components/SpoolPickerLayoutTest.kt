package com.spoolstudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.ui.theme.SpoolStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render the production picker on a small phone without requiring an emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpoolPickerLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var expectedFontScale = 1f

    @Test
    fun smallPhone_defaultTextShowsCompactIdentityAndRemainingWeight() {
        showPicker(fontScale = 1f)

        assertTextFits("Black PLA Plus", rowTag = "spool-row-1")
        assertTextFits("ID #1 · 866.3 g remaining")
        assertInsideCard("spool-row-1")
        saveScreenshot("spool-picker-small-phone.png")
    }

    @Test
    fun smallPhone_largeText_keepsSpoolIdentityAndWeightInsideThePicker() {
        showPicker(fontScale = 2f)

        compose.onNodeWithTag("searchable-dialog-list")
            .performScrollToNode(hasTestTag("spool-row-1"))

        assertTextFits("Black PLA Plus", rowTag = "spool-row-1")
        assertTextFits("ID #1 · 866.3 g remaining")
        assertInsideCard("spool-row-1")
        saveScreenshot("spool-picker-small-phone-large-text.png")

        compose.onNodeWithTag("searchable-dialog-list")
            .performScrollToNode(hasTestTag("spool-row-30"))
        compose.onNodeWithTag("spool-row-30").assertIsDisplayed()
        assertTextFits("ID #30 · 27 g remaining")
        assertInsideCard("spool-row-30")
        saveScreenshot("spool-picker-small-phone-last-spool.png")
    }

    @Test
    fun tappingOutsideDismissesWithoutSelectingASpool() {
        var selections = 0
        showPicker(fontScale = 1.5f, onSelected = { selections++ })

        compose.onNodeWithTag("searchable-dialog-backdrop").performTouchInput {
            click(Offset(2f, 2f))
        }

        compose.onNodeWithTag("searchable-dialog-card").assertDoesNotExist()
        assertEquals(0, selections)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w480dp-h320dp-land-mdpi")
    fun shortLandscapeWindow_canScrollToTheLastSpoolAtLargeTextSize() {
        showPicker(fontScale = 2f)

        compose.onNodeWithTag("searchable-dialog-list")
            .performScrollToNode(hasTestTag("spool-row-30"))
        assertTextFits("ID #30 · 27 g remaining")
        assertInsideCard("spool-row-30")
        saveScreenshot("spool-picker-short-landscape-large-text.png")
    }

    @Test
    fun interactingInsideSearchKeepsPickerOpen_andSpoolRemainsSelectable() {
        var selectedId: Int? = null
        showPicker(fontScale = 1.5f, onSelected = { selectedId = it.id })

        compose.onNodeWithTag("searchable-dialog-search").performTextInput("Black")
        compose.onNodeWithTag("searchable-dialog-card").assertIsDisplayed()
        compose.onNodeWithTag("spool-row-1").performClick()

        assertEquals(1, selectedId)
        compose.onNodeWithTag("searchable-dialog-card").assertDoesNotExist()
    }

    @Test
    fun ownerSelector_remainsReadableAndDismissesWithoutChangingTheOwner() {
        var selections = 0
        expectedFontScale = 2f
        setTestFontScale(compose, 2f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SpoolStudioTheme {
                    Column(Modifier.fillMaxSize().background(Color(0xFFF7F4EF)).padding(16.dp)
                        .testTag("selectors-preview")) {
                        OwnerFilterDropdown("Egon", listOf("All owners", "Egon", "Friend")) { selections++ }
                        SpoolSelectionField(
                            label = "Select from Spoolman",
                            value = "Black PLA Plus",
                            details = "ID #1 · 866.3 g remaining",
                            onClick = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("owner-selector").assertIsDisplayed()
        listOf("Show owner", "Egon", "Select from Spoolman", "Black PLA Plus", "ID #1 · 866.3 g remaining")
            .forEach { assertTextFits(it, containerTag = "selectors-preview") }
        saveScreenshot("spool-selectors-large-text.png")
        compose.onNodeWithTag("owner-selector").performClick()
        compose.onNodeWithTag("searchable-dialog-card").assertIsDisplayed()
        compose.onNodeWithTag("searchable-dialog-backdrop").performTouchInput { click(Offset(2f, 2f)) }

        compose.onNodeWithTag("searchable-dialog-card").assertDoesNotExist()
        compose.onNodeWithTag("owner-selector").assertIsDisplayed()
        assertEquals(0, selections)
    }

    private fun showPicker(fontScale: Float, onSelected: (FilamentSpool) -> Unit = {}) {
        expectedFontScale = fontScale
        setTestFontScale(compose, fontScale)
        val spools = (1..30).map { id ->
            FilamentSpool(
                id = id,
                material = "PLA",
                variant = "Plus",
                brand = "Elegoo",
                colorHex = "000000",
                minTemp = 200,
                maxTemp = 230,
                bedMinTemp = 50,
                bedMaxTemp = 60,
                remainingWeight = if (id == 1) 866.3f else 27f,
                spoolmanName = "Black",
                owner = "Egon"
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                SpoolStudioTheme {
                    var open by remember { mutableStateOf(true) }
                    var query by remember { mutableStateOf("") }
                    Box(Modifier.fillMaxSize().background(Color(0xFFF7F4EF))) {
                        Text("Spool Studio Shared")
                        if (open) {
                            SearchableDropdownDialog(
                                title = "Select from Spoolman",
                                searchQuery = query,
                                onSearchQueryChange = { query = it },
                                items = spools,
                                itemLabel = { it.spoolmanName.orEmpty() },
                                onItemSelected = {
                                    onSelected(it)
                                    open = false
                                },
                                onDismiss = { open = false },
                                itemContent = { spool ->
                                    SpoolmanDialogSpoolRow(spool) {
                                        onSelected(spool)
                                        open = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertTextFits(
        text: String,
        rowTag: String? = null,
        containerTag: String = "searchable-dialog-card"
    ) {
        val matcher = if (rowTag == null) hasText(text) else
            hasText(text) and hasAnyAncestor(hasTestTag(rowTag))
        val node = compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Text should have an actual rendered layout: $text", layouts.isNotEmpty())
        val textBounds = node.fetchSemanticsNode().boundsInRoot
        layouts.forEach { assertRenderedTextFits(it, text, expectedFontScale) }

        val cardBounds = compose.onNodeWithTag(containerTag).fetchSemanticsNode().boundsInRoot
        assertTrue("Rendered text height should not be clipped: $text", layouts.all {
            it.size.height <= textBounds.height + 1f
        })
        assertTrue("Text should fit inside picker: $text", cardBounds.contains(textBounds.topLeft))
        assertTrue("Text should fit inside picker: $text", textBounds.right <= cardBounds.right + 1f)
        assertTrue("Text should fit inside picker: $text", textBounds.bottom <= cardBounds.bottom + 1f)
    }

    private fun assertInsideCard(tag: String) {
        val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("searchable-dialog-card").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("searchable-dialog-backdrop").fetchSemanticsNode().boundsInRoot
        assertTrue("Row should fit horizontally", row.left >= card.left && row.right <= card.right)
        assertTrue("Row should fit vertically", row.top >= card.top && row.bottom <= card.bottom)
        assertTrue("Picker should fit on screen", card.left >= screen.left && card.right <= screen.right)
        assertTrue("Picker should fit on screen", card.top >= screen.top && card.bottom <= screen.bottom)
    }

    private fun saveScreenshot(name: String) = saveRenderedWindow(compose, name)
}
