package com.spoolstudio.app.ui.screens

import com.spoolstudio.app.data.local.MaterialDatabase
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.ui.SpoolMode
import com.spoolstudio.app.ui.components.filterSpoolmanDropdownFilaments
import com.spoolstudio.app.ui.remainingWeightForUpdate
import com.spoolstudio.app.ui.hasFilamentSaveChanges
import com.spoolstudio.app.ui.buildSpoolModeSourceData
import org.junit.Assert.*
import org.junit.Test

class SharedSpoolFormTest {
    private fun spool(owner: String = "Owner A") = FilamentSpool(
        id = 1, material = "PLA", variant = "Plus", brand = "Example",
        colorHex = "FFFFFF", minTemp = 210, maxTemp = 230, bedMinTemp = 50, bedMaxTemp = 60,
        remainingWeight = 892f, spoolmanName = "White", filamentId = 2,
        owner = owner, cardUids = listOf("04AABBCCDDEE11", "04AABBCCDDEE22")
    )
    private fun form() = SpoolFormState(MaterialDatabase.getMaterial("PLA")!!)

    @Test fun ownerLoadsSavesAndResetsOnNewSpool() {
        val form = form()
        val spool = spool()
        form.applySpoolSource(spool, SpoolMode.UPDATE, emptyList())
        assertEquals("Owner A", form.owner)
        assertEquals("Owner A", form.buildSaveRequest(SpoolMode.UPDATE, spool).owner)
        form.owner = "Owner B"
        assertTrue(hasSpoolmanSaveChanges(form.buildSaveRequest(SpoolMode.UPDATE, spool), spool))
        form.resetForNewSpool()
        assertEquals("", form.owner)
    }

    @Test fun duplicateKeepsOwnerButCannotReuseTheOldSpoolsTag() {
        val form = form()
        form.applySpoolSource(spool(), SpoolMode.DUPLICATE, emptyList())
        assertEquals("Owner A", form.owner)
        assertNull(form.pendingCardUid)
        assertNull(form.buildSaveRequest(SpoolMode.DUPLICATE, null).existingSpoolId)
        val reloaded = FilamentSpool.fromOpenSpool(buildSpoolModeSourceData(spool(), null)!!)
        assertEquals("Owner A", reloaded.owner)
        assertTrue(reloaded.cardUids.isEmpty())
    }

    @Test fun ownerSearchFindsOnlyThatPersonsSpools() {
        val a = spool("Owner A")
        val b = spool("Owner B").copy(id = 2)
        assertEquals(listOf(a), filterSpoolmanDropdownFilaments(listOf(a, b), "owner a"))
    }

    @Test fun unchangedWeightIsOmittedWhileExplicitCorrectionsIncludingZeroAreKept() {
        assertNull(remainingWeightForUpdate(892f, 892f))
        assertNull(remainingWeightForUpdate(null, 892f))
        assertEquals(850f, remainingWeightForUpdate(850f, 892f))
        assertEquals(0f, remainingWeightForUpdate(0f, 892f))
    }

    @Test fun changingOnlyOwnerDoesNotRewriteSharedFilamentMetadata() {
        val spool = spool()
        val form = form()
        form.applySpoolSource(spool, SpoolMode.UPDATE, emptyList())
        form.owner = "Owner B"
        assertFalse(hasFilamentSaveChanges(form.buildSaveRequest(SpoolMode.UPDATE, spool), spool))
        form.variant = "Silk"
        assertTrue(hasFilamentSaveChanges(form.buildSaveRequest(SpoolMode.UPDATE, spool), spool))
    }

    @Test fun ownerEditRetainsMissingProductFieldsAndRoundedTare() {
        val spool = spool().copy(minTemp = null, maxTemp = null, bedMinTemp = null,
            bedMaxTemp = null, spoolmanName = null, emptySpoolWeight = 180.123f)
        val form = form()
        form.applySpoolSource(spool, SpoolMode.UPDATE, emptyList())
        form.owner = "Owner B"
        assertFalse(hasFilamentSaveChanges(form.buildSaveRequest(SpoolMode.UPDATE, spool), spool))
        form.emptySpoolWeight = "185.00"
        assertTrue(hasFilamentSaveChanges(form.buildSaveRequest(SpoolMode.UPDATE, spool), spool))
    }
}
