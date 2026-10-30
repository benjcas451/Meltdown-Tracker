package org.dwarftsch.meltdown.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class LocalBackupServiceTest {

    private val kinder = listOf(KindZeile(1, "Kurt"), KindZeile(2, "Mia"))
    private val eintraege = listOf(
        EintragZeile(1, 1, Instant.parse("2026-10-30T18:05:00Z"), 7, "Übergang"),
        EintragZeile(2, 2, Instant.parse("2026-10-30T19:00:00Z"), 2, null),
    )

    private fun fehlerText(json: String): String =
        runCatching { LocalBackupService.parseUndValidiere(json) }.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `Export und Import sind verlustfrei`() {
        val json = LocalBackupService.exportJson(kinder, eintraege)
        val (k, e) = LocalBackupService.parseUndValidiere(json)
        assertEquals(kinder, k)
        assertEquals(eintraege, e)
    }

    @Test
    fun `fremdes Format wird abgelehnt`() {
        assertTrue(fehlerText("""{"format": "stillzeit"}""").contains("Kein Meltdown"))
        assertTrue(fehlerText("kein json").contains("JSON"))
    }

    @Test
    fun `Eintrag mit unbekanntem Kind wird abgelehnt`() {
        val json = LocalBackupService.exportJson(kinder.take(1), eintraege)
        assertTrue(fehlerText(json).contains("unbekanntes Kind"))
    }

    @Test
    fun `doppelte Namen ohne Gross-Kleinschreibung werden abgelehnt`() {
        val json = LocalBackupService.exportJson(listOf(KindZeile(1, "Kurt"), KindZeile(2, "kurt")), emptyList())
        assertTrue(fehlerText(json).contains("Doppelte Namen"))
    }

    @Test
    fun `Intensitaet ausserhalb 1 bis 10 wird abgelehnt`() {
        val json = LocalBackupService.exportJson(kinder, listOf(eintraege[0].copy(intensity = 11)))
        assertTrue(fehlerText(json).contains("Intensität"))
    }

    @Test
    fun `zu grosse Datei wird nicht eingelesen`() {
        val gross = ByteArray(LocalBackupService.MAX_BYTES + 1)
        val fehler = runCatching { LocalBackupService.leseBegrenzt(ByteArrayInputStream(gross)) }.exceptionOrNull()
        assertTrue(fehler?.message.orEmpty().contains("16 MB"))
    }
}
