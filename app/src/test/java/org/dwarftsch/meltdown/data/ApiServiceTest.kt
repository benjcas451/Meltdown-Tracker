package org.dwarftsch.meltdown.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.dwarftsch.meltdown.EntryInput
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Prüft die Anfragen gegen das Format von `meltdown-tracker/api.php`. */
class ApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        api = ApiService(
            endpunkt = AppSettings.endpunkt(server.url("/meltdown-tracker/").toString()),
            apiKey = "geheim",
            cfToken = CloudflareServiceToken("id.access", "secret"),
        )
    }

    @After
    fun stopp() {
        api.dispose()
        server.shutdown()
    }

    private fun antworte(code: Int, json: String) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(json))
    }

    private val eintragJson = """
        {"id": 7, "child_id": 1, "child_name": "Kurt", "create_time": "2026-10-30T18:05:00Z",
         "create_time_local": "2026-10-30T19:05:00+01:00", "intensity": 8, "trigger": null}
    """.trimIndent()

    @Test
    fun `Kinder lesen mit Headern und Action`() = runTest {
        antworte(200, """{"children": [{"id": 1, "name": "Kurt", "entries": 3}]}""")
        val kinder = api.getChildren()
        assertEquals("Kurt", kinder.single().name)
        assertEquals(3, kinder.single().entries)

        val anfrage = server.takeRequest()
        assertEquals("GET", anfrage.method)
        assertEquals("/meltdown-tracker/api.php?action=children", anfrage.path)
        assertEquals("geheim", anfrage.getHeader("X-API-Key"))
        assertEquals("id.access", anfrage.getHeader("CF-Access-Client-Id"))
        assertEquals("secret", anfrage.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `Verlauf mit Filter und Seiten`() = runTest {
        antworte(200, """{"entries": [$eintragJson]}""")
        val seite = api.getEntries(childId = 1, offset = 50, limit = 50)
        assertEquals(51, seite.naechsterOffset)
        assertFalse(seite.hatMehr)
        assertNull(seite.entries.single().trigger)
        assertEquals(Instant.parse("2026-10-30T18:05:00Z"), seite.entries.single().createTime)
        assertEquals(
            "/meltdown-tracker/api.php?action=entries&child_id=1&limit=50&offset=50",
            server.takeRequest().path,
        )
    }

    @Test
    fun `Anlegen ohne Zeitpunkt laesst create_time weg`() = runTest {
        antworte(201, """{"entry": $eintragJson}""")
        api.createEntry(EntryInput(childId = 1, createTime = null, intensity = 8, trigger = null))
        val anfrage = server.takeRequest()
        assertEquals("POST", anfrage.method)
        assertEquals("/meltdown-tracker/api.php?action=entry_create", anfrage.path)
        assertTrue(anfrage.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(anfrage.body.readUtf8())
        // Der Server lehnt unbekannte Felder ab – genau diese zwei.
        assertEquals(setOf("child_id", "intensity"), body.keys().asSequence().toSet())
        assertEquals(8, body.getInt("intensity"))
    }

    @Test
    fun `Aendern schickt alle Felder, null loescht den Ausloeser`() = runTest {
        antworte(200, """{"entry": $eintragJson}""")
        val zeit = Instant.parse("2026-10-25T01:30:00Z")
        api.updateEntry(7, EntryInput(childId = 2, createTime = zeit, intensity = 3, trigger = null))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("id", "child_id", "create_time", "intensity", "trigger"), body.keys().asSequence().toSet())
        assertTrue(body.isNull("trigger"))
        assertEquals(zeit, org.dwarftsch.meltdown.parseIsoZeit(body.getString("create_time")))
        assertTrue(body.getString("create_time").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d\d:\d\d""")))
    }

    @Test
    fun `Fehlermeldung des Servers wird durchgereicht`() = runTest {
        antworte(409, """{"error": "Kind hat noch Einträge im Verlauf und wird nicht gelöscht."}""")
        try {
            api.deleteChild(1)
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertEquals(409, e.statusCode)
            assertEquals("Kind hat noch Einträge im Verlauf und wird nicht gelöscht.", e.message)
            assertNull(Netzfehler.aus(e))
        }
    }

    @Test
    fun `HTML-Fehlerseite ohne JSON ergibt verstaendliche Meldung`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("<html>No required SSL certificate was sent</html>"))
        try {
            api.getChildren()
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message!!.contains("Zertifikat"))
        }
    }

    @Test
    fun `fehlende URL meldet Konfigurationsfehler`() = runTest {
        try {
            ApiService(endpunkt = "").getChildren()
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("Keine API-URL"))
        }
    }

    @Test
    fun `Klartext ausserhalb von Loopback wird abgelehnt`() = runTest {
        try {
            ApiService(endpunkt = "http://example.org/meltdown-tracker/api.php").getChildren()
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("https://"))
        }
    }
}
