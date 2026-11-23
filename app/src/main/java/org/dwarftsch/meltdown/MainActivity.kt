package org.dwarftsch.meltdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.dwarftsch.meltdown.ui.EintragScreen
import org.dwarftsch.meltdown.ui.HomeScreen
import org.dwarftsch.meltdown.ui.HomeViewModel
import org.dwarftsch.meltdown.ui.KinderScreen
import org.dwarftsch.meltdown.ui.MhTheme
import org.dwarftsch.meltdown.ui.SettingsScreen

private enum class Ansicht { START, EINTRAG, KINDER, EINSTELLUNGEN }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MhTheme {
                val viewModel: HomeViewModel = viewModel()
                var ansicht by rememberSaveable { mutableStateOf(Ansicht.START) }
                /** Eintrag, der in der Ansicht „Eintrag bearbeiten“ offen ist. */
                var eintragId by rememberSaveable { mutableStateOf<Long?>(null) }

                // Eine Meldungsleiste für alle Ansichten: Bestätigungen wie
                // „Eintrag geändert“ kommen erst nach dem Zurückspringen an.
                val meldungen = remember { SnackbarHostState() }
                LaunchedEffect(viewModel) {
                    viewModel.meldungen.collect { meldungen.showSnackbar(it) }
                }
                val zumStart = { ansicht = Ansicht.START }
                // Die Startansicht behält Formular und Scrollstand, während
                // man Kinder, Einstellungen oder einen Eintrag bearbeitet.
                val zustaende = rememberSaveableStateHolder()

                when (ansicht) {
                    Ansicht.START -> zustaende.SaveableStateProvider("start") {
                        HomeScreen(
                            viewModel = viewModel,
                            meldungen = meldungen,
                            onEinstellungen = { ansicht = Ansicht.EINSTELLUNGEN },
                            onKinder = { ansicht = Ansicht.KINDER },
                            onBearbeiten = {
                                eintragId = it.id
                                ansicht = Ansicht.EINTRAG
                            },
                        )
                    }

                    Ansicht.EINTRAG -> {
                        BackHandler(onBack = zumStart)
                        val state by viewModel.uiState.collectAsState()
                        val eintrag = state.eintraege.firstOrNull { it.id == eintragId }
                        if (eintrag == null) {
                            // Inzwischen gelöscht oder Datenquelle gewechselt.
                            LaunchedEffect(Unit) { zumStart() }
                        } else {
                            EintragScreen(
                                viewModel = viewModel,
                                eintrag = eintrag,
                                kinder = state.kinder,
                                meldungen = meldungen,
                                onZurueck = zumStart,
                            )
                        }
                    }

                    Ansicht.KINDER -> {
                        BackHandler(onBack = zumStart)
                        KinderScreen(viewModel = viewModel, meldungen = meldungen, onZurueck = zumStart)
                    }

                    Ansicht.EINSTELLUNGEN -> {
                        // Beim Verlassen der Einstellungen die (womöglich
                        // neue) Datenquelle übernehmen.
                        val zurueck = {
                            ansicht = Ansicht.START
                            viewModel.datenquelleNeuAufbauen()
                        }
                        BackHandler(onBack = zurueck)
                        SettingsScreen(
                            settings = viewModel.settings,
                            certSource = viewModel.certSource,
                            meldungen = meldungen,
                            onZurueck = zurueck,
                        )
                    }
                }
            }
        }
    }
}
