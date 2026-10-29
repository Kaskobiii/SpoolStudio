package com.spoolstudio.app.domain.models

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SharedVariantTest {
    @Test fun plusAndFinishArePreservedInBothStores() {
        for (finish in listOf("Basic", "Matte", "Silk", "Galaxy")) {
            val expected = if (finish == "Basic") "Plus" else "Plus $finish"
            val stored = spoolLinkSpoolmanFields("PLA", finish, "Plus", allowMaterialModifier = false)
            val tag = spoolLinkTagFields(stored.material, stored.variant, stored.materialModifier)
            assertEquals("PLA", stored.material)
            assertEquals(expected, stored.variant)
            assertEquals(expected, tag.variant)
            assertEquals("", stored.materialModifier)
        }
    }

    @Test fun repeatedLoadsDoNotDuplicateModifiers() {
        val once = spoolLinkSpoolmanFields("PLA+", "Plus Matte", "Plus")
        val twice = spoolLinkSpoolmanFields(once.material, once.variant, once.materialModifier)
        assertEquals("Plus Matte", once.variant)
        assertEquals(once, twice)
    }

    @Test fun legacySpoolLoadsItsOwnerAndCompleteVariant() {
        val raw = """{"id":1,"filament":{"id":2,"name":"White","material":"PLA","extra":{"variant":"\"Basic\"","material_modifier":"\"Plus\""}},"remaining_weight":892,"extra":{"owner":"\"Owner A\"","card_uids":"\"04AABBCCDDEE11,04AABBCCDDEE22\""}}"""
        val spool = FilamentSpool.fromSpoolman(Gson().fromJson(raw, SpoolmanSpool::class.java))
        assertEquals("Owner A", spool.owner)
        assertEquals("Plus", spool.variant)
        assertEquals("PLA Plus", spool.displayName)
        assertTrue(spool.needsVariantMigration)
        assertEquals(2, spool.cardUids.size)
        assertEquals(892f, spool.remainingWeight)
    }
}
