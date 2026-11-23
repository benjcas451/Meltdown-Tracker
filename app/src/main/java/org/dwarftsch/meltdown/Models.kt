package org.dwarftsch.meltdown

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ein benanntes Kind der Familie (`meltdown_children`). */
data class Child(
    val id: Long,
    val name: String,
    /** Anzahl der Einträge im Verlauf; ein Kind mit Einträgen lässt sich nicht löschen. */
    val entries: Int,
)

/** Ein Eintrag im Verlauf (`meltdown_entries`). */
data class Entry(
    val id: Long,
    val childId: Long,
    val childName: String,
    val createTime: Instant,
    /** Ganzzahl 1 (mild) bis 10 (maximal). */
    val intensity: Int,
    /** Optionaler Auslöser/Grund; null wenn nicht angegeben. */
    val trigger: String?,
)

/**
 * Felder eines neuen bzw. geänderten Eintrags. [createTime] null heisst
 * „jetzt“ – beim Anlegen setzt dann der Server (bzw. die lokale Datenbank)
 * den Zeitpunkt, damit eine vorgehende Handyuhr nicht als „Zukunft“
 * abgewiesen wird.
 */
data class EntryInput(
    val childId: Long,
    val createTime: Instant?,
    val intensity: Int,
    val trigger: String?,
)

/**
 * Eine Seite des Verlaufs. [naechsterOffset] bezieht sich auf die Zählung der
 * Quelle (Server bzw. SQLite), nicht auf die angezeigte Liste – die kann durch
 * wartende Offline-Änderungen länger oder kürzer sein.
 */
data class EntryPage(
    val entries: List<Entry>,
    val naechsterOffset: Int,
    val hatMehr: Boolean,
)

/** Grenzen und Prüfungen – identisch zu `inc/app.php` des Webservers. */
object Grenzen {
    const val NAME_MAX = 50
    const val TRIGGER_MAX = 200
    const val INTENSITAET_MIN = 1
    const val INTENSITAET_MAX = 10

    /** Frühester zulässiger Zeitpunkt (Server: Jahr 2000). */
    val FRUEHESTENS: Instant = Instant.parse("2000-01-01T00:00:00Z")

    /** Toleranz des Servers für Zeitpunkte „in der Zukunft“. */
    const val ZUKUNFT_TOLERANZ_SEKUNDEN = 60L

    private val steuerzeichen = Regex("[\\x00-\\x1F\\x7F]")

    /** Bereinigter Name oder Fehlermeldung. */
    fun pruefeName(roh: String): Result<String> = text(roh, "Name", NAME_MAX, optional = false)
        .map { it!! }

    /** Bereinigter Auslöser (null bei leer) oder Fehlermeldung. */
    fun pruefeAusloeser(roh: String?): Result<String?> =
        if (roh == null) Result.success(null) else text(roh, "Auslöser", TRIGGER_MAX, optional = true)

    fun pruefeIntensitaet(wert: Int): Result<Int> =
        if (wert in INTENSITAET_MIN..INTENSITAET_MAX) {
            Result.success(wert)
        } else {
            Result.failure(IllegalArgumentException("Intensität: 1 (mild) bis 10 (maximal)."))
        }

    fun pruefeZeit(zeit: Instant, jetzt: Instant = Instant.now()): Result<Instant> = when {
        zeit < FRUEHESTENS -> Result.failure(IllegalArgumentException("Zeitpunkt liegt vor dem Jahr 2000."))
        zeit > jetzt.plusSeconds(ZUKUNFT_TOLERANZ_SEKUNDEN) ->
            Result.failure(IllegalArgumentException("Zeitpunkt liegt in der Zukunft."))
        else -> Result.success(zeit)
    }

    private fun text(roh: String, feld: String, max: Int, optional: Boolean): Result<String?> {
        if (steuerzeichen.containsMatchIn(roh)) {
            return Result.failure(IllegalArgumentException("$feld: ungültige Zeichen."))
        }
        val wert = roh.trim()
        if (wert.isEmpty()) {
            return if (optional) {
                Result.success(null)
            } else {
                Result.failure(IllegalArgumentException("$feld darf nicht leer sein."))
            }
        }
        // Wie mb_strlen: Codepoints zählen, nicht UTF-16-Einheiten (Emoji).
        if (wert.codePointCount(0, wert.length) > max) {
            return Result.failure(IllegalArgumentException("$feld: höchstens $max Zeichen."))
        }
        return Result.success(wert)
    }
}

/**
 * Liest einen ISO-8601-Zeitstempel tolerant: mit `Z`, mit Offset (`+01:00`)
 * oder ohne Zone (dann als lokale Zeit des Geräts).
 */
fun parseIsoZeit(text: String): Instant {
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()
}

/**
 * RFC 3339 mit Sekunden und explizitem Offset, z. B. `2026-10-25T02:30:00+01:00`.
 *
 * Der Server lehnt eine Berliner Ortszeit ohne Offset in der doppelten Stunde
 * der Zeitumstellung als mehrdeutig ab; mit Offset ist sie eindeutig.
 */
fun rfc3339(zeit: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(zeit, zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))
