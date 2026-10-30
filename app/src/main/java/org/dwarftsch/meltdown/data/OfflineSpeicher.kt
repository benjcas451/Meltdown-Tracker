package org.dwarftsch.meltdown.data

import android.content.Context
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Legt Warteschlange und Lesestand je Zugang im app-privaten Verzeichnis ab
 * (`filesDir/offline`, nicht im Backup).
 *
 * Der Schlüssel ist Modus plus Endpunkt: wer zwischen zwei Servern wechselt,
 * bekommt nicht die Einträge des anderen zu sehen und lädt auch keine
 * Warteschlange dorthin hoch, wo sie nicht hingehört. Vom Verlauf wird je
 * Filter (alle bzw. ein Kind) die erste Seite gemerkt.
 */
class OfflineSpeicher(private val ordner: File, zugang: String) {

    constructor(context: Context, zugang: String) :
        this(File(context.applicationContext.filesDir, "offline"), zugang)

    init {
        ordner.mkdirs()
    }

    private val schluessel = zugang.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    private val warteschlangeDatei get() = File(ordner, "warteschlange_$schluessel.json")
    private val kinderDatei get() = File(ordner, "kinder_$schluessel.json")
    private fun verlaufDatei(filter: Long?) = File(ordner, "verlauf_${schluessel}_${filter ?: "alle"}.json")

    fun ladeWarteschlange(): Warteschlange =
        lies(warteschlangeDatei)?.let { Warteschlange.ausJson(it) } ?: Warteschlange()

    fun speichere(warteschlange: Warteschlange) = schreibe(warteschlangeDatei, warteschlange.alsJson())

    fun ladeKinder(): List<Child>? {
        val liste = lies(kinderDatei)?.optJSONArray("kinder") ?: return null
        return (0 until liste.length()).mapNotNull { i ->
            liste.optJSONObject(i)?.let { runCatching { ApiService.kindAusJson(it) }.getOrNull() }
        }
    }

    fun speichereKinder(kinder: List<Child>) {
        val liste = JSONArray()
        kinder.forEach { liste.put(JSONObject().put("id", it.id).put("name", it.name).put("entries", it.entries)) }
        schreibe(kinderDatei, JSONObject().put("kinder", liste))
    }

    /** Erste Seite des Verlaufs für [filter]; null wenn nie geladen. */
    fun ladeVerlauf(filter: Long?): Pair<List<Entry>, Boolean>? {
        val json = lies(verlaufDatei(filter)) ?: return null
        val liste = json.optJSONArray("eintraege") ?: return null
        return ApiService.eintraegeAusJson(liste) to json.optBoolean("hat_mehr", false)
    }

    fun speichereVerlauf(filter: Long?, eintraege: List<Entry>, hatMehr: Boolean) {
        val liste = JSONArray()
        for (e in eintraege) {
            liste.put(
                JSONObject()
                    .put("id", e.id)
                    .put("child_id", e.childId)
                    .put("child_name", e.childName)
                    .put("create_time", e.createTime.toString())
                    .put("intensity", e.intensity)
                    .put("trigger", e.trigger ?: JSONObject.NULL),
            )
        }
        schreibe(verlaufDatei(filter), JSONObject().put("eintraege", liste).put("hat_mehr", hatMehr))
    }

    private fun lies(datei: File): JSONObject? = runCatching { JSONObject(datei.readText()) }.getOrNull()

    /**
     * Erst in eine Nebendatei, dann umbenennen: ein Absturz mitten im
     * Schreiben hinterlässt sonst eine halbe Datei, und die Warteschlange
     * wäre verloren.
     */
    private fun schreibe(datei: File, json: JSONObject) {
        runCatching {
            val temp = File(datei.parentFile, "${datei.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(datei)) {
                datei.writeText(json.toString())
                temp.delete()
            }
        }
    }
}
