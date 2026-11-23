package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.dwarftsch.meltdown.Grenzen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// Dialoge im Stil von Minze & Honig 2.0: neutrale, schwebende Fläche
// (surfaceRaised), Radius 20, Aktionen als Text – Löschen in Rot.

/** Gemeinsamer Rahmen der Dialoge. */
@Composable
private fun MhDialog(
    titel: String,
    onDismiss: () -> Unit,
    bestaetigen: @Composable () -> Unit,
    abbrechen: (@Composable () -> Unit)? = null,
    inhalt: @Composable () -> Unit,
) {
    val f = Mh.farben
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Mh.radiusXl),
        containerColor = f.surfaceRaised,
        titleContentColor = f.textStrong,
        textContentColor = f.text,
        title = { Text(titel, style = MhTypo.h2) },
        text = inhalt,
        confirmButton = bestaetigen,
        dismissButton = abbrechen,
    )
}

/** Datumsauswahl; künftige Tage und Jahre vor 2000 sind gesperrt (wie der Server). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatumDialog(
    initial: LocalDate,
    onAbbrechen: () -> Unit,
    onUebernehmen: (LocalDate) -> Unit,
) {
    // Der DatePicker rechnet in UTC-Mitternacht; die Umrechnung bleibt damit
    // unabhängig von der Zeitzone des Geräts.
    val heute = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 2000..heute.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= heute

            override fun isSelectableYear(year: Int): Boolean = year in 2000..heute.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onAbbrechen,
        shape = RoundedCornerShape(Mh.radiusXl),
        confirmButton = {
            MhTextButton(
                "Weiter",
                onClick = {
                    state.selectedDateMillis?.let {
                        onUebernehmen(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = state.selectedDateMillis != null,
            )
        },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen) },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeitDialog(
    initial: LocalTime,
    onAbbrechen: () -> Unit,
    onUebernehmen: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    MhDialog(
        titel = "Uhrzeit wählen",
        onDismiss = onAbbrechen,
        bestaetigen = { MhTextButton("Übernehmen", { onUebernehmen(LocalTime.of(state.hour, state.minute)) }) },
        abbrechen = { MhTextButton("Abbrechen", onAbbrechen) },
    ) {
        TimePicker(state = state)
    }
}

/** Name eines Kindes ändern. */
@Composable
fun NameDialog(
    titel: String,
    initial: String,
    onAbbrechen: () -> Unit,
    onSpeichern: (String) -> Unit,
    onUngueltig: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    // Tastatur sofort offen: Namen tippt man immer, kein Extra-Tipp ins Feld.
    val fokus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fokus.requestFocus() }
    MhDialog(
        titel = titel,
        onDismiss = onAbbrechen,
        bestaetigen = {
            MhTextButton("Speichern", {
                Grenzen.pruefeName(name).fold(onSuccess = onSpeichern, onFailure = { onUngueltig(it.message.orEmpty()) })
            })
        },
        abbrechen = { MhTextButton("Abbrechen", onAbbrechen) },
    ) {
        MhFeld("Name") {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.codePointCount(0, it.length) <= Grenzen.NAME_MAX) name = it.replace('\n', ' ') },
                textStyle = MhTypo.body,
                singleLine = true,
                shape = MhFeldForm,
                colors = mhFeldFarben(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().focusRequester(fokus),
            )
        }
    }
}

/** Bestätigung vor dem Löschen. */
@Composable
fun LoeschDialog(
    titel: String,
    text: String,
    onAbbrechen: () -> Unit,
    onLoeschen: () -> Unit,
) {
    MhDialog(
        titel = titel,
        onDismiss = onAbbrechen,
        bestaetigen = { MhLoeschenButton("Löschen", onLoeschen) },
        abbrechen = { MhTextButton("Abbrechen", onAbbrechen) },
    ) {
        Text(text, style = MhTypo.body)
    }
}

/** Rückfrage mit einer bestätigenden Aktion (z. B. Backup wiederherstellen). */
@Composable
fun FrageDialog(
    titel: String,
    text: String,
    aktion: String,
    onAbbrechen: () -> Unit,
    onBestaetigen: () -> Unit,
) {
    MhDialog(
        titel = titel,
        onDismiss = onAbbrechen,
        bestaetigen = { MhTextButton(aktion, onBestaetigen) },
        abbrechen = { MhTextButton("Abbrechen", onAbbrechen) },
    ) {
        Text(text, style = MhTypo.body)
    }
}

/** Scrollbarer Info-Dialog (Aufbau API / Aufbau Datenbank). */
@Composable
fun InfoDialog(titel: String, text: String, onSchliessen: () -> Unit) {
    MhDialog(
        titel = titel,
        onDismiss = onSchliessen,
        bestaetigen = { MhTextButton("Schließen", onSchliessen) },
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MhTypo.small) }
    }
}
