package org.dwarftsch.meltdown.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.EntryPage
import java.time.Instant

/** Der Offline-Zustand, den die Oberfläche anzeigt. */
data class OfflineZustand(
    /** Grund der letzten gescheiterten Verbindung; null heisst „online“. */
    val grund: String? = null,
    /** Anzahl der Schreibzugriffe, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
    /** IDs, deren Stand noch nicht beim Server ist (lokale sind negativ). */
    val ausstehendeIds: Set<Long> = emptySet(),
)

/**
 * Legt sich über die Server-Quelle und hält die App bei einem
 * Verbindungsabbruch benutzbar.
 *
 * Lesen: Bei jedem Netzwerkfehler wird der zuletzt erfolgreiche Stand
 * gezeigt (Kinder und erste Verlaufsseite je Filter).
 *
 * Schreiben (nur Einträge): In die Warteschlange darf eine Aktion **nur**,
 * wenn sie den Server nachweislich nie erreicht hat
 * ([Netzfehler.NIE_GESENDET]). Bei einer Zeitüberschreitung oder einem
 * Abbruch mitten in der Übertragung könnte der Server sie bereits ausgeführt
 * haben; ein zweiter Versuch legte dann einen zweiten Eintrag an (die API
 * kennt keinen Idempotenz-Schlüssel). Solche Fälle melden einen Fehler.
 *
 * Kinder verwalten geht nur online, siehe [Warteaktion].
 */
