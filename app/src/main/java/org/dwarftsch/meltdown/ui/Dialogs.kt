package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.Grenzen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Erfassen bzw. Bearbeiten eines Eintrags: Kind, Zeitpunkt, Intensität 1–10
 * und optionaler Auslöser.
 *
 * Beim Erfassen ist „Jetzt“ vorgewählt – dann schickt die App keinen
 * Zeitpunkt mit und der Server setzt seine eigene Uhrzeit. Beim Bearbeiten
 * bleibt ein unveränderter Zeitpunkt sekundengenau erhalten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EintragDialog(
    kinder: List<Child>,
    /** null = neuer Eintrag. */
    bestehend: Entry?,
    vorauswahlKind: Long?,
    onAbbrechen: () -> Unit,
    onSpeichern: (EntryInput) -> Unit,
    onUngueltig: (String) -> Unit,
) {
    var kindId by remember { mutableStateOf(bestehend?.childId ?: vorauswahlKind) }
    var intensitaet by remember { mutableStateOf(bestehend?.intensity) }
    /** null = „Jetzt“ (nur beim Erfassen). */
    var zeit by remember { mutableStateOf(bestehend?.createTime) }
    var ausloeser by remember { mutableStateOf(bestehend?.trigger.orEmpty()) }
    var datumWahl by remember { mutableStateOf(false) }
    var zeitWahlFuer by remember { mutableStateOf<LocalDate?>(null) }

    fun speichern() {
        val kind = kindId?.takeIf { id -> kinder.any { it.id == id } }
            ?: return onUngueltig("Bitte ein Kind auswählen.")
        val wert = intensitaet ?: return onUngueltig("Bitte eine Intensität von 1 bis 10 wählen.")
        zeit?.let { z -> Grenzen.pruefeZeit(z).onFailure { return onUngueltig(it.message.orEmpty()) } }
        val text = Grenzen.pruefeAusloeser(ausloeser).getOrElse { return onUngueltig(it.message.orEmpty()) }
        onSpeichern(EntryInput(childId = kind, createTime = zeit, intensity = wert, trigger = text))
    }

    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(if (bestehend == null) "Meltdown erfassen" else "Eintrag bearbeiten") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Feldtitel("Kind")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinder.forEach { kind ->
                        FilterChip(
                            selected = kindId == kind.id,
                            onClick = { kindId = kind.id },
                            shape = RoundedCornerShape(50),
                            colors = chipFarben(),
                            label = { Text(kind.name) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Feldtitel("Zeitpunkt")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bestehend == null) {
                        FilterChip(
                            selected = zeit == null,
                            onClick = { zeit = null },
                            shape = RoundedCornerShape(50),
                            colors = chipFarben(),
                            label = { Text("Jetzt") },
                        )
                    }
                    FilterChip(
                        selected = zeit != null,
                        onClick = { datumWahl = true },
                        shape = RoundedCornerShape(50),
                        colors = chipFarben(),
                        leadingIcon = {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text(zeit?.let(::datumUhrzeit) ?: "Zeitpunkt wählen") },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Feldtitel("Intensität", "1 = mild · 10 = maximal")
                IntensitaetsAuswahl(wert = intensitaet, onWahl = { intensitaet = it })

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ausloeser,
                    onValueChange = { neu ->
                        // Hart begrenzen statt erst beim Speichern zu meckern;
                        // Zeilenumbrüche lehnt der Server ab.
                        val ohneUmbruch = neu.replace('\n', ' ')
                        if (ohneUmbruch.codePointCount(0, ohneUmbruch.length) <= Grenzen.TRIGGER_MAX) ausloeser = ohneUmbruch
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = mhEingabefeldFarben(),
                    label = { Text("Auslöser / Grund (optional)") },
                    placeholder = { Text("z. B. Übergang Spielplatz → Auto") },
                    supportingText = {
                        Text("${ausloeser.codePointCount(0, ausloeser.length)} / ${Grenzen.TRIGGER_MAX}")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = ::speichern, shape = MaterialTheme.shapes.medium) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )

    if (datumWahl) {
        DatumDialog(
            initial = (zeit ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalDate(),
            onAbbrechen = { datumWahl = false },
            onUebernehmen = { datum ->
                datumWahl = false
                zeitWahlFuer = datum
            },
        )
    }

    zeitWahlFuer?.let { datum ->
        ZeitDialog(
            initial = (zeit ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalTime(),
            onAbbrechen = { zeitWahlFuer = null },
            onUebernehmen = { uhrzeit ->
                zeitWahlFuer = null
                val gewaehlt = ZonedDateTime.of(datum, uhrzeit, ZoneId.systemDefault()).toInstant()
                if (gewaehlt > Instant.now().plusSeconds(Grenzen.ZUKUNFT_TOLERANZ_SEKUNDEN)) {
                    onUngueltig("Der Zeitpunkt liegt in der Zukunft.")
                } else {
                    zeit = gewaehlt
                }
            },
        )
    }
}

/** Zehn Felder in zwei Reihen; gewählt = Fläche der Intensitätsstufe. */
@Composable
fun IntensitaetsAuswahl(wert: Int?, onWahl: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (reihe in listOf(1..5, 6..10)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in reihe) {
                    val gewaehlt = wert == i
                    val beschreibung = when (i) {
                        1 -> "Intensität 1, mild"
                        10 -> "Intensität 10, maximal"
                        else -> "Intensität $i"
                    }
                    if (gewaehlt) {
                        Button(
                            onClick = { onWahl(i) },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = intensitaetFlaeche(i),
                                contentColor = intensitaetInhalt(i),
                            ),
                            modifier = Modifier.size(44.dp).semantics {
                                contentDescription = beschreibung
                                selected = true
                            },
                        ) { Text("$i") }
                    } else {
                        OutlinedButton(
                            onClick = { onWahl(i) },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(44.dp).semantics {
                                contentDescription = beschreibung
                                selected = false
                            },
                        ) { Text("$i") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Feldtitel(text: String, hinweis: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MinzeHonig.farben.sektionsTitel)
        if (hinweis != null) {
            Spacer(Modifier.width(8.dp))
            Text(hinweis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
fun chipFarben() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

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
        confirmButton = {
            Button(
                onClick = {
                    state.selectedDateMillis?.let {
                        onUebernehmen(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = state.selectedDateMillis != null,
                shape = MaterialTheme.shapes.medium,
            ) { Text("Weiter") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
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
    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text("Uhrzeit wählen") },
        icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
        text = { TimePicker(state = state) },
        confirmButton = {
            Button(
                onClick = { onUebernehmen(LocalTime.of(state.hour, state.minute)) },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Übernehmen") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )
}

/** Name eines Kindes anlegen bzw. ändern. */
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
    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(titel) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.codePointCount(0, it.length) <= Grenzen.NAME_MAX) name = it.replace('\n', ' ') },
                shape = MaterialTheme.shapes.medium,
                colors = mhEingabefeldFarben(),
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().focusRequester(fokus),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    Grenzen.pruefeName(name).fold(onSuccess = onSpeichern, onFailure = { onUngueltig(it.message.orEmpty()) })
                },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )
}

/** Bestätigung vor dem Löschen. */
@Composable
fun LoeschDialog(
    titel: String,
    text: String,
    onAbbrechen: () -> Unit,
    onLoeschen: () -> Unit,
) {
    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(titel) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onLoeschen,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Löschen") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )
}

/** Scrollbarer Info-Dialog (Aufbau API / Aufbau Datenbank). */
@Composable
fun InfoDialog(titel: String, text: String, onSchliessen: () -> Unit) {
    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onSchliessen,
        title = { Text(titel) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(text) }
        },
        confirmButton = {
            TextButton(
                onClick = onSchliessen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Schließen") }
        },
    )
}
