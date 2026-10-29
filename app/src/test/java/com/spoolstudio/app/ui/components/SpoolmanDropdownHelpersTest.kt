package com.spoolstudio.app.ui.components

import com.spoolstudio.app.domain.models.FilamentSpool
import org.junit.Assert.assertEquals
import org.junit.Test

class SpoolmanDropdownHelpersTest {
    @Test
    fun spoolmanDropdownLabelUsesCompactNameAndMaterial() {
        val spool = testSpool(
            id = 12,
            brand = "Generic",
            spoolmanName = "White",
            material = "PLA"
        )

        assertEquals("White PLA", spoolmanDropdownLabel(spool))
    }

    @Test
    fun compactLabelKeepsVariantWithoutRepeatingMaterial() {
        val spool = testSpool(12, "Elegoo", "Black", "PLA").copy(variant = "Plus")
        assertEquals("Black PLA Plus", spoolmanDropdownLabel(spool))
        assertEquals("Black PLA Plus", spoolmanDropdownLabel(spool.copy(spoolmanName = "Black PLA Plus")))
        assertEquals("Black PLA+", spoolmanDropdownLabel(spool.copy(spoolmanName = "Black PLA+")))
        assertEquals("Planet Black PLA", spoolmanDropdownLabel(spool.copy(spoolmanName = "Planet Black", variant = "Basic")))
    }

    @Test
    fun detailsIdentifyPhysicalSpoolsAndHandleUnknownWeight() {
        val spool = testSpool(12, "Elegoo", "Black", "PLA")
        assertEquals("ID #12 · 866.3 g remaining", spoolmanDropdownDetails(spool.copy(remainingWeight = 866.34f)))
        assertEquals("ID #12 · 27 g remaining", spoolmanDropdownDetails(spool.copy(remainingWeight = 27f)))
        assertEquals("ID #12 · Weight unknown", spoolmanDropdownDetails(spool.copy(remainingWeight = null)))
        assertEquals("ID #12 · Weight unknown", spoolmanDropdownDetails(spool.copy(remainingWeight = Float.NaN)))
    }

    @Test
    fun filterSpoolmanDropdownFilamentsMatchesCommonFields() {
        val whitePla = testSpool(
            id = 1,
            brand = "Generic",
            spoolmanName = "White",
            material = "PLA",
            location = "AMS 1",
            lotNr = "LOT-123"
        )
        val blackPetg = testSpool(
            id = 2,
            brand = "GST 3D",
            spoolmanName = "Black",
            material = "PETG",
            location = "Shelf B",
            lotNr = "LOT-999"
        )
        val spools = listOf(whitePla, blackPetg)

        assertEquals(listOf(whitePla), filterSpoolmanDropdownFilaments(spools, "ams"))
        assertEquals(listOf(blackPetg), filterSpoolmanDropdownFilaments(spools, "gst"))
        assertEquals(listOf(whitePla), filterSpoolmanDropdownFilaments(spools, "LOT-123"))
        assertEquals(spools, filterSpoolmanDropdownFilaments(spools, ""))
    }

    private fun testSpool(
        id: Int,
        brand: String,
        spoolmanName: String,
        material: String,
        location: String? = null,
        lotNr: String? = null
    ): FilamentSpool {
        return FilamentSpool(
            id = id,
            material = material,
            variant = "Basic",
            brand = brand,
            colorHex = "FFFFFF",
            minTemp = 190,
            maxTemp = 220,
            bedMinTemp = 40,
            bedMaxTemp = 65,
            location = location,
            lotNr = lotNr,
            spoolmanName = spoolmanName
        )
    }
}
