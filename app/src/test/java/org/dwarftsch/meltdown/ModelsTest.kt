package org.dwarftsch.meltdown

import org.dwarftsch.meltdown.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ModelsTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `Name wird getrimmt und begrenzt wie auf dem Server`() {
        assertEquals("Kurt", Grenzen.pruefeName("  Kurt ").getOrThrow())
        assertTrue(Grenzen.pruefeName("   ").isFailure)
        assertTrue(Grenzen.pruefeName("a".repeat(51)).isFailure)
        assertEquals("a".repeat(50), Grenzen.pruefeName("a".repeat(50)).getOrThrow())
        assertTrue(Grenzen.pruefeName("Zeile\nzwei").isFailure)
    }

    @Test
    fun `Laenge zaehlt Codepoints wie mb_strlen`() {
        // 50 Emoji sind 100 UTF-16-Einheiten, für den Server aber 50 Zeichen.
        val emoji = "😀".repeat(50)
        assertEquals(emoji, Grenzen.pruefeName(emoji).getOrThrow())
        assertTrue(Grenzen.pruefeName(emoji + "😀").isFailure)
    }

    @Test
    fun `leerer Ausloeser wird zu null`() {
        assertNull(Grenzen.pruefeAusloeser("   ").getOrThrow())
        assertNull(Grenzen.pruefeAusloeser(null).getOrThrow())
        assertEquals("Übergang", Grenzen.pruefeAusloeser(" Übergang ").getOrThrow())
        assertTrue(Grenzen.pruefeAusloeser("x".repeat(201)).isFailure)
    }

    @Test
    fun `Intensitaet nur 1 bis 10`() {
        assertTrue(Grenzen.pruefeIntensitaet(0).isFailure)
        assertTrue(Grenzen.pruefeIntensitaet(11).isFailure)
        (1..10).forEach { assertEquals(it, Grenzen.pruefeIntensitaet(it).getOrThrow()) }
    }

    @Test
    fun `Zeitpunkt in der Zukunft und vor 2000 wird abgelehnt`() {
        val jetzt = Instant.parse("2026-10-30T12:00:00Z")
        assertTrue(Grenzen.pruefeZeit(jetzt.plusSeconds(61), jetzt).isFailure)
        assertTrue(Grenzen.pruefeZeit(jetzt.plusSeconds(60), jetzt).isSuccess)
        assertTrue(Grenzen.pruefeZeit(Instant.parse("1999-12-31T23:59:59Z"), jetzt).isFailure)
    }

    @Test
    fun `rfc3339 ist in der doppelten Stunde der Zeitumstellung eindeutig`() {
        // 25.10.2026: 03:00 MESZ -> 02:00 MEZ. 02:30 gibt es zweimal.
        val ersteRunde = Instant.parse("2026-10-25T00:30:00Z")
        val zweiteRunde = Instant.parse("2026-10-25T01:30:00Z")
        assertEquals("2026-10-25T02:30:00+02:00", rfc3339(ersteRunde, berlin))
        assertEquals("2026-10-25T02:30:00+01:00", rfc3339(zweiteRunde, berlin))
        assertEquals(zweiteRunde, parseIsoZeit(rfc3339(zweiteRunde, berlin)))
    }

    @Test
    fun `parseIsoZeit liest Z und Offset`() {
        assertEquals(Instant.parse("2026-10-30T18:05:00Z"), parseIsoZeit("2026-10-30T18:05:00Z"))
        assertEquals(Instant.parse("2026-10-30T18:05:00Z"), parseIsoZeit("2026-10-30T19:05:00+01:00"))
    }

    @Test
    fun `Endpunkt ergaenzt api_php`() {
        assertEquals("https://h/meltdown-tracker/api.php", AppSettings.endpunkt("https://h/meltdown-tracker/"))
        assertEquals("https://h/meltdown-tracker/api.php", AppSettings.endpunkt(" https://h/meltdown-tracker "))
        assertEquals("https://h/meltdown-tracker/api.php", AppSettings.endpunkt("https://h/meltdown-tracker/api.php"))
        assertEquals("https://h/meltdown-tracker/api.php", AppSettings.endpunkt("https://h/meltdown-tracker/api.php?action=children"))
        assertEquals("", AppSettings.endpunkt("  "))
    }
}
