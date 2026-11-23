package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Startansicht wie im Web: oben „Neuer Eintrag“ mit der einen Hauptaktion
 * „Eintrag speichern“, darunter der Verlauf als Liste mit Haarlinien.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    meldungen: SnackbarHostState,
    onEinstellungen: () -> Unit,
    onKinder: () -> Unit,
    onBearbeiten: (Entry) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var loeschen by remember { mutableStateOf<Entry?>(null) }
    val formular = rememberSaveable(saver = EintragFormular.Saver) { EintragFormular(null, null, null, "") }
    val fokus = LocalFocusManager.current

    Scaffold(
        topBar = {
            // Einstellungen liegen bewusst im Kopf: sie bleiben auch
            // erreichbar, wenn der Inhalt durch die Fehleransicht ersetzt ist.
            StartKopf("Meltdown-Tracker") {
                MhSymbolButton(R.drawable.ic_mh_refresh, "Aktualisieren", viewModel::aktualisieren)
                MhSymbolButton(R.drawable.ic_mh_users, "Kinder verwalten", onKinder)
                MhSymbolButton(R.drawable.ic_mh_sliders, "Einstellungen", onEinstellungen)
            }
        },
        snackbarHost = { SnackbarHost(meldungen) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.eintraege.isEmpty() && state.kinder.isEmpty() && state.fehler == null ->
                    MhLaden()

                state.fehler != null -> FehlerAnsicht(
                    meldung = state.fehler.orEmpty(),
                    onErneut = viewModel::aktualisieren,
                    onEinstellungen = onEinstellungen,
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.laedt,
                    onRefresh = viewModel::aktualisieren,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Inhalt(
                        state = state,
                        formular = formular,
                        vorauswahl = viewModel.vorauswahlKind(),
                        onSpeichern = {
                            formular.eingabe(state.kinder, viewModel.vorauswahlKind(), viewModel::hinweis)?.let {
                                viewModel.anlegen(it)
                                formular.leeren()
                                fokus.clearFocus()
                            }
                        },
                        onUngueltig = viewModel::hinweis,
                        onKinder = onKinder,
                        onFilter = viewModel::setzeFilter,
                        onBearbeiten = onBearbeiten,
                        onLoeschen = { loeschen = it },
                        onMehr = viewModel::mehrLaden,
                    )
                }
            }
        }
    }

    loeschen?.let { eintrag ->
        LoeschDialog(
            titel = "Eintrag löschen?",
            text = "${eintrag.childName}, ${datumUhrzeit(eintrag.createTime)}, Intensität ${eintrag.intensity}",
            onAbbrechen = { loeschen = null },
            onLoeschen = {
                loeschen = null
                viewModel.loeschen(eintrag)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inhalt(
    state: HomeUiState,
    formular: EintragFormular,
    vorauswahl: Long?,
    onSpeichern: () -> Unit,
    onUngueltig: (String) -> Unit,
    onKinder: () -> Unit,
    onFilter: (Long?) -> Unit,
    onBearbeiten: (Entry) -> Unit,
    onLoeschen: (Entry) -> Unit,
    onMehr: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Mh.rand, end = Mh.rand, top = Mh.abstand2, bottom = Mh.abstand8),
    ) {
        // Offline-Hinweis über allem: man kann weiterarbeiten, der Stand ist
        // aber noch nicht beim Server.
        if (state.offlineGrund != null || state.ausstehend > 0) {
            item(key = "offline") {
                Column {
                    OfflineHinweis(grund = state.offlineGrund, ausstehend = state.ausstehend)
                    Spacer(Modifier.height(Mh.abstand6))
                }
            }
        }

        if (state.kinder.isEmpty()) {
            item(key = "keine-kinder") {
                MhLeer(
                    "Noch keine Kinder angelegt. Lege zuerst ein Kind an, danach kannst du hier Einträge erfassen.",
                    aktion = {
                        MhHauptButton("Kind anlegen", onKinder, symbol = R.drawable.ic_mh_plus)
                    },
                )
            }
            return@LazyColumn
        }

        item(key = "neu") {
            Column {
                MhGruppenLabel("Neuer Eintrag", Modifier.padding(top = 0.dp))
                EintragFelder(
                    formular = formular,
                    kinder = state.kinder,
                    vorauswahl = vorauswahl,
                    mitJetzt = true,
                    onUngueltig = onUngueltig,
                )
                Spacer(Modifier.height(Mh.abstand6))
                MhHauptButton("Eintrag speichern", onSpeichern, modifier = Modifier.fillMaxWidth())
            }
        }

        item(key = "verlauf") {
            MhGruppenLabel("Verlauf", Modifier.padding(top = Mh.abstand8), rechts = "neueste zuerst")
        }

        // Filter nur, wenn es etwas zu filtern gibt.
        if (state.kinder.size > 1) {
            item(key = "filter") {
                FlowRow(
                    modifier = Modifier.padding(bottom = Mh.abstand2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MhAuswahl("Alle", gewaehlt = state.filter == null, onClick = { onFilter(null) })
                    state.kinder.forEach { kind ->
                        MhAuswahl(kind.name, gewaehlt = state.filter == kind.id, onClick = { onFilter(kind.id) })
                    }
                }
            }
        }

        if (state.eintraege.isEmpty()) {
            item(key = "leer") {
                MhLeer(if (state.laedt) "Lädt …" else "Noch keine Einträge vorhanden.")
            }
        } else {
            var letzterTag: String? = null
            state.eintraege.forEachIndexed { i, eintrag ->
                val tag = tagesLabel(eintrag.createTime)
                if (tag != letzterTag) {
                    letzterTag = tag
                    // Schlüssel über die Eintrags-ID: derselbe Tag kann bei
                    // nachgetragenen Zeiten in zwei Blöcken auftauchen.
                    item(key = "tag-${eintrag.id}") {
                        MhGruppenLabel(tag, Modifier.padding(top = if (i == 0) Mh.abstand2 else Mh.abstand4))
                    }
                }
                item(key = "eintrag-${eintrag.id}") {
                    VerlaufZeile(
                        eintrag = eintrag,
                        ausstehend = eintrag.id in state.ausstehendeIds,
                        linie = i < state.eintraege.lastIndex,
                        onBearbeiten = { onBearbeiten(eintrag) },
                        onLoeschen = { onLoeschen(eintrag) },
                    )
                }
            }
            if (state.hatMehr) {
                item(key = "mehr") {
                    Box(Modifier.fillMaxWidth().padding(top = Mh.abstand4), contentAlignment = Alignment.Center) {
                        if (state.laedtMehr) {
                            MhLaden(Modifier.height(Mh.touch))
                        } else {
                            MhTextButton("Ältere Einträge laden", onMehr, symbol = R.drawable.ic_mh_chevron_down)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Zeile im Verlauf wie im Web: Uhrzeit, Kind und Auslöser, Intensität als
 * Punkt + Zahl, Bearbeiten und Löschen. Wischen nach links fragt erst per
 * Dialog nach; die Zeile springt deshalb immer zurück.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerlaufZeile(
    eintrag: Entry,
    ausstehend: Boolean,
    /** Haarlinie unter der Zeile (alle außer der letzten). */
    linie: Boolean,
    onBearbeiten: () -> Unit,
    onLoeschen: () -> Unit,
) {
    val f = Mh.farben
    val grosseSchrift = LocalDensity.current.fontScale >= 1.5f
    val wischZustand = rememberSwipeToDismissBoxState(
        confirmValueChange = { wert ->
            if (wert == SwipeToDismissBoxValue.EndToStart) onLoeschen()
            false
        },
    )
    Column {
        SwipeToDismissBox(
            state = wischZustand,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(
                    modifier = Modifier.fillMaxSize().background(f.errorBg),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    MhSymbol(R.drawable.ic_mh_trash, null, Modifier.padding(end = Mh.abstand5), farbe = f.danger)
                }
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(f.bg)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Mh.abstand3),
            ) {
                val uhrzeit = @Composable { modifier: Modifier ->
                    Text(
                        hhmm(eintrag.createTime),
                        style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
                        color = f.textSecondary,
                        modifier = modifier,
                    )
                }
                if (!grosseSchrift) uhrzeit(Modifier.padding(top = 14.dp))
                Column(Modifier.weight(1f).padding(vertical = Mh.abstand3)) {
                    // Große Schrift: Uhrzeit über dem Namen, damit Kind und
                    // Auslöser genug Breite behalten.
                    if (grosseSchrift) uhrzeit(Modifier)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            eintrag.childName,
                            style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
                            color = f.textStrong,
                        )
                        if (ausstehend) {
                            Spacer(Modifier.width(6.dp))
                            MhSymbol(
                                R.drawable.ic_mh_clock,
                                "Wartet auf Übertragung",
                                groesse = Mh.symbolKlein,
                                farbe = f.warningFg,
                            )
                        }
                    }
                    if (eintrag.trigger != null) {
                        Text(eintrag.trigger, style = MhTypo.small, color = f.textSecondary)
                    }
                }
                IntensitaetsMarke(eintrag.intensity, Modifier.padding(top = 14.dp).widthIn(min = 36.dp))
                Row {
                    MhSymbolButton(R.drawable.ic_mh_edit, "Bearbeiten", onBearbeiten)
                    MhSymbolButton(R.drawable.ic_mh_trash, "Eintrag löschen", onLoeschen)
                }
            }
        }
        if (linie) MhTrennlinie()
    }
}

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit, onEinstellungen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Mh.abstand8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MhSymbol(R.drawable.ic_mh_alert, null, groesse = 32.dp, farbe = Mh.farben.errorFg)
        Spacer(Modifier.height(Mh.abstand4))
        Text(meldung, style = MhTypo.body, color = Mh.farben.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Mh.abstand6))
        MhHauptButton("Erneut versuchen", onErneut, symbol = R.drawable.ic_mh_refresh)
        Spacer(Modifier.height(Mh.abstand2))
        MhZweitButton("Einstellungen", onEinstellungen, symbol = R.drawable.ic_mh_sliders)
    }
}

/**
 * Hinweis über dem Inhalt: Verbindung weg, App weiter benutzbar.
 * [grund] null heisst „wieder online, aber es wartet noch etwas“.
 */
@Composable
private fun OfflineHinweis(grund: String?, ausstehend: Int) {
    val titel = if (grund == null) "Übertragung läuft" else "Offline: $grund"
    val text = when (ausstehend) {
        0 -> "Angezeigt wird der zuletzt geladene Stand."
        1 -> "Eine Änderung wartet auf die Übertragung und geht raus, sobald die Verbindung steht."
        else -> "$ausstehend Änderungen warten auf die Übertragung und gehen raus, sobald die Verbindung steht."
    }
    MhHinweis(
        text = text,
        titel = titel,
        art = MhHinweisArt.Warnung,
        symbol = if (grund == null) R.drawable.ic_mh_upload else R.drawable.ic_mh_cloud_off,
    )
}

// --- Datums-Helfer -----------------------------------------------------------

private val DEUTSCH = Locale.GERMANY

internal fun hhmm(zeit: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(zeit)

/** z. B. „Do., 30.10.2026, 19:45“. */
internal fun datumUhrzeit(zeit: Instant): String =
    DateTimeFormatter.ofPattern("EE, dd.MM.yyyy, HH:mm", DEUTSCH).withZone(ZoneId.systemDefault()).format(zeit)

internal fun tagesLabel(zeit: Instant, heute: LocalDate = LocalDate.now()): String {
    val tag = zeit.atZone(ZoneId.systemDefault()).toLocalDate()
    return when (heute.toEpochDay() - tag.toEpochDay()) {
        0L -> "Heute"
        1L -> "Gestern"
        else -> tag.format(DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", DEUTSCH))
    }
}
