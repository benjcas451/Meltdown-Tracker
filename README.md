# Meltdown-Tracker (Android)

Native Android-App für den Meltdown-Tracker des Familien-Webstacks. Ein
Eintrag ist ein **Kind**, ein **Zeitpunkt**, eine **Intensität von 1 (mild)
bis 10 (maximal)** und ein optionaler **Auslöser**. Dazu gibt es einen
chronologischen Verlauf, wie in der Weboberfläche bewusst ohne Dauer,
Diagnose oder Auswertung.

Kotlin, Jetpack Compose (Material 3), AGP 9 mit Built-in Kotlin. Aufbau,
Werkzeuge und Design-System folgen **StillzeitTracker-Android**.

| | |
|---|---|
| applicationId | `org.dwarftsch.meltdown` |
| minSdk / targetSdk | 26 / 37 |
| Server-Gegenstück | `www_apps_shared/meltdown-tracker/api.php` (Webserver) |

---

## Funktionen

- **Erfassen:** Großer Button „Meltdown erfassen“. Im Dialog Kind, Zeitpunkt
  („Jetzt“ oder Datum + Uhrzeit), Intensität 1–10 und Auslöser (höchstens 200
  Zeichen) wählen. Vorausgewählt ist das gefilterte, zuletzt verwendete bzw.
  einzige Kind.
- **Verlauf:** Neueste zuerst, nach Tagen gruppiert, nach Kind filterbar. Ältere
  Einträge kommen seitenweise (50 Stück) per „Ältere Einträge laden“.
  Bearbeiten über den Stift; Löschen über den Papierkorb oder durch Wischen
  nach links, immer mit Rückfrage.
- **Kinder verwalten** (Symbol in der oberen Leiste): anlegen, umbenennen,
  löschen. Ein Kind mit Einträgen wird wie im Web nicht gelöscht (Server: 409).
- **Einstellungen** sind immer über das Zahnrad oben erreichbar, auch wenn
  keine Verbindung besteht. Die Fehleransicht hat zusätzlich einen
  eigenen Einstellungen-Button.

## Datenquellen

| Modus | Auth | Prefs-Schlüssel |
|---|---|---|
| **Server (mTLS)**, Standard | Client-Zertifikat (`client.crt` + `client.key` aus einem per Dateiauswahl freigegebenen Ordner), optional zusätzlich `X-API-Key` | `mtls_url`, `mtls_api_key`, `cert_folder_uri` |
| **Server (Cloudflare Access)** | `CF-Access-Client-Id` + `CF-Access-Client-Secret`, optional zusätzlich `X-API-Key` | `cloudflare_url`, `cf_access_client_id`, `cf_access_client_secret`, `cloudflare_api_key` |
| **Lokal (SQLite)** | – | `meltdown.db` |

**Adresse:** Es genügt der Ordner, z. B. `https://<host>/meltdown-tracker/`;
`api.php` ergänzt die App (`AppSettings.endpunkt`). Nur `https://`.
Server-Adressen und Zugangsdaten haben **keinen** Vorgabewert im Code. Sie
gehören zu einer Familie und werden auf dem Gerät eingetragen.

**Welcher Modus für welchen Host:** mTLS-Hosts (Client-Zertifikat am
Reverse-Proxy) → Modus mTLS. Hosts hinter Cloudflare Access → Modus Cloudflare.
Der braucht ein Service Token, dem die Access-Anwendung per
Service-Auth-Regel Zugang gewährt, und in der Regel zusätzlich den
Meltdown-API-Key der Familie.

„Verbindung testen“ in den Einstellungen ruft `?action=children` direkt auf,
ohne Offline-Hülle, und zeigt damit den echten Zustand.

Fehlerbilder, die die App gezielt übersetzt:
- **400 ohne JSON** (NPMplus ohne Zertifikat) → Hinweis aufs Zertifikat.
- **Weiterleitung auf `*.cloudflareaccess.com`** oder **403 mit `cf-ray`** →
  Hinweis aufs Service Token.

## REST-API (Server)

Nur GET und POST mit `?action=…`. Schreibzugriffe schicken ein JSON-Objekt
mit **genau** den erlaubten Feldern, denn unbekannte Felder lehnt der Server mit
400 ab.

| Aufruf | Zweck |
|---|---|
| `GET ?action=children` | Kinder (`id`, `name`, `entries`) |
| `GET ?action=entries[&child_id][&limit≤500][&offset]` | Verlauf, neueste zuerst |
| `POST ?action=child_create {name}` / `child_rename {id, name}` / `child_delete {id}` | Kinder |
| `POST ?action=entry_create {child_id, intensity[, create_time][, trigger]}` | Eintrag anlegen |
| `POST ?action=entry_update {id, child_id, create_time, intensity, trigger}` | Eintrag ändern (`trigger: null` löscht den Auslöser) |
| `POST ?action=entry_delete {id}` | Eintrag löschen |

