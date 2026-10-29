package com.spoolstudio.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.spoolstudio.app.data.local.MaterialDatabase
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.ui.SpoolMode
import com.spoolstudio.app.ui.screens.SpoolFormCard
import com.spoolstudio.app.ui.screens.SpoolFormState
import com.spoolstudio.app.ui.theme.SpoolStudioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainFormLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actualMainForm_atLargeText_keepsUidRemainingGramsAndTemperaturesReadable() {
        val uid = "0489F3A1832681"
        val spool = FilamentSpool(
            id = 1, material = "PLA", variant = "Plus", brand = "Elegoo", colorHex = "000000",
            minTemp = 200, maxTemp = 230, bedMinTemp = 50, bedMaxTemp = 60,
            remainingWeight = 866.3f, usedWeight = 133.7f,
            spoolmanName = "Black", owner = "Egon", cardUids = listOf(uid)
        )
        val form = SpoolFormState(MaterialDatabase.getMaterial("PLA")!!).apply {
            remainingWeight = "866.3"
            colorHex = "000000"
            colorName = "Black"
            brand = "Elegoo"
            variant = "Plus"
            owner = "Egon"
            minTemp = "200"
            maxTemp = "230"
            bedMinTemp = "50"
            bedMaxTemp = "60"
        }
        setTestFontScale(compose, 2f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SpoolStudioTheme {
                    Box(Modifier.fillMaxSize().testTag("main-form-viewport")) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            SpoolFormCard(
                                form = form,
                                spools = listOf(spool),
                                selectedSpool = spool,
                                lastScannedCardUid = uid,
                                spoolMode = SpoolMode.UPDATE,
                                spoolmanUrl = "http://spoolman.invalid:7912",
                                currentSpoolId = "1",
                                isLoadingSpools = false,
                                availableMaterials = listOf("PLA"),
                                availableBrands = listOf("Elegoo"),
                                availableVariants = listOf("Plus"),
                                availableLocations = emptyList(),
                                showLotNumber = false,
                                showCommentField = false,
                                showEmptySpoolWeight = false,
                                materialModifierFieldEnabled = false,
                                isRemainingWeightValid = true,
                                onSpoolSelected = {},
                                onClearAllSpoolFields = {},
                                onRefreshSelectedSpool = {},
                                onRefreshSpoolmanCatalogIfStale = {}
                            )
                        }
                    }
                }
            }
        }

        assertFullyReadable("status-uid-value")
        assertFullyReadable("status-remaining-value")
        saveScreenshot("main-form-status-large-text.png")
        listOf("temperature-nozzle-min", "temperature-nozzle-max", "temperature-bed-min", "temperature-bed-max")
            .forEach(::assertFullyReadable)
        saveScreenshot("main-form-temperatures-large-text.png")
    }

    private fun assertFullyReadable(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Text layout should exist for $tag", layouts.isNotEmpty())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("main-form-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Text should remain in viewport: $tag", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        layouts.forEach { assertRenderedTextFits(it, tag, expectedFontScale = 2f) }
        assertTrue("Text should not be vertically clipped: $tag", layouts.all { it.size.height <= bounds.height + 1f })
    }

    private fun saveScreenshot(name: String) = saveRenderedWindow(compose, name)
}
