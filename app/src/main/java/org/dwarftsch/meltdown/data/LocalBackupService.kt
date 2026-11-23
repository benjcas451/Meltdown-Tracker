package org.dwarftsch.meltdown.data

import org.dwarftsch.meltdown.Grenzen
import org.dwarftsch.meltdown.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JSON-Backup des lokalen Modus (Kinder + Einträge). Unabhängig vom
 * Android-Auto-Backup: die Datei lässt sich z. B. in Google Drive ablegen und
 * auf einem anderen Gerät wieder einspielen.
 *
 * Format:
 * ```
 * {"format": "meltdown-tracker-backup", "version": 1, "exportiert": "<ISO>",
 *  "kinder": [{"id": 1, "name": "…"}],
 *  "eintraege": [{"id": 1, "child_id": 1, "create_time": "<ISO UTC>",
 *                 "intensity": 7, "trigger": "…" | null}]}
 * ```
 */
object LocalBackupService {

    const val FORMAT = "meltdown-tracker-backup"
    const val VERSION = 1

    /** Obergrenze beim Import – alles darüber wird abgelehnt statt eingelesen. */
    const val MAX_BYTES = 16 * 1024 * 1024

    fun dateiname(jetzt: LocalDateTime = LocalDateTime.now()): String =
        "meltdown-backup-${jetzt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}.json"

    fun exportJson(kinder: List<KindZeile>, eintraege: List<EintragZeile>, jetzt: Instant = Instant.now()): String {
        val k = JSONArray()
        kinder.forEach { k.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val e = JSONArray()
        eintraege.forEach {
            e.put(
                JSONObject()
                    .put("id", it.id)
                    .put("child_id", it.childId)
                    .put("create_time", it.createTime.toString())
                    .put("intensity", it.intensity)
                    .put("trigger", it.trigger ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportiert", jetzt.toString())
            .put("kinder", k)
            .put("eintraege", e)
            .toString(2)
    }

    /** Liest höchstens [MAX_BYTES]; größere Dateien werden abgelehnt. */
    fun leseBegrenzt(eingabe: InputStream): String {
        val puffer = ByteArrayOutputStream()
        val block = ByteArray(64 * 1024)
        var gesamt = 0
        while (true) {
            val n = eingabe.read(block)
            if (n < 0) break
            gesamt += n
            if (gesamt > MAX_BYTES) throw IllegalArgumentException("Backup ist größer als 16 MB.")
            puffer.write(block, 0, n)
        }
        return puffer.toString(Charsets.UTF_8.name())
    }

    /**
     * Prüft das Backup vollständig, bevor irgendetwas ersetzt wird: Format,
     * eindeutige IDs und Namen, Verweise auf vorhandene Kinder, Wertebereiche.
     */
    fun parseUndValidiere(text: String): Pair<List<KindZeile>, List<EintragZeile>> {
        val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Keine gültige JSON-Datei.") }
        if (json.optString("format") != FORMAT) throw IllegalArgumentException("Kein Meltdown-Tracker-Backup.")
        if (json.optInt("version", -1) != VERSION) throw IllegalArgumentException("Unbekannte Backup-Version.")

        val kinderJson = json.optJSONArray("kinder") ?: throw IllegalArgumentException("Backup enthält keine Kinder-Liste.")
        val kinder = (0 until kinderJson.length()).map { i ->
            val o = kinderJson.optJSONObject(i) ?: throw IllegalArgumentException("Kind ${i + 1}: ungültig.")
            val id = o.optLong("id", -1L).takeIf { it > 0 } ?: throw IllegalArgumentException("Kind ${i + 1}: ungültige ID.")
            val name = Grenzen.pruefeName(o.optString("name")).getOrElse {
                throw IllegalArgumentException("Kind ${i + 1}: ${it.message}")
            }
            KindZeile(id, name)
        }
        if (kinder.map { it.id }.toSet().size != kinder.size) throw IllegalArgumentException("Doppelte Kind-IDs im Backup.")
        if (kinder.map { it.name.lowercase() }.toSet().size != kinder.size) {
            throw IllegalArgumentException("Doppelte Namen im Backup.")
        }
        val kindIds = kinder.map { it.id }.toSet()

        val eintraegeJson = json.optJSONArray("eintraege") ?: JSONArray()
        val eintraege = (0 until eintraegeJson.length()).map { i ->
            val o = eintraegeJson.optJSONObject(i) ?: throw IllegalArgumentException("Eintrag ${i + 1}: ungültig.")
            fun fehler(text: String): Nothing = throw IllegalArgumentException("Eintrag ${i + 1}: $text")
            val id = o.optLong("id", -1L).takeIf { it > 0 } ?: fehler("ungültige ID.")
            val kind = o.optLong("child_id", -1L).takeIf { it in kindIds } ?: fehler("unbekanntes Kind.")
            val zeit = runCatching { parseIsoZeit(o.getString("create_time")) }.getOrElse { fehler("ungültiger Zeitpunkt.") }
            if (zeit < Grenzen.FRUEHESTENS) fehler("Zeitpunkt liegt vor dem Jahr 2000.")
            val intensitaet = o.optInt("intensity", -1)
            Grenzen.pruefeIntensitaet(intensitaet).getOrElse { fehler(it.message.orEmpty()) }
            val ausloeser = Grenzen.pruefeAusloeser(if (o.isNull("trigger")) null else o.optString("trigger"))
                .getOrElse { fehler(it.message.orEmpty()) }
            EintragZeile(id, kind, zeit, intensitaet, ausloeser)
        }
        if (eintraege.map { it.id }.toSet().size != eintraege.size) throw IllegalArgumentException("Doppelte Eintrags-IDs im Backup.")
        return kinder to eintraege
    }
}
