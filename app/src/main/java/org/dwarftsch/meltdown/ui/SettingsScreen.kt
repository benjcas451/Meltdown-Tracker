package org.dwarftsch.meltdown.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dwarftsch.meltdown.R
import org.dwarftsch.meltdown.data.AppSettings
import org.dwarftsch.meltdown.data.CertSource
import org.dwarftsch.meltdown.data.DataSourceMode
import org.dwarftsch.meltdown.data.LocalBackupService
import org.dwarftsch.meltdown.data.LocalService
import org.dwarftsch.meltdown.data.createConfiguredService
import org.dwarftsch.meltdown.data.meldung

/** Beschreibung der REST-API (Dialog „Aufbau API“). */
private const val API_INFO_TEXT = """
Die App spricht meltdown-tracker/api.php des Webservers an. Als Adresse genügt der Ordner (…/meltdown-tracker/), "api.php" ergänzt die App selbst. Alle Antworten sind JSON.

Lesen (GET):
• ?action=children – Kinder mit Anzahl der Einträge
• ?action=entries&child_id=&limit=&offset= – Verlauf, neueste zuerst
• ?action=entry&id= – ein Eintrag

Schreiben (POST, JSON-Objekt):
• ?action=child_create {name}
• ?action=child_rename {id, name}
• ?action=child_delete {id} – 409, solange das Kind Einträge hat
• ?action=entry_create {child_id, intensity, create_time?, trigger?}
• ?action=entry_update {id, child_id, create_time, intensity, trigger}
• ?action=entry_delete {id}

Ein Eintrag hat die Felder id, child_id, child_name, create_time (UTC), create_time_local (Europe/Berlin), intensity (Ganzzahl 1 = mild bis 10 = maximal) und trigger (optionaler Auslöser, höchstens 200 Zeichen). Zeitpunkte schickt die App immer mit Zeitzonen-Offset; ohne Angabe setzt der Server die aktuelle Uhrzeit. Fehler kommen als {"error": "..."} mit passendem HTTP-Status.

Authentifizierung je nach Datenquelle:
• Server (mTLS): Client-Zertifikat (client.crt + client.key), optional zusätzlich "X-API-Key: <Key>"
• Server (Cloudflare Access): "CF-Access-Client-Id" und "CF-Access-Client-Secret", optional zusätzlich "X-API-Key: <Key>"
"""

