package org.dwarftsch.meltdown.data

import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.EntryPage

/**
 * Fehler einer Anfrage, die inhaltlich beantwortet wurde (Statuscode +
 * Meldung des Servers bzw. der lokalen Datenbank). Kein Verbindungsproblem –
 * siehe [Netzfehler].
 */
class ApiException(message: String, val statusCode: Int? = null) : Exception(message) {
    override fun toString(): String =
        if (statusCode != null) "Fehler $statusCode: $message" else message.orEmpty()
}

/**
 * Gemeinsame Schnittstelle der Datenquellen: die Server-API ([ApiService]),
 * deren Offline-Hülle ([OfflineService]) oder die lokale SQLite-Datenbank
 * ([LocalService]). Semantik wie `api.php` des Webservers.
 */
interface MeltdownService {
    /** Alle Kinder, alphabetisch. */
    suspend fun getChildren(): List<Child>

    /** Verlauf, neueste zuerst; [childId] null = alle Kinder. */
    suspend fun getEntries(childId: Long?, offset: Int, limit: Int): EntryPage

    suspend fun createChild(name: String): Child

    suspend fun renameChild(id: Long, name: String): Child

    /** Schlägt mit 409 fehl, solange das Kind Einträge hat. */
    suspend fun deleteChild(id: Long)

    suspend fun createEntry(neu: EntryInput): Entry

    /** Setzt alle Felder; [EntryInput.createTime] muss gesetzt sein. */
    suspend fun updateEntry(id: Long, neu: EntryInput): Entry

    suspend fun deleteEntry(id: Long)

    /** Gibt Ressourcen frei (HTTP-Client bzw. Datenbank-Handle). */
    fun dispose()

    companion object {
        /** Seitengröße des Verlaufs (Server erlaubt bis 500). */
        const val SEITE = 50
    }
}

/** Lesbare Meldung einer Exception (ApiException liefert den Statuscode mit). */
fun Throwable.meldung(): String = when (this) {
    is ApiException -> toString()
    else -> message?.takeIf { it.isNotBlank() } ?: toString()
}
