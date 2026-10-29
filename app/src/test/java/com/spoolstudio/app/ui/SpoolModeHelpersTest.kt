package com.spoolstudio.app.ui

import com.spoolstudio.app.domain.models.FilamentSpool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolModeHelpersTest {
    @Test
    fun duplicatePetgPreservesMaterialModifierThroughFormReloadWithoutReusingUid() {
        val source = FilamentSpool(
            id = 7, material = "PETG", materialModifier = "2.0", variant = "Basic",
            brand = "Bambu Lab", colorHex = "FFFFFF", minTemp = 240, maxTemp = 260,
            bedMinTemp = 70, bedMaxTemp = 80, spoolmanName = "White", cardUids = listOf("AABBCCDD")
        )
        val data = buildSpoolModeSourceData(source, null)!!
        val reloaded = FilamentSpool.fromOpenSpool(data)
        assertEquals("PETG", reloaded.material)
        assertEquals("2.0", reloaded.variant)
        assertEquals("", reloaded.materialModifier)
        assertNull(data.cardUid)
        assertNull(data.spoolId)
    }

    @Test
    fun duplicateFromReadDataDoesNotMoveOriginalUid() {
        val read = com.spoolstudio.app.domain.models.OpenSpoolData(
            type = "PETG", colorHex = null, brand = "Generic", minTemp = "240", maxTemp = "260",
            cardUid = "AABBCCDD", formMaterialModifier = "2.0"
        )
        val result = buildSpoolModeSourceData(null, read)!!
        assertNull(result.cardUid)
        assertEquals("2.0", FilamentSpool.fromOpenSpool(result).variant)
    }

    @Test
    fun buildSpoolModeSourceDataKeepsSelectedSpoolFieldsForDuplicateForm() {
        val source = FilamentSpool(
            id = 42,
            material = "PLA",
            variant = "Plus Silk",
            brand = "GST 3D",
            colorHex = "00B1E7",
            minTemp = 200,
            maxTemp = 230,
            bedMinTemp = 50,
            bedMaxTemp = 70,
            remainingWeight = 60f,
            spoolmanName = "Cyan Blue",
            lotNr = "LOT-1"
        )

        val result = buildSpoolModeSourceData(source, readData = null)

        assertEquals("PLA", result?.type)
        assertEquals("Plus Silk", result?.subtype)
        assertEquals("GST 3D", result?.brand)
        assertNull(result?.colorHex)
        assertNull(result?.spoolId)
        assertNull(result?.lotNr)
    }
}
