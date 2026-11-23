package org.dwarftsch.meltdown.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.data.ApiException
import org.dwarftsch.meltdown.data.AppSettings
import org.dwarftsch.meltdown.data.CertSource
import org.dwarftsch.meltdown.data.MeltdownService
import org.dwarftsch.meltdown.data.OfflineService
import org.dwarftsch.meltdown.data.Verbindungswache
import org.dwarftsch.meltdown.data.createConfiguredService
import org.dwarftsch.meltdown.data.meldung

data class HomeUiState(
    val laedt: Boolean = true,
    val fehler: String? = null,
    val kinder: List<Child> = emptyList(),
    /** Filter des Verlaufs; null = alle Kinder. */
    val filter: Long? = null,
    val eintraege: List<Entry> = emptyList(),
    /** Offset der nächsten Seite in der Zählung der Quelle. */
    val naechsterOffset: Int = 0,
    val hatMehr: Boolean = false,
    val laedtMehr: Boolean = false,
    /** Grund der abgebrochenen Verbindung; null heisst „online“. */
    val offlineGrund: String? = null,
    /** Anzahl der Schreibzugriffe, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
    /** IDs, deren Stand noch nicht beim Server ist – die Liste markiert sie. */
    val ausstehendeIds: Set<Long> = emptySet(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    val certSource = CertSource(application, settings)

    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state

    private val meldungenFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar-Meldungen (Fehler, Bestätigungen). */
    val meldungen: SharedFlow<String> = meldungenFlow

    fun hinweis(text: String) {
        meldungenFlow.tryEmit(text)
    }

    private var service: MeltdownService? = null
    private var zustandBeobachter: Job? = null
    private var ladeJob: Job? = null
    private val wache = Verbindungswache(application)

    init {
        // Sobald wieder ein Netz da ist, die Warteschlange abarbeiten – ohne
        // dass jemand etwas antippen muss.
        wache.starten()
        viewModelScope.launch { wache.wiederVerbunden.collect { aktualisieren() } }
        datenquelleNeuAufbauen()
    }

    override fun onCleared() {
        wache.beenden()
        service?.dispose()
        super.onCleared()
    }

    /** Baut die Datenquelle nach den Einstellungen neu auf und lädt neu. */
    fun datenquelleNeuAufbauen() {
        service?.dispose()
        zustandBeobachter?.cancel()
        val neu = createConfiguredService(getApplication(), settings, certSource, offlineFaehig = true)
        service = neu
        // Nichts vom alten Zugang stehen lassen: weder Einträge noch Kinder
        // noch den Offline-Hinweis.
        state.value = HomeUiState()
        if (neu is OfflineService) {
            zustandBeobachter = viewModelScope.launch {
                neu.zustand.collect { z ->
                    state.value = state.value.copy(
                        offlineGrund = z.grund,
                        ausstehend = z.ausstehend,
                        ausstehendeIds = z.ausstehendeIds,
                    )
                }
            }
        }
        aktualisieren()
    }

    /** Lädt Kinder und die erste Seite des Verlaufs neu. */
    fun aktualisieren() {
        val dienst = service ?: return
        ladeJob?.cancel()
        state.value = state.value.copy(laedt = true, fehler = null)
        ladeJob = viewModelScope.launch {
            // Erst das Liegengebliebene loswerden, dann laden: sonst zeigte die
            // Liste einen Serverstand ohne die eigenen Einträge.
            warteschlangeAbarbeiten(dienst)
            runCatching {
                val kinder = dienst.getChildren()
                // Ein gelöschtes Kind kann nicht Filter bleiben.
                val filter = state.value.filter?.takeIf { f -> kinder.any { it.id == f } }
                Triple(kinder, filter, dienst.getEntries(filter, 0, MeltdownService.SEITE))
            }.fold(
                onSuccess = { (kinder, filter, seite) ->
                    state.value = state.value.copy(
                        laedt = false,
                        kinder = kinder,
                        filter = filter,
                        eintraege = seite.entries.distinctBy { it.id },
                        naechsterOffset = seite.naechsterOffset,
                        hatMehr = seite.hatMehr,
                    )
                },
                onFailure = { fehler ->
                    if (fehler is kotlinx.coroutines.CancellationException) throw fehler
                    state.value = state.value.copy(laedt = false, fehler = fehler.meldung())
                },
            )
        }
    }

    fun setzeFilter(kind: Long?) {
        if (state.value.filter == kind) return
        state.value = state.value.copy(filter = kind, eintraege = emptyList(), hatMehr = false, naechsterOffset = 0)
        aktualisieren()
    }

    /** Hängt die nächste Seite des Verlaufs an. */
    fun mehrLaden() {
        val dienst = service ?: return
        val aktuell = state.value
        if (aktuell.laedtMehr || !aktuell.hatMehr || aktuell.laedt) return
        state.value = aktuell.copy(laedtMehr = true)
        viewModelScope.launch {
            runCatching { dienst.getEntries(aktuell.filter, aktuell.naechsterOffset, MeltdownService.SEITE) }.fold(
                onSuccess = { seite ->
                    val jetzt = state.value
                    // Zwischenzeitlich Filter gewechselt: Ergebnis verwerfen.
                    if (jetzt.filter != aktuell.filter) {
                        state.value = jetzt.copy(laedtMehr = false)
                        return@fold
                    }
                    state.value = jetzt.copy(
                        laedtMehr = false,
                        eintraege = (jetzt.eintraege + seite.entries).distinctBy { it.id },
                        naechsterOffset = seite.naechsterOffset,
                        hatMehr = seite.hatMehr,
                    )
                },
                onFailure = {
                    state.value = state.value.copy(laedtMehr = false)
                    hinweis("Weitere Einträge ließen sich nicht laden: ${it.meldung()}")
                },
            )
        }
    }

    private suspend fun warteschlangeAbarbeiten(dienst: MeltdownService) {
        if (dienst !is OfflineService) return
        val verworfen = dienst.nachholen()
        if (verworfen.isEmpty()) return
        hinweis(
            if (verworfen.size == 1) {
                "Eine wartende Änderung wurde vom Server abgelehnt: ${verworfen.first()}"
            } else {
                "${verworfen.size} wartende Änderungen wurden vom Server abgelehnt."
            },
        )
    }

    /** Kind, das beim Erfassen vorausgewählt wird. */
    fun vorauswahlKind(): Long? {
        val kinder = state.value.kinder
        state.value.filter?.let { return it }
        settings.letztesKind?.takeIf { id -> kinder.any { it.id == id } }?.let { return it }
        return kinder.singleOrNull()?.id
    }

    // ── Einträge ────────────────────────────────────────────────────────────

    fun anlegen(neu: EntryInput) {
        settings.letztesKind = neu.childId
        schreibe("Eintrag gespeichert") { it.createEntry(neu) }
    }

    fun aendern(eintrag: Entry, neu: EntryInput) {
        schreibe("Eintrag geändert") { it.updateEntry(eintrag.id, neu) }
    }

    fun loeschen(eintrag: Entry) {
        schreibe("Eintrag gelöscht") { it.deleteEntry(eintrag.id) }
    }

    // ── Kinder ──────────────────────────────────────────────────────────────

    fun kindAnlegen(name: String) = schreibe("Kind angelegt") { it.createChild(name) }

    fun kindUmbenennen(kind: Child, name: String) = schreibe("Name geändert") { it.renameChild(kind.id, name) }

    fun kindLoeschen(kind: Child) = schreibe("Kind gelöscht") { it.deleteChild(kind.id) }

    /** Führt eine schreibende Aktion aus, meldet das Ergebnis und lädt neu. */
    private fun schreibe(erfolg: String, aktion: suspend (MeltdownService) -> Any?) {
        val dienst = service ?: return
        viewModelScope.launch {
            runCatching { aktion(dienst) }.fold(
                onSuccess = {
                    hinweis(erfolg)
                    aktualisieren()
                },
                // ApiException mit Status meldet sich schon als „Fehler 409: …“.
                onFailure = {
                    hinweis(if (it is ApiException && it.statusCode != null) it.meldung() else "Fehler: ${it.meldung()}")
                },
            )
        }
    }
}
