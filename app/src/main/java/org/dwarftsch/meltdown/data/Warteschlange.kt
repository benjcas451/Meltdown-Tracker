package org.dwarftsch.meltdown.data

import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Ein Schreibzugriff auf Einträge, der offline erfasst wurde und noch zum
 * Server muss. Kinder verwalten geht bewusst nur online: ein offline
 * angelegtes Kind bräuchte eine lokale ID, auf die sich wartende Einträge
 * beziehen – und ein Namenskonflikt fiele erst beim Nachholen auf.
 *
 * Änderungen und Löschungen beziehen sich immer auf eine **Server-ID**.
 * Betreffen sie einen Eintrag, der selbst noch wartet, werden sie direkt in
 * dessen [Anlegen] eingearbeitet bzw. löschen sie ganz. Dadurch kann beim
 * Abarbeiten keine unbekannte ID auftauchen.
 */
sealed interface Warteaktion {

    data class Anlegen(
        /** Negative Kennung, unter der der Eintrag bis zum Hochladen erscheint. */
        val lokaleId: Long,
        val childId: Long,
        /** Zeitpunkt der Erfassung, nicht des Hochladens. */
        val createTime: Instant,
        val intensity: Int,
        val trigger: String?,
    ) : Warteaktion

    /** Neuer Gesamtstand eines bereits hochgeladenen Eintrags. */
    data class Aendern(
        val id: Long,
        val childId: Long,
        val createTime: Instant,
        val intensity: Int,
        val trigger: String?,
    ) : Warteaktion

    data class Loeschen(val id: Long) : Warteaktion
}

