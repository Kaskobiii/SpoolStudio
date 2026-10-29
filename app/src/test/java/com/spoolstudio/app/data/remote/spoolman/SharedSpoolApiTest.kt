package com.spoolstudio.app.data.remote.spoolman

import com.google.gson.JsonParser
import com.google.gson.Gson
import com.spoolstudio.app.data.local.MaterialDatabase
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.domain.models.SpoolmanSpool
import com.spoolstudio.app.ui.SpoolmanSaveRepository
import com.spoolstudio.app.ui.SpoolmanSaveInput
import com.spoolstudio.app.ui.SpoolMode
import com.spoolstudio.app.ui.screens.SpoolFormState
import com.spoolstudio.app.ui.screens.applySpoolSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SharedSpoolApiTest {
    private lateinit var server: MockWebServer
    private lateinit var service: SpoolmanService
    private val uid = "04AABBCCDDEE11"
    private val second = "04AABBCCDDEE22"
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        service = SpoolmanService(server.url("/").toString())
    }
    @After fun cleanup() { server.shutdown() }
    private fun reply(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    private fun spool(legacy: String = "$uid,$second", native: Boolean = true) = """
        {"id":1,"filament":{"id":2,"name":"White","material":"PLA","extra":{"variant":"\"Plus\""}},
         "remaining_weight":892,"extra":{"owner":"\"Owner A\"","card_uids":"\"$legacy\"","custom":"\"keep\""},
         "tags":${if (native) "[{\"uid\":\"$uid\"}]" else "[]"}}
    """.trimIndent()

    @Test fun changingOwnerPreservesBothTagsAndDoesNotSendWeight() = runBlocking {
        reply(spool())
        reply("""[{"key":"owner","name":"Owner","field_type":"text"}]""")
        reply(spool())
        service.updateSpool(1, owner = "Owner B")
        server.takeRequest(); server.takeRequest()
        val body = JsonParser().parse(server.takeRequest().body.readUtf8()).asJsonObject
        assertFalse(body.has("remaining_weight"))
        val extra = body.getAsJsonObject("extra")
        assertEquals("\"Owner B\"", extra.get("owner").asString)
        assertEquals("\"$uid,$second\"", extra.get("card_uids").asString)
        assertEquals("\"keep\"", extra.get("custom").asString)
    }

    @Test fun ownerCanBeClearedWithoutDroppingOtherExtraFields() = runBlocking {
        reply(spool()); reply(spool())
        service.updateSpool(1, owner = "")
        server.takeRequest()
        val extra = JsonParser().parse(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("extra")
        assertEquals("\"\"", extra.get("owner").asString)
        assertEquals("\"$uid,$second\"", extra.get("card_uids").asString)
    }

    @Test fun successfulNativeWriteWithoutLegacyMirrorIsNotReportedAsPaired() = runBlocking {
        reply("""{"version":"0.27.0"}"""); reply(spool(legacy = ""))
        try { service.verifyCardUidLink(1, uid); fail("Expected incomplete pairing") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Card UIDs")) }
    }

    @Test fun legacyMirrorWithoutNativeTagIsNotReportedAsFullyPaired() = runBlocking {
        reply("""{"version":"0.27.0"}"""); reply(spool(native = false))
        try { service.verifyCardUidLink(1, uid); fail("Expected incomplete pairing") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("native tags")) }
    }

    @Test fun twoTagSpoolPassesVerificationWithoutWrites() = runBlocking {
        reply("""{"version":"0.27.0"}"""); reply(spool())
        service.verifyCardUidLink(1, "04:AA:BB:CC:DD:EE:11")
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun linkingOneTagPreservesTheOtherTagAndOwner() = runBlocking {
        reply("""{"version":"0.27.0"}"""); reply("{}")
        reply("""[{"key":"card_uids","name":"Card UIDs","field_type":"text"}]""")
        reply("""[{"key":"variant","name":"Variant","field_type":"text"}]""")
        reply(spool(legacy = second))
        reply("[${spool(legacy = second)}]")
        reply(spool()); reply(spool())
        service.assignAndVerifyCardUidToSpool(1, uid)
        repeat(6) { server.takeRequest() }
        val patch = server.takeRequest()
        val body = JsonParser().parse(patch.body.readUtf8()).asJsonObject
        assertEquals("PATCH", patch.method)
        assertFalse(body.has("remaining_weight"))
        val extra = body.getAsJsonObject("extra")
        assertTrue(extra.get("card_uids").asString.contains(second))
        assertTrue(extra.get("card_uids").asString.contains(uid))
        assertEquals("\"Owner A\"", extra.get("owner").asString)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun olderSpoolmanNeedsOnlyTheCompatibilityMirror() = runBlocking {
        reply("""{"version":"0.26.1"}"""); reply(spool(native = false))
        service.verifyCardUidLink(1, uid)
    }

    @Test fun filamentPatchStoresFullVariantAndPreservesOtherMetadata() = runBlocking {
        val filament = """{"id":2,"name":"White","material":"PLA","extra":{"variant":"\"Basic\"","material_modifier":"\"Plus\"","custom":"\"keep\""}}"""
        reply(filament); reply(filament)
        service.updateFilament(2, "White", "PLA", "Silk", "Plus", vendorId = 1, colorHex = "FFFFFF", nozzleTemp = 210, bedTemp = 60)
        server.takeRequest()
        val extra = JsonParser().parse(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("extra")
        assertEquals("\"Plus Silk\"", extra.get("variant").asString)
        assertFalse(extra.has("material_modifier"))
        assertEquals("\"keep\"", extra.get("custom").asString)
    }

    @Test fun failedFilamentReadNeverOverwritesUnknownMetadata() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            service.updateFilament(2, "White", "PLA", "Plus", vendorId = 1, colorHex = "FFFFFF", nozzleTemp = 210, bedTemp = 60)
            fail("Expected read failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Load filament"))
        }
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun ownerOnlySaveDoesNotTouchVendorFilamentOrConsumedWeight() = runBlocking {
        val selected = FilamentSpool.fromSpoolman(Gson().fromJson(spool(), SpoolmanSpool::class.java))
            .copy(cardUids = emptyList(), nativeTagUids = emptyList())
        val form = SpoolFormState(MaterialDatabase.getMaterial("PLA")!!)
        form.applySpoolSource(selected, SpoolMode.UPDATE, emptyList())
        form.owner = "Owner B"
        val current = spool().replace("892", "891")
        val updated = current.replace("Owner A", "Owner B")
        reply("""{"version":"0.27.0"}""")
        reply("""[{"key":"card_uids","field_type":"text"}]""")
        reply("""[{"key":"variant","field_type":"text"}]""")
        reply(current)
        reply("""[{"key":"owner","field_type":"text"}]""")
        reply(updated); reply(updated)
        val result = SpoolmanSaveRepository { service }.save(SpoolmanSaveInput(
            server.url("/").toString(), form.buildSaveRequest(SpoolMode.UPDATE, selected),
            SpoolMode.UPDATE, selected, null, "1"
        ))
        assertEquals("Owner B", result.finalSpool.owner)
        assertEquals(891f, result.finalSpool.remainingWeight)
        repeat(server.requestCount) {
            val request = server.takeRequest()
            assertFalse(request.path!!.contains("/vendor"))
            assertFalse(request.path!!.contains("/filament/"))
            if (request.method == "PATCH") {
                val body = JsonParser().parse(request.body.readUtf8()).asJsonObject
                assertFalse(body.has("remaining_weight"))
                assertTrue(body.getAsJsonObject("extra").get("card_uids").asString.contains(second))
            }
        }
    }
}
