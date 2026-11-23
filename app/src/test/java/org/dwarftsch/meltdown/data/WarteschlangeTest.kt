package org.dwarftsch.meltdown.data

import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WarteschlangeTest {

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val namen = mapOf(1L to "Kurt", 2L to "Mia")
    private fun name(id: Long) = namen[id] ?: "?"

    private fun eintrag(id: Long, kind: Long, minuten: Long, intensitaet: Int = 5) =
        Entry(id, kind, name(kind), t0.plusSeconds(minuten * 60), intensitaet, null)

    @Test
    fun `neuer Eintrag bekommt negative IDs und erscheint nur auf der ersten Seite`() {
        val w = Warteschlange()
        val a = w.lege(EntryInput(1, null, 7, "Auto"), t0.plusSeconds(3600))
        val b = w.lege(EntryInput(2, null, 3, null), t0.plusSeconds(7200))
        assertEquals(-1L, a)
        assertEquals(-2L, b)

        val server = listOf(eintrag(10, 1, 0))
        val erste = w.anwenden(server, filterKind = null, ersteSeite = true, kindName = ::name)
        assertEquals(listOf(-2L, -1L, 10L), erste.map { it.id })
        assertEquals("Mia", erste.first().childName)

        val zweite = w.anwenden(server, filterKind = null, ersteSeite = false, kindName = ::name)
        assertEquals(listOf(10L), zweite.map { it.id })

        val gefiltert = w.anwenden(server, filterKind = 1L, ersteSeite = true, kindName = ::name)
        assertEquals(listOf(-1L, 10L), gefiltert.map { it.id })
    }

    @Test
    fun `Aenderung eines wartenden Eintrags fliesst ins Anlegen ein`() {
        val w = Warteschlange()
        val id = w.lege(EntryInput(1, null, 7, null), t0)
        w.aendere(id, EntryInput(2, t0, 9, "Lärm"), t0)
        assertEquals(1, w.anzahl)
        val anlegen = w.aktionen.single() as Warteaktion.Anlegen
        assertEquals(2L, anlegen.childId)
        assertEquals(9, anlegen.intensity)
        assertEquals("Lärm", anlegen.trigger)
    }

    @Test
    fun `Loeschen eines wartenden Eintrags entfernt ihn ersatzlos`() {
        val w = Warteschlange()
        val id = w.lege(EntryInput(1, null, 7, null), t0)
        w.loesche(id)
        assertTrue(w.istLeer)
    }

    @Test
    fun `mehrfache Aenderung desselben Server-Eintrags bleibt eine Aktion`() {
        val w = Warteschlange()
        w.aendere(10, EntryInput(1, t0, 4, null), t0)
        w.aendere(10, EntryInput(1, t0, 8, "x"), t0)
        assertEquals(1, w.anzahl)
        assertEquals(8, (w.aktionen.single() as Warteaktion.Aendern).intensity)
    }

    @Test
    fun `Loeschen verdraengt wartende Aenderung`() {
        val w = Warteschlange()
        w.aendere(10, EntryInput(1, t0, 4, null), t0)
        w.loesche(10)
        w.loesche(10)
        assertEquals(listOf<Warteaktion>(Warteaktion.Loeschen(10)), w.aktionen)
        val ergebnis = w.anwenden(listOf(eintrag(10, 1, 0), eintrag(11, 1, 1)), null, true, ::name)
        assertEquals(listOf(11L), ergebnis.map { it.id })
    }

    @Test
    fun `Kindwechsel nimmt Eintrag aus gefilterter Ansicht`() {
        val w = Warteschlange()
        w.aendere(10, EntryInput(2, t0, 4, null), t0)
        val gefiltert = w.anwenden(listOf(eintrag(10, 1, 0)), filterKind = 1L, ersteSeite = true, kindName = ::name)
        assertTrue(gefiltert.isEmpty())
        val alle = w.anwenden(listOf(eintrag(10, 1, 0)), filterKind = null, ersteSeite = true, kindName = ::name)
        assertEquals("Mia", alle.single().childName)
    }

    @Test
    fun `betrifftKind erkennt wartende Eintraege`() {
        val w = Warteschlange()
        w.lege(EntryInput(1, null, 7, null), t0)
        assertTrue(w.betrifftKind(1))
        assertFalse(w.betrifftKind(2))
    }

    @Test
    fun `JSON-Rundreise erhaelt alles inklusive naechster lokaler ID`() {
        val w = Warteschlange()
        w.lege(EntryInput(1, null, 7, "Auto"), t0)
        w.lege(EntryInput(2, null, 2, null), t0.plusSeconds(1))
        w.aendere(10, EntryInput(1, t0, 4, null), t0)
        w.loesche(11)
        val zurueck = Warteschlange.ausJson(w.alsJson())
        assertEquals(w.aktionen, zurueck.aktionen)
        assertEquals(-3L, zurueck.lege(EntryInput(1, null, 1, null), t0))
    }

    @Test
    fun `beschaedigte Datei vergibt keine lokale ID doppelt`() {
        val w = Warteschlange()
        w.lege(EntryInput(1, null, 7, null), t0)
        w.lege(EntryInput(1, null, 7, null), t0)
        val json = w.alsJson().apply { remove("naechste_lokale_id") }
        assertEquals(-3L, Warteschlange.ausJson(json).lege(EntryInput(1, null, 1, null), t0))
    }
}
