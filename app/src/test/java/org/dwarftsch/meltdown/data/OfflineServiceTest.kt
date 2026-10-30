package org.dwarftsch.meltdown.data

import kotlinx.coroutines.test.runTest
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.EntryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant

class OfflineServiceTest {

    @get:Rule
    val ordner = TemporaryFolder()

    /** Server-Attrappe: hält Einträge im Speicher, [fehler] simuliert das Netz. */
    private class Attrappe : MeltdownService {
        var fehler: Throwable? = null
        val kinder = mutableListOf(Child(1, "Kurt", 0))
        val eintraege = mutableListOf<Entry>()
        val aufrufe = mutableListOf<String>()
        private var naechsteId = 100L

        private fun pruefe(aufruf: String) {
            fehler?.let { throw it }
            aufrufe += aufruf
        }

        override suspend fun getChildren(): List<Child> { pruefe("children"); return kinder.toList() }

        override suspend fun getEntries(childId: Long?, offset: Int, limit: Int): EntryPage {
            pruefe("entries")
            val liste = eintraege.filter { childId == null || it.childId == childId }
                .sortedByDescending { it.createTime }.drop(offset).take(limit)
            return EntryPage(liste, offset + liste.size, liste.size >= limit)
        }

        override suspend fun createChild(name: String): Child { pruefe("child_create"); return Child(2, name, 0) }
        override suspend fun renameChild(id: Long, name: String): Child { pruefe("child_rename"); return Child(id, name, 0) }
        override suspend fun deleteChild(id: Long) { pruefe("child_delete") }

        override suspend fun createEntry(neu: EntryInput): Entry {
            pruefe("entry_create")
            return Entry(naechsteId++, neu.childId, "Kurt", neu.createTime ?: Instant.now(), neu.intensity, neu.trigger)
                .also { eintraege += it }
        }

        override suspend fun updateEntry(id: Long, neu: EntryInput): Entry {
            pruefe("entry_update")
            val index = eintraege.indexOfFirst { it.id == id }
            if (index < 0) throw ApiException("Eintrag nicht gefunden", 404)
            eintraege[index] = eintraege[index].copy(intensity = neu.intensity, trigger = neu.trigger)
            return eintraege[index]
        }

        override suspend fun deleteEntry(id: Long) {
            pruefe("entry_delete")
            if (!eintraege.removeIf { it.id == id }) throw ApiException("Eintrag nicht gefunden", 404)
        }

        override fun dispose() = Unit
    }

    private fun dienst(innen: Attrappe) = OfflineService(innen, OfflineSpeicher(ordner.root, "mtls|https://h/api.php"))

    @Test
    fun `nie gesendeter Eintrag wartet und geht spaeter raus`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        offline.getChildren() // Namen für Platzhalter zwischenspeichern

        innen.fehler = ConnectException("weg")
        val platzhalter = offline.createEntry(EntryInput(1, null, 6, "Laden"))
        assertTrue(platzhalter.id < 0)
        assertEquals("Kurt", platzhalter.childName)
        assertEquals(1, offline.zustand.value.ausstehend)

        innen.fehler = null
        assertTrue(offline.nachholen().isEmpty())
        assertEquals(0, offline.zustand.value.ausstehend)
        assertEquals(1, innen.eintraege.size)
        // Erfassungszeitpunkt bleibt erhalten, nicht die Zeit des Nachholens.
        assertEquals(platzhalter.createTime, innen.eintraege.single().createTime)
    }

    @Test
    fun `mehrdeutiger Fehler landet nicht in der Warteschlange`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        innen.fehler = SocketTimeoutException("zu langsam")
        try {
            offline.createEntry(EntryInput(1, null, 6, null))
            fail("Exception erwartet")
        } catch (_: SocketTimeoutException) {
        }
        assertEquals(0, offline.zustand.value.ausstehend)
    }

    @Test
    fun `offline wird der zwischengespeicherte Stand samt Warteschlange gezeigt`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        offline.createEntry(EntryInput(1, Instant.parse("2026-10-30T08:00:00Z"), 4, null))
        offline.getChildren()
        offline.getEntries(null, 0, 50)

        innen.fehler = ConnectException("weg")
        offline.createEntry(EntryInput(1, Instant.parse("2026-10-30T09:00:00Z"), 9, null))
        val seite = offline.getEntries(null, 0, 50)
        assertEquals(listOf(9, 4), seite.entries.map { it.intensity })
        assertTrue(offline.zustand.value.grund != null)
    }

    @Test
    fun `solange etwas wartet, gehen neue Schreibzugriffe hinten dran`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        innen.fehler = ConnectException("weg")
        offline.createEntry(EntryInput(1, null, 2, null))
        innen.fehler = null
        // Netz wieder da, aber Warteschlange noch nicht abgearbeitet:
        offline.createEntry(EntryInput(1, null, 3, null))
        assertEquals(2, offline.zustand.value.ausstehend)
        assertTrue("entry_create" !in innen.aufrufe)
        offline.nachholen()
        assertEquals(listOf(2, 3), innen.eintraege.map { it.intensity })
    }

    @Test
    fun `vom Server abgelehnte Aktion wird verworfen und gemeldet`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        innen.fehler = ConnectException("weg")
        offline.deleteEntry(42)
        innen.fehler = null
        val verworfen = offline.nachholen()
        assertEquals(1, verworfen.size)
        assertTrue(verworfen.single().contains("404"))
        assertEquals(0, offline.zustand.value.ausstehend)
    }

    @Test
    fun `Kinder verwalten verlangt Verbindung`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        innen.fehler = ConnectException("weg")
        try {
            offline.createChild("Mia")
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("nur online"))
        }
    }

    @Test
    fun `Kind mit wartenden Eintraegen wird nicht geloescht`() = runTest {
        val innen = Attrappe()
        val offline = dienst(innen)
        innen.fehler = ConnectException("weg")
        offline.createEntry(EntryInput(1, null, 5, null))
        innen.fehler = null
        try {
            offline.deleteChild(1)
            fail("Exception erwartet")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("warten noch"))
        }
        assertTrue("child_delete" !in innen.aufrufe)
    }

    @Test
    fun `Warteschlange ueberlebt einen Neustart`() = runTest {
        val innen = Attrappe()
        innen.fehler = ConnectException("weg")
        dienst(innen).createEntry(EntryInput(1, null, 7, null))
        val neu = dienst(innen)
        assertEquals(1, neu.zustand.value.ausstehend)
    }
}
