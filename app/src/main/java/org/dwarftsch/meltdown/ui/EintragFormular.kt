package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.Grenzen
import org.dwarftsch.meltdown.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Eingaben eines neuen bzw. geänderten Eintrags: Kind, Zeitpunkt,
 * Intensität 1–10 und optionaler Auslöser.
 *
 * [zeit] null heisst „Jetzt“ (nur beim Erfassen): dann schickt die App
 * keinen Zeitpunkt mit und der Server setzt seine eigene Uhrzeit. Beim
 * Bearbeiten bleibt ein unveränderter Zeitpunkt sekundengenau erhalten.
 */
@Stable
class EintragFormular(kindId: Long?, intensitaet: Int?, zeit: Instant?, ausloeser: String) {
    /** Ausdrücklich gewähltes Kind; null = Vorauswahl gilt. */
    var kindId by mutableStateOf(kindId)
    var intensitaet by mutableStateOf(intensitaet)
    var zeit by mutableStateOf(zeit)
    var ausloeser by mutableStateOf(ausloeser)

    /** Zurück auf leer: Vorauswahl, „Jetzt“, keine Intensität, kein Auslöser. */
    fun leeren() {
        kindId = null
        intensitaet = null
        zeit = null
        ausloeser = ""
    }

    /**
     * Prüft die Eingaben wie der Server. Bei einem Fehler meldet
     * [onUngueltig] den Grund und das Ergebnis ist null.
     */
    fun eingabe(kinder: List<Child>, vorauswahl: Long?, onUngueltig: (String) -> Unit): EntryInput? {
        val kind = (kindId ?: vorauswahl)?.takeIf { id -> kinder.any { it.id == id } }
        if (kind == null) {
            onUngueltig("Bitte ein Kind auswählen.")
            return null
        }
        val wert = intensitaet
        if (wert == null) {
            onUngueltig("Bitte eine Intensität von 1 bis 10 wählen.")
            return null
        }
        val z = zeit
        if (z != null) {
            Grenzen.pruefeZeit(z).onFailure {
                onUngueltig(it.message.orEmpty())
                return null
            }
        }
        val text = Grenzen.pruefeAusloeser(ausloeser).getOrElse {
            onUngueltig(it.message.orEmpty())
            return null
        }
        return EntryInput(childId = kind, createTime = z, intensity = wert, trigger = text)
    }

    companion object {
        /** Übersteht Drehen und Prozess-Neustart (Zeit sekundengenau mit Nanos). */
        val Saver = listSaver<EintragFormular, Any?>(
            save = { listOf(it.kindId, it.intensitaet, it.zeit?.epochSecond, it.zeit?.nano, it.ausloeser) },
            restore = { w ->
                EintragFormular(
                    kindId = w[0] as Long?,
                    intensitaet = w[1] as Int?,
                    zeit = (w[2] as Long?)?.let { Instant.ofEpochSecond(it, (w[3] as Int?)?.toLong() ?: 0L) },
                    ausloeser = w[4] as String,
                )
            },
        )
    }
}

