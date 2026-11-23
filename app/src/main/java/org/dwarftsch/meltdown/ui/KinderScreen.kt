package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Grenzen
import org.dwarftsch.meltdown.R

private sealed interface KindDialog {
    data class Umbenennen(val kind: Child) : KindDialog
    data class Loeschen(val kind: Child) : KindDialog
}

/**
 * Kinder anlegen, umbenennen und löschen (wie `kinder.php` im Web). Ein Kind
 * mit Einträgen lässt sich nicht löschen: der Verlauf geht nie
 * stillschweigend verloren. In den Server-Modi geht das nur mit Verbindung.
 */
@Composable
fun KinderScreen(viewModel: HomeViewModel, meldungen: SnackbarHostState, onZurueck: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var dialog by remember { mutableStateOf<KindDialog?>(null) }
    var neuName by rememberSaveable { mutableStateOf("") }
    val fokus = LocalFocusManager.current

    fun anlegen() {
        Grenzen.pruefeName(neuName).fold(
            onSuccess = {
                viewModel.kindAnlegen(it)
                neuName = ""
                fokus.clearFocus()
            },
            onFailure = { viewModel.hinweis(it.message.orEmpty()) },
        )
    }

    Scaffold(
        topBar = { MhKopf(titel = "Kinder", onZurueck = onZurueck, zurueckText = "Verlauf") },
        snackbarHost = { SnackbarHost(meldungen) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        LazyColumn(
            modifier = Modifier.padding(innenAbstand).fillMaxSize(),
            contentPadding = PaddingValues(start = Mh.rand, end = Mh.rand, top = Mh.abstand2, bottom = Mh.abstand8),
        ) {
            item(key = "neu") { Column {
                MhGruppenLabel("Neues Kind", Modifier.padding(top = 0.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Mh.abstand2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = neuName,
                        onValueChange = {
                            if (it.codePointCount(0, it.length) <= Grenzen.NAME_MAX) neuName = it.replace('\n', ' ')
                        },
                        textStyle = MhTypo.body,
                        placeholder = { Text("Name des Kindes", style = MhTypo.body) },
                        singleLine = true,
                        shape = MhFeldForm,
                        colors = mhFeldFarben(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { anlegen() }),
                        modifier = Modifier.weight(1f),
                    )
                    MhHauptButton("Anlegen", ::anlegen)
                }
            } }

            item(key = "liste") {
                MhGruppenLabel("Kinder", Modifier.padding(top = Mh.abstand8))
            }
            if (state.kinder.isEmpty() && !state.laedt) {
                item(key = "leer") { MhLeer("Noch keine Kinder angelegt.") }
            }
            itemsIndexed(state.kinder, key = { _, kind -> kind.id }) { i, kind ->
                Column {
                    if (i > 0) MhTrennlinie()
                    KindZeile(
                        kind = kind,
                        onUmbenennen = { dialog = KindDialog.Umbenennen(kind) },
                        onLoeschen = { dialog = KindDialog.Loeschen(kind) },
                        onGesperrt = {
                            viewModel.hinweis("${kind.name} hat noch Einträge im Verlauf und wird deshalb nicht gelöscht.")
                        },
                    )
                }
            }
            if (state.kinder.isNotEmpty()) {
                item(key = "hinweis") {
                    Text(
                        "Ein Kind mit Einträgen kann nicht gelöscht werden, so geht kein Verlauf verloren. " +
                            "Wer es dennoch entfernen möchte, löscht zuerst die Einträge einzeln im Verlauf.",
                        style = MhTypo.small,
                        color = Mh.farben.textSecondary,
                        modifier = Modifier.padding(top = Mh.abstand4),
                    )
                }
            }
        }
    }

    when (val aktuell = dialog) {
        null -> Unit
        is KindDialog.Umbenennen -> NameDialog(
            titel = "Umbenennen",
            initial = aktuell.kind.name,
            onAbbrechen = { dialog = null },
            onSpeichern = {
                dialog = null
                if (it != aktuell.kind.name) viewModel.kindUmbenennen(aktuell.kind, it)
            },
            onUngueltig = viewModel::hinweis,
        )
        is KindDialog.Loeschen -> LoeschDialog(
            titel = "Kind löschen?",
            text = "${aktuell.kind.name} wird entfernt. Das Kind hat keine Einträge.",
            onAbbrechen = { dialog = null },
            onLoeschen = {
                dialog = null
                viewModel.kindLoeschen(aktuell.kind)
            },
        )
    }
}

/** Name und Anzahl der Einträge; Löschen nur ohne Einträge, sonst ein Schloss. */
@Composable
private fun KindZeile(kind: Child, onUmbenennen: () -> Unit, onLoeschen: () -> Unit, onGesperrt: () -> Unit) {
    val f = Mh.farben
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Mh.abstand2)) {
            Text(kind.name, style = MhTypo.body.copy(fontWeight = FontWeight.Medium), color = f.textStrong)
            Text(
                when (kind.entries) {
                    0 -> "Keine Einträge"
                    1 -> "1 Eintrag"
                    else -> "${kind.entries} Einträge"
                },
                style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
                color = f.textSecondary,
            )
        }
        MhSymbolButton(R.drawable.ic_mh_edit, "${kind.name} umbenennen", onUmbenennen)
        if (kind.entries > 0) {
            MhSymbolButton(
                R.drawable.ic_mh_lock,
                "${kind.name} löschen nicht möglich, hat Einträge",
                onGesperrt,
                farbe = f.textTertiary,
            )
        } else {
            MhLoeschenButton("Löschen", onLoeschen)
        }
    }
}