/** Beschreibung der lokalen Datenbank (Dialog „Aufbau Datenbank“). */
private const val DB_INFO_TEXT = """
Im Modus "Lokal (SQLite)" speichert die App alles in meltdown.db im app-privaten Speicher. Andere Apps haben keinen Zugriff, es findet keine Synchronisation statt.

Tabelle "children":
• id – INTEGER, Primärschlüssel
• name – TEXT, eindeutig (ohne Groß-/Kleinschreibung), 1–50 Zeichen

Tabelle "entries":
• id – INTEGER, Primärschlüssel
• child_id – Verweis auf children; ein Kind mit Einträgen lässt sich nicht löschen
• create_time – TEXT, ISO 8601 in UTC (chronologisch sortierbar), Anzeige in lokaler Zeit
• intensity – INTEGER, 1 (mild) bis 10 (maximal)
• trigger_text – TEXT, optionaler Auslöser, höchstens 200 Zeichen

Das entspricht dem Schema des Webservers.

Sicherung & Gerätewechsel

• Cloud-Backup (Google-Konto): nur die lokale Datenbank. Die Einstellungen bleiben außen vor, weil dort API-Key und Service Token stehen.
• Direkter Gerätewechsel: zusätzlich die Einstellungen; die Übertragung läuft Ende-zu-Ende-verschlüsselt zwischen den Geräten.

Den Zertifikats-Ordner für mTLS muss man in beiden Fällen neu auswählen – die Leseberechtigung gilt nur auf dem Gerät, auf dem sie erteilt wurde.

Der Offline-Zwischenspeicher der Server-Modi wird nicht gesichert. Noch nicht übertragene Einträge gehen bei einer Wiederherstellung verloren.

Davon unabhängig gibt es im lokalen Modus das manuelle JSON-Backup (Import bis 16 MB).
"""

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    certSource: CertSource,
    meldungen: SnackbarHostState,
    onZurueck: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(settings.mode) }
    var mtlsUrl by remember { mutableStateOf(settings.mtlsUrl) }
    var mtlsApiKey by remember { mutableStateOf(settings.mtlsApiKey) }
    var cloudflareUrl by remember { mutableStateOf(settings.cloudflareUrl) }
    var cloudflareApiKey by remember { mutableStateOf(settings.cloudflareApiKey) }
    var cfClientId by remember { mutableStateOf(settings.cfAccessClientId) }
    var cfClientSecret by remember { mutableStateOf(settings.cfAccessClientSecret) }
    var certsOk by remember { mutableStateOf(false) }
    var beschaeftigt by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restoreBestaetigen by remember { mutableStateOf(false) }

    fun zeige(text: String) {
        scope.launch { meldungen.showSnackbar(text) }
    }

    suspend fun pruefeZertifikate(): Boolean = runCatching { certSource.readCredentials() }.isSuccess

    LaunchedEffect(Unit) { certsOk = pruefeZertifikate() }

    /** Ruft mit den aktuellen Einstellungen die Kinder ab – ohne Offline-Hülle. */
    fun verbindungTesten() {
        beschaeftigt = true
        scope.launch {
            val dienst = createConfiguredService(context, settings, certSource, offlineFaehig = false)
            runCatching { dienst.getChildren() }.fold(
                onSuccess = { kinder ->
                    zeige(
                        when (kinder.size) {
                            0 -> "Verbindung steht, noch keine Kinder angelegt"
                            1 -> "Verbindung steht, 1 Kind"
                            else -> "Verbindung steht, ${kinder.size} Kinder"
                        },
                    )
                },
                onFailure = { zeige("Verbindung fehlgeschlagen: ${it.meldung()}") },
            )
            dienst.dispose()
            beschaeftigt = false
        }
    }

    // Ordner-Auswahl für die mTLS-Zertifikate (Storage Access Framework).
    val ordnerWahl = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { certSource.uebernehmeOrdner(uri) }
                .onFailure { zeige("Fehler bei der Ordnerauswahl: ${it.meldung()}") }
            scope.launch {
                certsOk = pruefeZertifikate()
                zeige(if (certsOk) "Zertifikate gefunden" else "Keine Zertifikate gefunden")
            }
        }
    }

    // Backup-Export: Ziel-Datei über den System-Speichern-Dialog wählen.
    val backupSpeichern = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val lokal = LocalService(context)
                    val (kinder, eintraege) = try { lokal.exportiere() } finally { lokal.dispose() }
                    val json = LocalBackupService.exportJson(kinder, eintraege)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                            ?: error("Datei ließ sich nicht schreiben.")
                    }
                    eintraege.size
                }.fold(
                    onSuccess = { zeige("Backup gespeichert ($it Einträge)") },
                    onFailure = { zeige("Backup fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    // Backup-Import: Datei wählen, vollständig prüfen, dann Bestand ersetzen.
    val backupOeffnen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { LocalBackupService.leseBegrenzt(it) }
                            ?: error("Datei ließ sich nicht lesen.")
                    }
                    val (kinder, eintraege) = LocalBackupService.parseUndValidiere(text)
                    val lokal = LocalService(context)
                    try { lokal.ersetzeAlles(kinder, eintraege) } finally { lokal.dispose() }
                    kinder.size to eintraege.size
                }.fold(
                    onSuccess = { (k, e) -> zeige("Wiederhergestellt: $k Kinder, $e Einträge") },
                    onFailure = { zeige("Wiederherstellung fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }


    Scaffold(
        topBar = { MhKopf(titel = "Einstellungen", onZurueck = onZurueck, zurueckText = "Verlauf") },
        snackbarHost = { SnackbarHost(meldungen) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        val f = Mh.farben
        Column(
            modifier = Modifier
                .padding(innenAbstand)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Mh.rand)
                .padding(bottom = Mh.abstand8),
        ) {
            MhGruppenLabel("Datenquelle", Modifier.padding(top = 0.dp))
            Column(Modifier.selectableGroup()) {
                ModusZeile(
                    gewaehlt = mode == DataSourceMode.MTLS,
                    titel = "Server (mTLS)",
                    untertitel = "Synchronisation per Client-Zertifikat",
                ) { mode = DataSourceMode.MTLS; settings.mode = mode }
                ModusZeile(
                    gewaehlt = mode == DataSourceMode.CLOUDFLARE,
                    titel = "Server (Cloudflare Access)",
                    untertitel = "Zugang per Service Token",
                ) { mode = DataSourceMode.CLOUDFLARE; settings.mode = mode }
                ModusZeile(
                    gewaehlt = mode == DataSourceMode.LOKAL,
                    titel = "Lokal (SQLite)",
                    untertitel = "Einträge bleiben nur auf diesem Gerät",
                ) { mode = DataSourceMode.LOKAL; settings.mode = mode }
            }

            if (mode == DataSourceMode.MTLS) {
                MhGruppenLabel("Server (mTLS)", Modifier.padding(top = Mh.abstand8))
                Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand5)) {
                    UrlFeld(wert = mtlsUrl, onAenderung = {
                        mtlsUrl = it
                        settings.mtlsUrl = it
                    })
                    // Zusätzlich zum Client-Zertifikat: für Server, die beides
                    // prüfen. Leer lassen = nur mTLS.
                    GeheimFeld(
                        wert = mtlsApiKey,
                        hinweis = "Nur nötig, wenn der Server zusätzlich zum Zertifikat einen Key erwartet. " +
                            "Leer lassen für reines mTLS.",
                        onAenderung = {
                            mtlsApiKey = it
                            settings.mtlsApiKey = it
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Mh.abstand5, bottom = Mh.abstand3),
                    verticalAlignment = Alignment.Top,
                ) {
                    MhSymbol(
                        if (certsOk) R.drawable.ic_mh_check else R.drawable.ic_mh_alert,
                        null,
                        Modifier.padding(top = 2.dp),
                        farbe = if (certsOk) f.successFg else f.errorFg,
                    )
                    Spacer(Modifier.width(Mh.abstand3))
                    Column {
                        Text(
                            if (certsOk) "Zertifikate gefunden" else "Keine Zertifikate gefunden",
                            style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
                            color = f.textStrong,
                        )
                        Text(
                            certSource.locationLabel?.let { "Ordner: $it" }
                                ?: "Ordner mit ${CertSource.CERT_FILE_NAME} und ${CertSource.KEY_FILE_NAME} wählen",
                            style = MhTypo.small,
                            color = f.textSecondary,
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Mh.abstand2),
                    verticalArrangement = Arrangement.spacedBy(Mh.abstand2),
                ) {
                    MhZweitButton("Zertifikats-Ordner wählen", { ordnerWahl.launch(null) }, symbol = R.drawable.ic_mh_folder)
                    MhTextButton(
                        "Erneut prüfen",
                        {
                            scope.launch {
                                certsOk = pruefeZertifikate()
                                zeige(if (certsOk) "Zertifikate gefunden" else "Keine Zertifikate gefunden")
                            }
                        },
                        symbol = R.drawable.ic_mh_refresh,
                    )
                }
            }

            // Cloudflare Access prüft das Service Token am Rand und reicht die
            // Anfrage erst danach an den Server weiter; der Meltdown-Tracker
            // verlangt dahinter in der Regel zusätzlich seinen eigenen Key.
            if (mode == DataSourceMode.CLOUDFLARE) {
                MhGruppenLabel("Server (Cloudflare Access)", Modifier.padding(top = Mh.abstand8))
                Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand5)) {
                    UrlFeld(wert = cloudflareUrl, onAenderung = {
                        cloudflareUrl = it
                        settings.cloudflareUrl = it
                    })
                    GeheimFeld(
                        wert = cfClientId,
                        titel = "Client-ID",
                        hinweis = "Client-ID des Service Tokens, endet üblicherweise auf „.access“.",
                        onAenderung = {
                            cfClientId = it
                            settings.cfAccessClientId = it
                        },
                    )
                    GeheimFeld(
                        wert = cfClientSecret,
                        titel = "Client-Secret",
                        hinweis = "Beide Teile nötig. Service Tokens laufen ab, standardmäßig nach einem Jahr.",
                        onAenderung = {
                            cfClientSecret = it
                            settings.cfAccessClientSecret = it
                        },
                    )
                    GeheimFeld(
                        wert = cloudflareApiKey,
                        hinweis = "API-Key des Meltdown-Trackers dieser Familie, falls der Server ihn hinter " +
                            "Cloudflare zusätzlich verlangt.",
                        onAenderung = {
                            cloudflareApiKey = it
                            settings.cloudflareApiKey = it
                        },
                    )
                }
            }

            if (mode != DataSourceMode.LOKAL) {
                Spacer(Modifier.height(Mh.abstand5))
                MhZweitButton(
                    if (beschaeftigt) "Bitte warten …" else "Verbindung testen",
                    ::verbindungTesten,
                    enabled = !beschaeftigt,
                    symbol = R.drawable.ic_mh_cloud,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (mode == DataSourceMode.LOKAL) {
                MhGruppenLabel("Backup", Modifier.padding(top = Mh.abstand8))
                Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand2)) {
                    MhZweitButton(
                        if (beschaeftigt) "Bitte warten …" else "Backup speichern",
                        { backupSpeichern.launch(LocalBackupService.dateiname()) },
                        enabled = !beschaeftigt,
                        symbol = R.drawable.ic_mh_save,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MhZweitButton(
                        "Backup wiederherstellen",
                        { restoreBestaetigen = true },
                        enabled = !beschaeftigt,
                        symbol = R.drawable.ic_mh_undo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Im Dateidialog z. B. Google Drive als Ziel wählen.",
                    style = MhTypo.small,
                    color = f.textSecondary,
                    modifier = Modifier.padding(top = Mh.abstand2),
                )
            }

            MhGruppenLabel("Erklärung", Modifier.padding(top = Mh.abstand8))
            MhListenZeile(
                "Aufbau API",
                symbol = R.drawable.ic_mh_cloud,
                pfeil = true,
                onClick = { infoDialog = "Aufbau API" to API_INFO_TEXT.trim() },
            )
            MhTrennlinie()
            MhListenZeile(
                "Aufbau Datenbank",
                symbol = R.drawable.ic_mh_file,
                pfeil = true,
                onClick = { infoDialog = "Aufbau Datenbank" to DB_INFO_TEXT.trim() },
            )
            Text(
                "Version ${appVersion(context)}",
                style = MhTypo.small,
                color = f.textTertiary,
                modifier = Modifier.padding(top = Mh.abstand6),
            )
        }
    }

    infoDialog?.let { (titel, text) -> InfoDialog(titel = titel, text = text, onSchliessen = { infoDialog = null }) }

    if (restoreBestaetigen) {
        FrageDialog(
            titel = "Backup wiederherstellen?",
            text = "Alle lokal gespeicherten Kinder und Einträge werden durch den Inhalt des Backups " +
                "ersetzt. Das lässt sich nicht rückgängig machen.",
            aktion = "Backup auswählen",
            onAbbrechen = { restoreBestaetigen = false },
            onBestaetigen = {
                restoreBestaetigen = false
                backupOeffnen.launch(arrayOf("application/json"))
            },
        )
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** Auswahlzeile mit Optionsfeld: Titel, Nebentext, ganze Zeile tippbar. */
@Composable
private fun ModusZeile(gewaehlt: Boolean, titel: String, untertitel: String, onWahl: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = gewaehlt, role = Role.RadioButton, onClick = onWahl)
            .padding(vertical = Mh.abstand2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = gewaehlt,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = Mh.farben.primary,
                unselectedColor = Mh.farben.borderStrong,
            ),
        )
        Spacer(Modifier.width(Mh.abstand3))
        Column {
            Text(titel, style = MhTypo.body.copy(fontWeight = FontWeight.Medium), color = Mh.farben.textStrong)
            Text(untertitel, style = MhTypo.small, color = Mh.farben.textSecondary)
        }
    }
}

/** Verdecktes Eingabefeld (API-Key, Token) mit Auge zum Aufdecken. */
@Composable
private fun GeheimFeld(
    wert: String,
    hinweis: String,
    onAenderung: (String) -> Unit,
    titel: String = "API-Key",
) {
    var sichtbar by remember { mutableStateOf(false) }
    MhFeld(titel, zusatz = if (titel == "API-Key") "optional" else null, hinweis = hinweis) {
        OutlinedTextField(
            value = wert,
            onValueChange = onAenderung,
            textStyle = MhTypo.body,
            shape = MhFeldForm,
            colors = mhFeldFarben(),
            singleLine = true,
            visualTransformation = if (sichtbar) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            trailingIcon = {
                MhSymbolButton(
                    if (sichtbar) R.drawable.ic_mh_eye_off else R.drawable.ic_mh_eye,
                    if (sichtbar) "Verbergen" else "Anzeigen",
                    { sichtbar = !sichtbar },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UrlFeld(wert: String, onAenderung: (String) -> Unit) {
    MhFeld("API-URL", hinweis = "Ordner des Meltdown-Trackers; „api.php“ ergänzt die App.") {
        OutlinedTextField(
            value = wert,
            onValueChange = onAenderung,
            textStyle = MhTypo.body,
            shape = MhFeldForm,
            colors = mhFeldFarben(),
            placeholder = { Text("https://server.example/meltdown-tracker/", style = MhTypo.body) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
