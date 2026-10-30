package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Welcher Dialog gerade offen ist. */
private sealed interface DialogZustand {
    data object Neu : DialogZustand
    data class Bearbeiten(val eintrag: Entry) : DialogZustand
    data class Loeschen(val eintrag: Entry) : DialogZustand
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEinstellungen: () -> Unit,
    onKinder: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogZustand?>(null) }

    LaunchedEffect(Unit) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            // Einstellungen liegen bewusst in der AppBar: sie bleiben auch
            // erreichbar, wenn der Inhalt durch die Fehleransicht ersetzt ist.
            TopAppBar(
                title = { Text("🫂 Meltdown-Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = viewModel::aktualisieren) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(onClick = onKinder) {
                        Icon(Icons.Filled.Groups, contentDescription = "Kinder verwalten")
                    }
                    IconButton(onClick = onEinstellungen) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.eintraege.isEmpty() && state.kinder.isEmpty() && state.fehler == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

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
                        onErfassen = { dialog = DialogZustand.Neu },
                        onKinder = onKinder,
                        onFilter = viewModel::setzeFilter,
                        onBearbeiten = { dialog = DialogZustand.Bearbeiten(it) },
                        onLoeschen = { dialog = DialogZustand.Loeschen(it) },
                        onMehr = viewModel::mehrLaden,
                    )
                }
            }
        }
    }

    when (val aktuell = dialog) {
        null -> Unit

        DialogZustand.Neu -> EintragDialog(
            kinder = state.kinder,
            bestehend = null,
            vorauswahlKind = viewModel.vorauswahlKind(),
            onAbbrechen = { dialog = null },
            onSpeichern = {
                dialog = null
                viewModel.anlegen(it)
            },
            onUngueltig = viewModel::hinweis,
        )

        is DialogZustand.Bearbeiten -> EintragDialog(
            kinder = state.kinder,
            bestehend = aktuell.eintrag,
            vorauswahlKind = null,
            onAbbrechen = { dialog = null },
            onSpeichern = {
                dialog = null
                viewModel.aendern(aktuell.eintrag, it)
            },
            onUngueltig = viewModel::hinweis,
        )

        is DialogZustand.Loeschen -> LoeschDialog(
            titel = "Eintrag löschen?",
            text = "${aktuell.eintrag.childName}, ${datumUhrzeit(aktuell.eintrag.createTime)}, " +
                "Intensität ${aktuell.eintrag.intensity}",
            onAbbrechen = { dialog = null },
            onLoeschen = {
                dialog = null
                viewModel.loeschen(aktuell.eintrag)
            },
        )
    }
}

