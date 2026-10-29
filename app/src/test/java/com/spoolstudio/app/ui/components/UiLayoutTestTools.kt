package com.spoolstudio.app.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog

/** Dialogs create a separate Compose view; its density comes from Android resources. */
internal fun setTestFontScale(rule: AndroidComposeTestRule<*, *>, scale: Float) {
    rule.runOnUiThread {
        RuntimeEnvironment.setFontScale(scale)
        val resources = rule.activity.resources
        val configuration = Configuration(resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}

internal fun assertRenderedTextFits(layout: TextLayoutResult, label: String, expectedFontScale: Float) {
    assertEquals("The actual text density should use the requested font size: $label",
        expectedFontScale, layout.layoutInput.density.fontScale, 0.01f)
    assertFalse("Text must not omit lines: $label", layout.multiParagraph.didExceedMaxLines)
    // Compose 1.7 can retain a wide paragraph after reporting a tight Text size. Its
    // didOverflowWidth then compares allocated paragraph width, even when all glyphs fit.
    // Inspect actual rendered line extents instead, and retain vertical/ellipsis checks.
    repeat(layout.lineCount) { line ->
        assertFalse("Text must not be ellipsized: $label", layout.isLineEllipsized(line))
        assertTrue("Text left edge must fit: $label", layout.getLineLeft(line) >= -1f)
        assertTrue("Text right edge must fit: $label: ${layout.getLineRight(line)} > ${layout.size.width}",
            layout.getLineRight(line) <= layout.size.width + 1f)
        assertTrue("Text bottom edge must fit: $label", layout.getLineBottom(line) <= layout.size.height + 1f)
    }
}

/** PixelCopy is asynchronous in the device API and unsupported by this Robolectric setup. */
internal fun saveRenderedWindow(rule: AndroidComposeTestRule<*, *>, name: String) {
    val directory = System.getenv("SPOOLSTUDIO_SCREENSHOT_DIR") ?: return
    rule.waitForIdle()
    rule.runOnUiThread {
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        val view = (dialog?.window ?: rule.activity.window).decorView
        assertTrue("The rendered window must have a size", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory).mkdirs()
        File(directory, name).outputStream().use {
            assertTrue("The rendered image should encode", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
