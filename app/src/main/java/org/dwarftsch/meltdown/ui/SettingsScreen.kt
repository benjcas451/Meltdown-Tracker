package org.dwarftsch.meltdown.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    certSource: CertSource,
    onZurueck: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

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
        scope.launch { snackbar.showSnackbar(text) }
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
                            0 -> "Verbindung ok – noch keine Kinder angelegt."
                            1 -> "Verbindung ok – 1 Kind."
                            else -> "Verbindung ok – ${kinder.size} Kinder."
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
                zeige(if (certsOk) "Zertifikate gefunden." else "Keine Zertifikate gefunden.")
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
                    onSuccess = { zeige("Backup gespeichert ($it Einträge).") },
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
                    onSuccess = { (k, e) -> zeige("Wiederhergestellt: $k Kinder, $e Einträge.") },
                    onFailure = { zeige("Wiederherstellung fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
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
        Column(
            modifier = Modifier
                .padding(innenAbstand)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Abschnitt("Datenquelle")
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

            if (mode == DataSourceMode.MTLS) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Server (mTLS)")
                UrlFeld(wert = mtlsUrl, onAenderung = {
                    mtlsUrl = it
                    settings.mtlsUrl = it
                })
                Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (certsOk) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = if (certsOk) MinzeHonig.farben.erfolg else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (certsOk) "Zertifikate gefunden" else "Keine Zertifikate gefunden")
                        Text(
                            certSource.locationLabel?.let { "Ordner: $it" }
                                ?: "Ordner mit ${CertSource.CERT_FILE_NAME} und ${CertSource.KEY_FILE_NAME} wählen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { ordnerWahl.launch(null) }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zertifikats-Ordner wählen")
                    }
                    OutlinedButton(
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                        onClick = {
                            scope.launch {
                                certsOk = pruefeZertifikate()
                                zeige(if (certsOk) "Zertifikate gefunden." else "Keine Zertifikate gefunden.")
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Erneut prüfen")
                    }
                }
            }

            // Cloudflare Access prüft das Service Token am Rand und reicht die
            // Anfrage erst danach an den Server weiter; der Meltdown-Tracker
            // verlangt dahinter in der Regel zusätzlich seinen eigenen Key.
            if (mode == DataSourceMode.CLOUDFLARE) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Server (Cloudflare Access)")
                UrlFeld(wert = cloudflareUrl, onAenderung = {
                    cloudflareUrl = it
                    settings.cloudflareUrl = it
                })
                Spacer(Modifier.height(16.dp))
                GeheimFeld(
                    wert = cfClientId,
                    titel = "Client-ID",
                    hinweis = "Client-ID des Service Tokens, endet üblicherweise auf „.access“.",
                    onAenderung = {
                        cfClientId = it
                        settings.cfAccessClientId = it
                    },
                )
                Spacer(Modifier.height(16.dp))
                GeheimFeld(
                    wert = cfClientSecret,
                    titel = "Client-Secret",
                    hinweis = "Beide Teile nötig. Service Tokens laufen ab, standardmäßig nach einem Jahr.",
                    onAenderung = {
                        cfClientSecret = it
                        settings.cfAccessClientSecret = it
                    },
                )
                Spacer(Modifier.height(16.dp))
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

            if (mode != DataSourceMode.LOKAL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = ::verbindungTesten,
                    enabled = !beschaeftigt,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (beschaeftigt) "Bitte warten …" else "Verbindung testen")
                }
            }

            if (mode == DataSourceMode.LOKAL) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Backup")
                Button(
                    onClick = { backupSpeichern.launch(LocalBackupService.dateiname()) },
                    enabled = !beschaeftigt,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (beschaeftigt) "Bitte warten …" else "Backup speichern")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { restoreBestaetigen = true },
                    enabled = !beschaeftigt,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Backup wiederherstellen")
                }
                Text(
                    "Im Dateidialog z. B. Google Drive als Ziel wählen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Abschnitt("Erklärung")
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                    onClick = { infoDialog = "Aufbau API" to API_INFO_TEXT.trim() },
                ) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufbau API")
                }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                    onClick = { infoDialog = "Aufbau Datenbank" to DB_INFO_TEXT.trim() },
                ) {
                    Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufbau Datenbank")
                }
            }
            Text(
                "Version ${appVersion(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    infoDialog?.let { (titel, text) -> InfoDialog(titel = titel, text = text, onSchliessen = { infoDialog = null }) }

    if (restoreBestaetigen) {
        AlertDialog(
            shape = MaterialTheme.shapes.extraLarge,
            onDismissRequest = { restoreBestaetigen = false },
            title = { Text("Backup wiederherstellen?") },
            text = {
                Text(
                    "Alle lokal gespeicherten Kinder und Einträge werden durch den Inhalt des Backups " +
                        "ersetzt. Das lässt sich nicht rückgängig machen.",
                )
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        restoreBestaetigen = false
                        backupOeffnen.launch(arrayOf("application/json"))
                    },
                ) { Text("Backup auswählen") }
            },
            dismissButton = {
                TextButton(
                    onClick = { restoreBestaetigen = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
                ) { Text("Abbrechen") }
            },
        )
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

@Composable
private fun Abschnitt(titel: String) {
    Text(
        titel,
        style = MaterialTheme.typography.titleSmall,
        color = MinzeHonig.farben.sektionsTitel,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModusZeile(gewaehlt: Boolean, titel: String, untertitel: String, onWahl: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onWahl)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = gewaehlt,
            onClick = onWahl,
            colors = RadioButtonDefaults.colors(selectedColor = MinzeHonig.farben.gruenText),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(titel)
            Text(untertitel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Verdecktes Eingabefeld (API-Key, Token) mit Auge zum Aufdecken. */
@Composable
private fun GeheimFeld(
    wert: String,
    hinweis: String,
    onAenderung: (String) -> Unit,
    titel: String = "API-Key (optional)",
) {
    var sichtbar by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = wert,
        onValueChange = onAenderung,
        shape = MaterialTheme.shapes.medium,
        colors = mhEingabefeldFarben(),
        label = { Text(titel) },
        supportingText = { Text(hinweis) },
        singleLine = true,
        visualTransformation = if (sichtbar) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { sichtbar = !sichtbar }) {
                Icon(
                    if (sichtbar) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (sichtbar) "Verbergen" else "Anzeigen",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun UrlFeld(wert: String, onAenderung: (String) -> Unit) {
    OutlinedTextField(
        value = wert,
        onValueChange = onAenderung,
        shape = MaterialTheme.shapes.medium,
        colors = mhEingabefeldFarben(),
        label = { Text("API-URL") },
        placeholder = { Text("https://server.example/meltdown-tracker/") },
        supportingText = { Text("Ordner des Meltdown-Trackers; „api.php“ ergänzt die App.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