@Composable
private fun Inhalt(
    state: HomeUiState,
    onErfassen: () -> Unit,
    onKinder: () -> Unit,
    onFilter: (Long?) -> Unit,
    onBearbeiten: (Entry) -> Unit,
    onLoeschen: (Entry) -> Unit,
    onMehr: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        // Offline-Hinweis über allem: man kann weiterarbeiten, der Stand ist
        // aber noch nicht beim Server.
        if (state.offlineGrund != null || state.ausstehend > 0) {
            item(key = "offline") {
                OfflineBanner(grund = state.offlineGrund, ausstehend = state.ausstehend)
                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.kinder.isEmpty()) {
            item(key = "keine-kinder") { KeineKinder(onKinder) }
            return@LazyColumn
        }

        item(key = "erfassen") {
            Button(
                onClick = onErfassen,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Meltdown erfassen", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Filter nur, wenn es etwas zu filtern gibt.
        if (state.kinder.size > 1) {
            item(key = "filter") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.filter == null,
                        onClick = { onFilter(null) },
                        shape = RoundedCornerShape(50),
                        colors = chipFarben(),
                        label = { Text("Alle") },
                    )
                    state.kinder.forEach { kind ->
                        FilterChip(
                            selected = state.filter == kind.id,
                            onClick = { onFilter(kind.id) },
                            shape = RoundedCornerShape(50),
                            colors = chipFarben(),
                            label = { Text(kind.name) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (state.eintraege.isEmpty()) {
            item(key = "leer") {
                Text(
                    if (state.laedt) "Lädt …" else "Noch keine Einträge",
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            var letzterTag: String? = null
            for (eintrag in state.eintraege) {
                val tag = tagesLabel(eintrag.createTime)
                if (tag != letzterTag) {
                    letzterTag = tag
                    // Schlüssel über die Eintrags-ID: derselbe Tag kann bei
                    // nachgetragenen Zeiten in zwei Blöcken auftauchen.
                    item(key = "tag-${eintrag.id}") { TagesUeberschrift(tag) }
                }
                item(key = "eintrag-${eintrag.id}") {
                    EintragsKachel(
                        eintrag = eintrag,
                        mitKind = state.filter == null && state.kinder.size > 1,
                        ausstehend = eintrag.id in state.ausstehendeIds,
                        onBearbeiten = { onBearbeiten(eintrag) },
                        onLoeschen = { onLoeschen(eintrag) },
                    )
                }
            }
            if (state.hatMehr) {
                item(key = "mehr") {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        if (state.laedtMehr) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        } else {
                            OutlinedButton(
                                onClick = onMehr,
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                            ) { Text("Ältere Einträge laden") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeineKinder(onKinder: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Noch keine Kinder angelegt", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Jeder Eintrag gehört zu einem Kind. Lege zuerst mindestens ein Kind an.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onKinder, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Kinder verwalten")
            }
        }
    }
}

@Composable
private fun TagesUeberschrift(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MinzeHonig.farben.sektionsTitel,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EintragsKachel(
    eintrag: Entry,
    /** Kindername in der Kachel zeigen (Ansicht „Alle“ mit mehreren Kindern). */
    mitKind: Boolean,
    ausstehend: Boolean,
    onBearbeiten: () -> Unit,
    onLoeschen: () -> Unit,
) {
    // Wischen nach links fragt erst per Dialog nach; die Kachel springt
    // deshalb immer zurück.
    val wischZustand = rememberSwipeToDismissBoxState(
        confirmValueChange = { wert ->
            if (wert == SwipeToDismissBoxValue.EndToStart) onLoeschen()
            false
        },
    )
    SwipeToDismissBox(
        state = wischZustand,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IntensitaetsBadge(eintrag.intensity)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (mitKind) "${hhmm(eintrag.createTime)} · ${eintrag.childName}" else hhmm(eintrag.createTime),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (ausstehend) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = "Wartet auf Übertragung",
                                tint = MinzeHonig.farben.hinweis,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        eintrag.trigger ?: "Kein Auslöser angegeben",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onBearbeiten) {
                    Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onLoeschen) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Eintrag löschen", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Kreis mit der Intensität; Farbe nach Stufe, die Zahl trägt die Information. */
@Composable
private fun IntensitaetsBadge(wert: Int) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(intensitaetFlaeche(wert), CircleShape)
            .semantics { contentDescription = "Intensität $wert von 10" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$wert", color = intensitaetInhalt(wert), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit, onEinstellungen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(meldung, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onErneut, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Erneut versuchen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onEinstellungen,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Einstellungen")
        }
    }
}

/**
 * Hinweisleiste über der Liste: Verbindung weg, App weiter benutzbar.
 * [grund] null heisst „wieder online, aber es wartet noch etwas“.
 */
@Composable
private fun OfflineBanner(grund: String?, ausstehend: Int) {
    val titel = if (grund == null) "Übertragung läuft" else "Offline-Modus – $grund"
    val untertitel = when (ausstehend) {
        0 -> "Angezeigt wird der zuletzt geladene Stand."
        1 -> "Eine Änderung wartet auf die Übertragung und geht raus, sobald die Verbindung steht."
        else -> "$ausstehend Änderungen warten auf die Übertragung und gehen raus, sobald die Verbindung steht."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MinzeHonig.farben.hinweisFlaeche),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (grund == null) Icons.Outlined.CloudUpload else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MinzeHonig.farben.hinweis,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(titel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MinzeHonig.farben.hinweis)
                Text(untertitel, style = MaterialTheme.typography.bodySmall, color = MinzeHonig.farben.hinweis)
            }
        }
    }
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