**Zeitpunkte:**
- Beim Erfassen mit „Jetzt“ schickt die App **kein** `create_time`; der
  Server setzt seine eigene Uhrzeit. Er weist Zeitpunkte mehr als 60 s in der
  Zukunft ab, und eine vorgehende Handyuhr würde sonst scheitern.
- Sonst sendet die App immer RFC 3339 **mit Offset und Sekunden**
  (`2026-10-25T02:30:00+01:00`). Eine Berliner Ortszeit ohne Offset in der
  doppelten Stunde der Zeitumstellung würde der Server als mehrdeutig
  ablehnen.
- Ein unveränderter Zeitpunkt bleibt beim Bearbeiten sekundengenau erhalten.

## Offline-Betrieb (Server-Modi)

`OfflineService` legt sich über die Server-Quelle; Verhalten wie in der
Stillzeit-App:

- **Lesen:** Kinder und die erste Verlaufsseite je Filter liegen nach jedem
  erfolgreichen Laden in `filesDir/offline/`. Bei einem Netzwerkfehler zeigt
  die App diesen Stand mit Hinweisleiste.
- **Schreiben (nur Einträge):** Was den Server **nachweislich nie erreicht**
  hat (DNS, Verbindungsaufbau, TLS, siehe `Netzfehler`), landet in einer
  Warteschlange mit negativer lokaler ID und Uhr-Symbol. Bei
  Zeitüberschreitung oder Abbruch mitten in der Übertragung bleibt es bei
  einer Fehlermeldung, weil die API keinen Idempotenz-Schlüssel kennt und
  sonst Duplikate entstünden.
- **Reihenfolge:** Solange etwas wartet, gehen neue Schreibzugriffe hinten dran.
  Änderungen und Löschungen eines noch wartenden Eintrags werden direkt in
  dessen Anlegen eingearbeitet.
- **Abarbeiten:** vor jedem Laden und sobald wieder ein Netz da ist. Weist der
  Server eine Aktion inhaltlich zurück, fliegt sie raus und wird gemeldet.
- **Kinder verwalten geht nur online.** Ein offline angelegtes Kind bräuchte
  eine lokale ID für wartende Einträge, und Namenskonflikte fielen erst beim
  Nachholen auf. Ein Kind mit wartenden Einträgen lässt sich nicht löschen.
- **Ablage je Zugang:** Die Ablage hängt an Modus + Endpunkt. Ein Serverwechsel
  zeigt keine fremden Daten und lädt keine Warteschlange zum falschen Server.

## Lokaler Modus (SQLite)

`meltdown.db` spiegelt die Server-Migration `007_meltdown_tracker.sql`:
- `children(id, name UNIQUE COLLATE NOCASE)`
- `entries(id, child_id → children ON DELETE RESTRICT, create_time TEXT UTC, intensity 1–10, trigger_text)`

Die Grenzen sind dieselben wie auf dem Server (`Grenzen` in `Models.kt`):
Name 1–50, Auslöser bis 200 Zeichen (gezählt in Codepoints wie `mb_strlen`),
keine Steuerzeichen, Zeitpunkt ab 2000 und nicht in der Zukunft.

**JSON-Backup** (Einstellungen → Backup) für Kinder + Einträge. Das Format
steht in `LocalBackupService`; der Import ist auf 16 MB begrenzt, wird vor dem
Ersetzen vollständig geprüft und läuft in einer Transaktion.

## Sicherung & Gerätewechsel

`res/xml/data_extraction_rules.xml` (API 31+) und `backup_rules.xml` sind als
**Whitelist** gepflegt:

| | Cloud-Backup | Geräte-Transfer |
|---|---|---|
| `meltdown.db` (ganzer Datenbank-Ordner, wegen WAL) | ✅ | ✅ |
| `meltdown_settings.xml` (URLs, **API-Keys, Service Token**) | ❌ | ✅ |
| `filesDir/offline` (Zwischenspeicher, Warteschlange) | ❌ | ❌ |

Die Freigabe des Zertifikats-Ordners überlebt keinen Gerätewechsel;
`CertSource` prüft das und fragt den Ordner neu ab. **Jedes neue `<include>`
erweitert die Whitelist, und jede neue Prefs-Datei fehlt sonst
stillschweigend.** Die In-App-Erklärung (`DB_INFO_TEXT`) mitziehen.

## Bauen & Testen

```bash
./gradlew :app:testDebugUnitTest      # Unit-Tests (JVM, inkl. MockWebServer)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease -PbuildNumber=123
```

- **JDK:** Der Gradle-Daemon provisioniert JDK 25 selbst
  (`gradle/gradle-daemon-jvm.properties`). Zum Starten genügt ein JDK 17–25.
- **Signing:** `key.properties` nach `key.properties.example` anlegen. Ohne die
  Datei signieren Release-Builds mit dem Debug-Key (lokal baubar, nicht
  Play-tauglich). `key.properties` und `*.jks` sind gitignored.
- **Tests:** Sie decken Validierung, Endpunkt-Bildung, DST-sichere Zeitformate,
  Anfrageformat gegen `api.php` (MockWebServer), Warteschlange,
  Offline-Hülle und Backup-Prüfung ab.