class OfflineService(
    private val innen: MeltdownService,
    private val speicher: OfflineSpeicher,
) : MeltdownService {

    private val sperre = Mutex()
    private var warteschlange = speicher.ladeWarteschlange()

    private val zustandFlow = MutableStateFlow(
        OfflineZustand(ausstehend = warteschlange.anzahl, ausstehendeIds = warteschlange.ausstehendeIds),
    )

    val zustand: StateFlow<OfflineZustand> = zustandFlow

    override fun dispose() = innen.dispose()

    // ── Lesen ───────────────────────────────────────────────────────────────

    override suspend fun getChildren(): List<Child> = try {
        innen.getChildren().also {
            speicher.speichereKinder(it)
            melde(grund = null)
        }
    } catch (fehler: Throwable) {
        val stand = speicher.ladeKinder()
        if (Netzfehler.aus(fehler) == null || stand == null) throw fehler
        melde(grund = fehler.meldung())
        stand
    }

    override suspend fun getEntries(childId: Long?, offset: Int, limit: Int): EntryPage {
        val ersteSeite = offset == 0
        val seite = try {
            innen.getEntries(childId, offset, limit).also {
                if (ersteSeite) speicher.speichereVerlauf(childId, it.entries, it.hatMehr)
                melde(grund = null)
            }
        } catch (fehler: Throwable) {
            val stand = if (ersteSeite) speicher.ladeVerlauf(childId) else null
            if (Netzfehler.aus(fehler) == null || stand == null) throw fehler
            melde(grund = fehler.meldung())
            EntryPage(stand.first, naechsterOffset = stand.first.size, hatMehr = stand.second)
        }
        val namen = kindNamen()
        val ueberlagert = sperre.withLock {
            warteschlange.anwenden(seite.entries, childId, ersteSeite) { namen[it] ?: "?" }
        }
        return seite.copy(entries = ueberlagert)
    }

    private fun kindNamen(): Map<Long, String> =
        speicher.ladeKinder().orEmpty().associate { it.id to it.name }

    // ── Kinder: nur online ──────────────────────────────────────────────────

    override suspend fun createChild(name: String): Child = online { innen.createChild(name) }

    override suspend fun renameChild(id: Long, name: String): Child = online { innen.renameChild(id, name) }

    override suspend fun deleteChild(id: Long) {
        if (sperre.withLock { warteschlange.betrifftKind(id) }) {
            throw ApiException("Für dieses Kind warten noch Einträge auf die Übertragung.")
        }
        online { innen.deleteChild(id) }
    }

    private suspend fun <T> online(aktion: suspend () -> T): T = try {
        aktion().also { melde(grund = null) }
    } catch (fehler: Throwable) {
        if (Netzfehler.aus(fehler) == null) throw fehler
        melde(grund = fehler.meldung())
        throw ApiException("Keine Verbindung – Kinder lassen sich nur online verwalten. (${fehler.meldung()})")
    }

    // ── Einträge schreiben ──────────────────────────────────────────────────

    override suspend fun createEntry(neu: EntryInput): Entry {
        // Offline steht der Erfassungszeitpunkt fest, sonst bekäme der Eintrag
        // beim Nachholen die Uhrzeit des Hochladens.
        val zeit = neu.createTime ?: Instant.now()
        // Reihenfolge wahren: Steht schon etwas an, gehört auch das Neue
        // hinten dran, statt es am Stau vorbeizuschicken.
        if (!istLeer()) return reiheEin(neu, zeit)
        return try {
            innen.createEntry(neu).also { melde(grund = null) }
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            reiheEin(neu, zeit)
        }
    }

    override suspend fun updateEntry(id: Long, neu: EntryInput): Entry {
        val zeit = neu.createTime ?: throw ApiException("Zeitpunkt fehlt.")
        if (id < 0 || !istLeer()) {
            schreibe { it.aendere(id, neu, zeit) }
            return platzhalter(id, neu, zeit)
        }
        return try {
            innen.updateEntry(id, neu).also { melde(grund = null) }
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            schreibe { it.aendere(id, neu, zeit) }
            platzhalter(id, neu, zeit)
        }
    }

    override suspend fun deleteEntry(id: Long) {
        // Negative IDs kennt nur die App: der Eintrag wartet noch.
        if (id < 0 || !istLeer()) {
            schreibe { it.loesche(id) }
            return
        }
        try {
            innen.deleteEntry(id)
            melde(grund = null)
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            schreibe { it.loesche(id) }
        }
    }

    // ── Nachholen ───────────────────────────────────────────────────────────

    /**
     * Arbeitet die Warteschlange von vorn ab. Bricht beim ersten
     * Verbindungsfehler ab – der Rest bleibt in der Reihenfolge stehen. Weist
     * der Server eine Aktion inhaltlich zurück (etwa ein längst gelöschter
     * Eintrag), fliegt sie raus und wird gemeldet; sonst blockierte sie die
     * Warteschlange für immer.
     *
     * Liefert die Meldungen zu verworfenen Aktionen.
     */
    suspend fun nachholen(): List<String> {
        val verworfen = mutableListOf<String>()
        while (true) {
            val naechste = sperre.withLock { warteschlange.aktionen.firstOrNull() } ?: break
            try {
                sende(naechste)
                schreibe { it.entferneErste() }
            } catch (fehler: Throwable) {
                if (Netzfehler.aus(fehler) != null) {
                    melde(grund = fehler.meldung())
                    return verworfen
                }
                schreibe { it.entferneErste() }
                verworfen.add(fehler.meldung())
            }
        }
        melde(grund = null)
        return verworfen
    }

    private suspend fun sende(aktion: Warteaktion) {
        when (aktion) {
            is Warteaktion.Anlegen -> innen.createEntry(
                EntryInput(aktion.childId, aktion.createTime, aktion.intensity, aktion.trigger),
            )
            is Warteaktion.Aendern -> innen.updateEntry(
                aktion.id,
                EntryInput(aktion.childId, aktion.createTime, aktion.intensity, aktion.trigger),
            )
            is Warteaktion.Loeschen -> innen.deleteEntry(aktion.id)
        }
    }

    // ── Innere Hilfen ───────────────────────────────────────────────────────

    private suspend fun istLeer(): Boolean = sperre.withLock { warteschlange.istLeer }

    private suspend fun reiheEin(neu: EntryInput, zeit: Instant): Entry {
        var id = -1L
        schreibe { id = it.lege(neu, zeit) }
        return platzhalter(id, neu, zeit)
    }

    private fun platzhalter(id: Long, neu: EntryInput, zeit: Instant) = Entry(
        id = id,
        childId = neu.childId,
        childName = kindNamen()[neu.childId] ?: "?",
        createTime = zeit,
        intensity = neu.intensity,
        trigger = neu.trigger,
    )

    private suspend fun schreibe(aenderung: (Warteschlange) -> Unit) {
        val stand = sperre.withLock {
            aenderung(warteschlange)
            speicher.speichere(warteschlange)
            warteschlange.anzahl to warteschlange.ausstehendeIds
        }
        zustandFlow.value = zustandFlow.value.copy(ausstehend = stand.first, ausstehendeIds = stand.second)
    }

    private fun melde(grund: String?) {
        if (zustandFlow.value.grund != grund) {
            zustandFlow.value = zustandFlow.value.copy(grund = grund)
        }
    }
}

/**
 * Meldet, sobald wieder ein Netzwerk da ist – damit die Warteschlange nicht
 * erst beim nächsten Antippen abgearbeitet wird.
 */
class Verbindungswache(context: Context) {

    private val wiederVerbundenFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Feuert bei jedem Wechsel von „kein Netz“ zu „Netz da“. */
    val wiederVerbunden: SharedFlow<Unit> = wiederVerbundenFlow

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var warOffline = false

    private val rueckruf = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (warOffline) wiederVerbundenFlow.tryEmit(Unit)
            warOffline = false
        }

        override fun onLost(network: Network) {
            warOffline = true
        }
    }

    fun starten() {
        runCatching { manager?.registerNetworkCallback(NetworkRequest.Builder().build(), rueckruf) }
    }

    fun beenden() {
        runCatching { manager?.unregisterNetworkCallback(rueckruf) }
    }
}