/** Die geordnete Liste der offenen Schreibzugriffe eines Zugangs. */
class Warteschlange private constructor(
    private val liste: MutableList<Warteaktion>,
    private var naechsteLokaleId: Long,
) {

    constructor() : this(mutableListOf(), -1L)

    val aktionen: List<Warteaktion> get() = liste
    val istLeer: Boolean get() = liste.isEmpty()
    val anzahl: Int get() = liste.size

    /** IDs mit noch nicht übertragenem Stand; lokale Kennungen sind negativ. */
    val ausstehendeIds: Set<Long>
        get() = liste.mapTo(mutableSetOf()) {
            when (it) {
                is Warteaktion.Anlegen -> it.lokaleId
                is Warteaktion.Aendern -> it.id
                is Warteaktion.Loeschen -> it.id
            }
        }

    /** true, wenn noch etwas für dieses Kind wartet (dann nicht löschen). */
    fun betrifftKind(childId: Long): Boolean = liste.any {
        (it is Warteaktion.Anlegen && it.childId == childId) ||
            (it is Warteaktion.Aendern && it.childId == childId)
    }

    // ── Aufnehmen ───────────────────────────────────────────────────────────

    /** Nimmt einen neuen Eintrag auf und liefert dessen lokale Kennung. */
    fun lege(neu: EntryInput, zeit: Instant): Long {
        val id = naechsteLokaleId
        naechsteLokaleId -= 1
        liste.add(Warteaktion.Anlegen(id, neu.childId, zeit, neu.intensity, neu.trigger))
        return id
    }

    /**
     * Nimmt eine Änderung auf. Wartet der Eintrag selbst noch, wird dessen
     * [Warteaktion.Anlegen] angepasst; sonst ersetzt die neue Änderung eine
     * ältere für dieselbe ID (es zählt ohnehin nur der letzte Stand).
     */
    fun aendere(id: Long, neu: EntryInput, zeit: Instant) {
        val anlegen = indexDesAnlegens(id)
        if (anlegen >= 0) {
            val alt = liste[anlegen] as Warteaktion.Anlegen
            liste[anlegen] = alt.copy(
                childId = neu.childId,
                createTime = zeit,
                intensity = neu.intensity,
                trigger = neu.trigger,
            )
            return
        }
        val aktion = Warteaktion.Aendern(id, neu.childId, zeit, neu.intensity, neu.trigger)
        val bestehend = liste.indexOfFirst { it is Warteaktion.Aendern && it.id == id }
        if (bestehend >= 0) liste[bestehend] = aktion else liste.add(aktion)
    }

    /** Nimmt eine Löschung auf; ein nie hochgeladener Eintrag fliegt ersatzlos raus. */
    fun loesche(id: Long) {
        val anlegen = indexDesAnlegens(id)
        if (anlegen >= 0) {
            liste.removeAt(anlegen)
            return
        }
        liste.removeAll { it is Warteaktion.Aendern && it.id == id }
        if (liste.none { it is Warteaktion.Loeschen && it.id == id }) liste.add(Warteaktion.Loeschen(id))
    }

    /** Entfernt die erste Aktion – nach erfolgreichem Senden bzw. Verwerfen. */
    fun entferneErste() {
        if (liste.isNotEmpty()) liste.removeAt(0)
    }

    private fun indexDesAnlegens(id: Long): Int =
        if (id >= 0) -1 else liste.indexOfFirst { it is Warteaktion.Anlegen && it.lokaleId == id }

    // ── Anwenden ────────────────────────────────────────────────────────────

    /**
     * Legt die offenen Aktionen über eine Seite des Verlaufs.
     *
     * - Neue Einträge erscheinen nur auf der ersten Seite ([ersteSeite]) und
     *   nur, wenn sie zum Filter [filterKind] passen.
     * - Eine Änderung, die das Kind wechselt, nimmt den Eintrag aus einer
     *   gefilterten Ansicht heraus.
     * - [kindName] liefert den Anzeigenamen für eine Kind-ID.
     */
    fun anwenden(
        eintraege: List<Entry>,
        filterKind: Long?,
        ersteSeite: Boolean,
        kindName: (Long) -> String,
    ): List<Entry> {
        val ergebnis = eintraege.toMutableList()
        for (aktion in liste) {
            when (aktion) {
                is Warteaktion.Anlegen -> if (ersteSeite && (filterKind == null || filterKind == aktion.childId)) {
                    ergebnis.add(
                        Entry(
                            id = aktion.lokaleId,
                            childId = aktion.childId,
                            childName = kindName(aktion.childId),
                            createTime = aktion.createTime,
                            intensity = aktion.intensity,
                            trigger = aktion.trigger,
                        ),
                    )
                }

                is Warteaktion.Aendern -> {
                    val index = ergebnis.indexOfFirst { it.id == aktion.id }
                    if (index < 0) continue
                    if (filterKind != null && filterKind != aktion.childId) {
                        ergebnis.removeAt(index)
                    } else {
                        ergebnis[index] = ergebnis[index].copy(
                            childId = aktion.childId,
                            childName = kindName(aktion.childId),
                            createTime = aktion.createTime,
                            intensity = aktion.intensity,
                            trigger = aktion.trigger,
                        )
                    }
                }

                is Warteaktion.Loeschen -> ergebnis.removeAll { it.id == aktion.id }
            }
        }
        return ergebnis.sortedWith(compareByDescending<Entry> { it.createTime }.thenByDescending { it.id })
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    fun alsJson(): JSONObject {
        val aktionenJson = JSONArray()
        for (aktion in liste) {
            val o = JSONObject()
            when (aktion) {
                is Warteaktion.Anlegen -> o.put("art", "anlegen")
                    .put("lokale_id", aktion.lokaleId)
                    .put("child_id", aktion.childId)
                    .put("create_time", aktion.createTime.toString())
                    .put("intensity", aktion.intensity)
                    .put("trigger", aktion.trigger ?: JSONObject.NULL)

                is Warteaktion.Aendern -> o.put("art", "aendern")
                    .put("id", aktion.id)
                    .put("child_id", aktion.childId)
                    .put("create_time", aktion.createTime.toString())
                    .put("intensity", aktion.intensity)
                    .put("trigger", aktion.trigger ?: JSONObject.NULL)

                is Warteaktion.Loeschen -> o.put("art", "loeschen").put("id", aktion.id)
            }
            aktionenJson.put(o)
        }
        return JSONObject()
            .put("naechste_lokale_id", naechsteLokaleId)
            .put("aktionen", aktionenJson)
    }

    companion object {
        fun ausJson(json: JSONObject): Warteschlange {
            val liste = mutableListOf<Warteaktion>()
            val aktionen = json.optJSONArray("aktionen") ?: JSONArray()
            for (i in 0 until aktionen.length()) {
                val o = aktionen.optJSONObject(i) ?: continue
                val aktion = runCatching {
                    when (o.getString("art")) {
                        "anlegen" -> Warteaktion.Anlegen(
                            lokaleId = o.getLong("lokale_id"),
                            childId = o.getLong("child_id"),
                            createTime = parseIsoZeit(o.getString("create_time")),
                            intensity = o.getInt("intensity"),
                            trigger = o.textOderNull("trigger"),
                        )

                        "aendern" -> Warteaktion.Aendern(
                            id = o.getLong("id"),
                            childId = o.getLong("child_id"),
                            createTime = parseIsoZeit(o.getString("create_time")),
                            intensity = o.getInt("intensity"),
                            trigger = o.textOderNull("trigger"),
                        )

                        "loeschen" -> Warteaktion.Loeschen(o.getLong("id"))
                        else -> null
                    }
                }.getOrNull()
                if (aktion != null) liste.add(aktion)
            }
            // Nie eine lokale ID doppelt vergeben, auch wenn die Datei
            // unvollständig war.
            val kleinsteVergebene = liste.filterIsInstance<Warteaktion.Anlegen>().minOfOrNull { it.lokaleId } ?: 0L
            val gespeichert = json.optLong("naechste_lokale_id", -1L)
            return Warteschlange(liste, minOf(gespeichert, kleinsteVergebene - 1, -1L))
        }

        private fun JSONObject.textOderNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
