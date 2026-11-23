package org.dwarftsch.meltdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.dwarftsch.meltdown.ui.HomeScreen
import org.dwarftsch.meltdown.ui.HomeViewModel
import org.dwarftsch.meltdown.ui.KinderScreen
import org.dwarftsch.meltdown.ui.MeltdownTheme
import org.dwarftsch.meltdown.ui.SettingsScreen

private enum class Ansicht { START, KINDER, EINSTELLUNGEN }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeltdownTheme {
                val viewModel: HomeViewModel = viewModel()
                var ansicht by rememberSaveable { mutableStateOf(Ansicht.START) }

                when (ansicht) {
                    Ansicht.START -> HomeScreen(
                        viewModel = viewModel,
                        onEinstellungen = { ansicht = Ansicht.EINSTELLUNGEN },
                        onKinder = { ansicht = Ansicht.KINDER },
                    )

                    Ansicht.KINDER -> {
                        BackHandler { ansicht = Ansicht.START }
                        KinderScreen(viewModel = viewModel, onZurueck = { ansicht = Ansicht.START })
                    }

                    Ansicht.EINSTELLUNGEN -> {
                        // Beim Verlassen der Einstellungen die (womöglich
                        // neue) Datenquelle übernehmen.
                        val zurueck = {
                            ansicht = Ansicht.START
                            viewModel.datenquelleNeuAufbauen()
                        }
                        BackHandler(onBack = zurueck)
                        SettingsScreen(settings = viewModel.settings, certSource = viewModel.certSource, onZurueck = zurueck)
                    }
                }
            }
        }
    }
}