## Versionierung

- `versionName` steht manuell in `app/build.gradle.kts`; Workflow und Tag lesen
  ihn von dort.
- `versionCode` = `-PbuildNumber`; die CI übergibt `github.run_number`, lokal
  gilt der Fallback 1. Branch-Builds und Releases laufen bewusst in **derselben**
  Workflow-Datei und teilen sich damit den Zähler. Getrennte Dateien hätten
  eigene Zähler, und Play lehnt einen schon einmal verwendeten `versionCode` ab.

## CI / Releases (`.github/workflows/build-release.yml`)

**Branch-Builds:** Jeder Push auf einen Branch außer `main` startet Tests und
baut eine signierte APK + AAB. Beide liegen 14 Tage als Artefakt am Lauf
(Actions → Lauf → *Artifacts*), ohne Release und ohne Play-Upload. So
entsteht z. B. das AAB für den ersten, manuellen Upload in der Play Console.

**Release** manuell per *workflow_dispatch*. Ein Lauf:
1. führt die Unit-Tests aus,
2. baut eine signierte **APK** und hängt sie an ein GitHub-Release
   (`v<version>-<run_number>`),
3. baut optional (`play_upload`, Standard an) ein **App Bundle** und lädt es in
   den gewählten Play-Track (`play_track`: internal/alpha/beta/production,
   Standard `alpha`; `play_status`: `completed` oder `draft`).

Benötigte **Repository-Secrets** (nur Namen, Werte niemals dokumentieren):
`PLAY_KEYSTORE_BASE64`, `PLAY_KEYSTORE_PASSWORD`, `PLAY_KEY_ALIAS`,
`PLAY_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.

**Play-Besonderheiten bei einer neuen App:**
- Die App muss in der Play Console angelegt sein, und das **erste** Bundle
  wird dort einmal **von Hand** hochgeladen. Erst danach nimmt die API Uploads
  an.
- Solange die App in Play noch nie veröffentlicht wurde, akzeptiert Play nur
  Releases mit Status `draft` („Only releases with status draft may be created
  on draft app“). Dann `play_status = draft` wählen.
- Der Service-Account braucht in der Play Console Release-Rechte für diese
  App.
- Track-Namen sind case-sensitiv.
- Play signiert mit eigenem App-Signing-Key. Der Keystore hier ist nur der
  **Upload-Key**. APKs aus dem GitHub-Release tragen den Upload-Key und lassen
  sich deshalb nicht über eine Play-Installation drüberinstallieren (und
  umgekehrt).

## Architektur

```
Models.kt                    Child, Entry, EntryInput, EntryPage, Grenzen, Zeitformate
MainActivity.kt              Ansichten Start / Kinder / Einstellungen
data/MeltdownService.kt      gemeinsames Interface + ApiException
data/ApiService.kt           REST-Client (OkHttp, mTLS, CF-Token, X-API-Key)
data/LocalService.kt         SQLite (meltdown.db) + Backup-Zugriff
data/OfflineService.kt       Offline-Hülle, Verbindungswache
data/Warteschlange.kt        wartende Schreibzugriffe (rein, testbar)
data/OfflineSpeicher.kt      Ablage je Zugang (filesDir/offline)
data/Netzfehler.kt           nie gesendet vs. mehrdeutig
data/ClientCertificates.kt   PEM (crt/key) → SSLSocketFactory, inkl. PKCS#1→#8
data/CertSource.kt           Zertifikats-Ordner über die Android-Dateiauswahl (SAF)
data/CloudflareServiceToken.kt  Header + Erkennung der Access-Abweisung
data/AppSettings.kt          Prefs, Endpunkt-Bildung, Zugangskennung
data/LocalBackupService.kt   JSON-Backup (Import max. 16 MB)
data/ServiceFactory.kt       Datenquelle nach Einstellung
ui/…                         Compose-UI (Theme, Start, Kinder, Einstellungen, Dialoge)
```

## Design-System „Minze & Honig“

Übernommen aus der Stillzeit-App (`ui/Theme.kt`, Nunito, OFL-Lizenz in
`app/src/main/assets/OFL_NUNITO.txt`). Die Intensität hat drei Stufen in den
Markenfarben, jeweils Fläche 300 mit Text 900:

| Stufe | Farbe |
|---|---|
| 1–3 | Minze |
| 4–6 | Honig |
| 7–10 | Flieder |

Rot bleibt Fehlern und Löschen vorbehalten. Die Zahl steht immer mit dabei,
Farbe ist nie der einzige Informationsträger. Das App-Icon ist ein weißes Herz
auf Flieder-Verlauf und als Themed Icon verfügbar.

## Was nie ins Repo darf

`key.properties`, `*.jks`, Play-Service-Account-JSON, API-Keys, Service
Tokens, Client-Zertifikate, Server-URLs von Familien. Die `.gitignore` deckt
die Dateien ab. Bei neuen Secrets zuerst dort eintragen, dann anlegen.