/**
 * Felder des Eintrags wie im Web (`mt_entry_fields`): Kind und Intensität
 * als ruhige Auswahl, Zeitpunkt, Auslöser. [vorauswahl] gilt, solange kein
 * Kind ausdrücklich gewählt ist.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EintragFelder(
    formular: EintragFormular,
    kinder: List<Child>,
    vorauswahl: Long?,
    /** „Jetzt“ anbieten (nur beim Erfassen). */
    mitJetzt: Boolean,
    onUngueltig: (String) -> Unit,
) {
    var datumWahl by rememberSaveable { mutableStateOf(false) }
    var zeitWahlFuer by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val kindGewaehlt = formular.kindId ?: vorauswahl

    Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand5)) {
        MhFeld("Kind") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                kinder.forEach { kind ->
                    MhAuswahl(kind.name, gewaehlt = kindGewaehlt == kind.id, onClick = { formular.kindId = kind.id })
                }
            }
        }

        MhFeld("Zeitpunkt") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (mitJetzt) {
                    MhAuswahl("Jetzt", gewaehlt = formular.zeit == null, onClick = { formular.zeit = null })
                }
                MhAuswahl(
                    formular.zeit?.let(::datumUhrzeit) ?: "Anderer Zeitpunkt",
                    gewaehlt = formular.zeit != null,
                    onClick = { datumWahl = true },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Intensität", style = MhTypo.label, color = Mh.farben.textStrong, modifier = Modifier.weight(1f))
                Text("1 mild · 10 maximal", style = MhTypo.small, color = Mh.farben.textSecondary)
            }
            IntensitaetsAuswahl(wert = formular.intensitaet, onWahl = { formular.intensitaet = it })
        }

        val zeichen = formular.ausloeser.codePointCount(0, formular.ausloeser.length)
        MhFeld("Auslöser oder Grund", zusatz = "optional", hinweis = "$zeichen / ${Grenzen.TRIGGER_MAX} Zeichen") {
            OutlinedTextField(
                value = formular.ausloeser,
                onValueChange = { neu ->
                    // Hart begrenzen statt erst beim Speichern zu meckern;
                    // Zeilenumbrüche lehnt der Server ab.
                    val ohneUmbruch = neu.replace('\n', ' ')
                    if (ohneUmbruch.codePointCount(0, ohneUmbruch.length) <= Grenzen.TRIGGER_MAX) {
                        formular.ausloeser = ohneUmbruch
                    }
                },
                textStyle = MhTypo.body,
                placeholder = { Text("z. B. Übergang Spielplatz → Auto", style = MhTypo.body) },
                shape = MhFeldForm,
                colors = mhFeldFarben(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (datumWahl) {
        DatumDialog(
            initial = (formular.zeit ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalDate(),
            onAbbrechen = { datumWahl = false },
            onUebernehmen = { datum ->
                datumWahl = false
                zeitWahlFuer = datum
            },
        )
    }

    zeitWahlFuer?.let { datum ->
        ZeitDialog(
            initial = (formular.zeit ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalTime(),
            onAbbrechen = { zeitWahlFuer = null },
            onUebernehmen = { uhrzeit ->
                zeitWahlFuer = null
                val gewaehlt = ZonedDateTime.of(datum, uhrzeit, ZoneId.systemDefault()).toInstant()
                if (gewaehlt > Instant.now().plusSeconds(Grenzen.ZUKUNFT_TOLERANZ_SEKUNDEN)) {
                    onUngueltig("Der Zeitpunkt liegt in der Zukunft.")
                } else {
                    formular.zeit = gewaehlt
                }
            },
        )
    }
}

/** Intensität 1–10 als ruhige Auswahl in zwei Reihen (wie im Web auf dem Handy). */
@Composable
fun IntensitaetsAuswahl(wert: Int?, onWahl: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (reihe in listOf(1..5, 6..10)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in reihe) {
                    val beschreibung = when (i) {
                        1 -> "Intensität 1, mild"
                        10 -> "Intensität 10, maximal"
                        else -> "Intensität $i"
                    }
                    MhAuswahl(
                        "$i",
                        gewaehlt = wert == i,
                        onClick = { onWahl(i) },
                        modifier = Modifier
                            .weight(1f)
                            .height(Mh.touch)
                            .semantics { contentDescription = beschreibung },
                    )
                }
            }
        }
    }
}

/**
 * Eintrag bearbeiten (wie `eintrag.php` im Web): dieselben Felder, darunter
 * die Hauptaktion „Änderungen speichern“ und abgesetzt „Eintrag löschen“.
 */
@Composable
fun EintragScreen(
    viewModel: HomeViewModel,
    eintrag: Entry,
    kinder: List<Child>,
    meldungen: SnackbarHostState,
    onZurueck: () -> Unit,
) {
    val formular = rememberSaveable(eintrag.id, saver = EintragFormular.Saver) {
        EintragFormular(eintrag.childId, eintrag.intensity, eintrag.createTime, eintrag.trigger.orEmpty())
    }
    var loeschen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { MhKopf(titel = "Eintrag bearbeiten", onZurueck = onZurueck, zurueckText = "Verlauf") },
        snackbarHost = { SnackbarHost(meldungen) },
        containerColor = Mh.farben.bg,
    ) { innen ->
        Column(
            Modifier
                .padding(innen)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Mh.rand)
                .padding(top = Mh.abstand2, bottom = Mh.abstand8),
        ) {
            EintragFelder(
                formular = formular,
                kinder = kinder,
                vorauswahl = null,
                mitJetzt = false,
                onUngueltig = viewModel::hinweis,
            )
            Spacer(Modifier.height(Mh.abstand6))
            MhHauptButton(
                "Änderungen speichern",
                onClick = {
                    formular.eingabe(kinder, null, viewModel::hinweis)?.let {
                        viewModel.aendern(eintrag, it)
                        onZurueck()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Mh.abstand8))
            MhTrennlinie()
            Spacer(Modifier.height(Mh.abstand3))
            MhLoeschenButton("Eintrag löschen", onClick = { loeschen = true }, symbol = R.drawable.ic_mh_trash)
        }
    }

    if (loeschen) {
        LoeschDialog(
            titel = "Eintrag löschen?",
            text = "${eintrag.childName}, ${datumUhrzeit(eintrag.createTime)}, Intensität ${eintrag.intensity}",
            onAbbrechen = { loeschen = false },
            onLoeschen = {
                loeschen = false
                viewModel.loeschen(eintrag)
                onZurueck()
            },
        )
    }
}
