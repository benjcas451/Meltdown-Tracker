package org.dwarftsch.meltdown.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Child

private sealed interface KindDialog {
    data object Neu : KindDialog
    data class Umbenennen(val kind: Child) : KindDialog
    data class Loeschen(val kind: Child) : KindDialog
}

/**
 * Kinder anlegen, umbenennen und löschen. Ein Kind mit Einträgen lässt sich
 * – wie im Web – nicht löschen: der Verlauf geht nie stillschweigend verloren.
 * In den Server-Modi geht das nur mit Verbindung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinderScreen(viewModel: HomeViewModel, onZurueck: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<KindDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kinder") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onZurueck) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innenAbstand ->
        LazyColumn(
            modifier = Modifier.padding(innenAbstand).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "neu") {
                Button(
                    onClick = { dialog = KindDialog.Neu },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Kind anlegen")
                }
                Spacer(Modifier.height(12.dp))
            }
            if (state.kinder.isEmpty() && !state.laedt) {
                item(key = "leer") {
                    Text(
                        "Noch keine Kinder angelegt.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(state.kinder, key = { it.id }) { kind ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(kind.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (kind.entries) {
                                    0 -> "Keine Einträge"
                                    1 -> "1 Eintrag"
                                    else -> "${kind.entries} Einträge"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { dialog = KindDialog.Umbenennen(kind) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "${kind.name} umbenennen", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                if (kind.entries > 0) {
                                    viewModel.hinweis(
                                        "${kind.name} hat noch Einträge im Verlauf und wird deshalb nicht gelöscht.",
                                    )
                                } else {
                                    dialog = KindDialog.Loeschen(kind)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "${kind.name} löschen",
                                modifier = Modifier.size(20.dp),
                                tint = if (kind.entries > 0) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val aktuell = dialog) {
        null -> Unit
        KindDialog.Neu -> NameDialog(
            titel = "Kind anlegen",
            initial = "",
            onAbbrechen = { dialog = null },
            onSpeichern = {
                dialog = null
                viewModel.kindAnlegen(it)
            },
            onUngueltig = viewModel::hinweis,
        )
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
