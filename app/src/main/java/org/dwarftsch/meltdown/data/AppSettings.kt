package org.dwarftsch.meltdown.data

import android.content.Context
import android.content.SharedPreferences

/** Welche Datenquelle die App verwendet. */
enum class DataSourceMode(val gespeichert: String) {
    /** Server-API per mTLS-Client-Zertifikat (optional zusätzlich API-Key). */
    MTLS("mtls"),

    /**
     * Server-API hinter Cloudflare Access, ausgewiesen per Service Token
     * (`CF-Access-Client-Id`/`CF-Access-Client-Secret`), optional mit API-Key.
     */
    CLOUDFLARE("cloudflare"),

    /** Lokale SQLite-Datenbank, keine Synchronisation. */
    LOKAL("lokal");

    companion object {
        fun fromGespeichert(value: String?): DataSourceMode =
            entries.firstOrNull { it.gespeichert == value } ?: MTLS
    }
}

/**
 * Lädt und speichert App-Einstellungen (SharedPreferences `meltdown_settings`).
 *
 * Server-Adressen und Zugangsdaten haben bewusst keinen Vorgabewert: sie
 * gehören zu einer Familie und werden auf dem Gerät eingetragen.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: DataSourceMode
        get() = DataSourceMode.fromGespeichert(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.gespeichert).apply()

    /** Adresse der mTLS-API, wie eingegeben; siehe [endpunkt]. */
    var mtlsUrl: String
        get() = prefs.getString(KEY_MTLS_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MTLS_URL, value.trim()).apply()

    /**
     * Optionaler API-Key **zusätzlich** zum Client-Zertifikat. Eigener
     * Schlüssel, damit der Wechsel zwischen den Server-Modi nicht den jeweils
     * anderen Key überschreibt. Leer = nur mTLS.
     */
    var mtlsApiKey: String
        get() = prefs.getString(KEY_MTLS_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MTLS_API_KEY, value.trim()).apply()

    /** Adresse der API hinter Cloudflare Access, wie eingegeben. */
    var cloudflareUrl: String
        get() = prefs.getString(KEY_CLOUDFLARE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUDFLARE_URL, value.trim()).apply()

    /**
     * Optionaler API-Key zusätzlich zum Service Token. Der Meltdown-Tracker
     * verlangt hinter Cloudflare in der Regel seinen eigenen Key (`X-API-Key`).
     */
    var cloudflareApiKey: String
        get() = prefs.getString(KEY_CLOUDFLARE_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUDFLARE_API_KEY, value.trim()).apply()

    /** Client-ID des Cloudflare Service Tokens; endet üblicherweise auf `.access`. */
    var cfAccessClientId: String
        get() = prefs.getString(KEY_CF_CLIENT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CF_CLIENT_ID, value.trim()).apply()

    /** Client-Secret des Cloudflare Service Tokens. */
    var cfAccessClientSecret: String
        get() = prefs.getString(KEY_CF_CLIENT_SECRET, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CF_CLIENT_SECRET, value.trim()).apply()

    /**
     * Das hinterlegte Service Token, oder null solange eine Hälfte fehlt –
     * mit einer Hälfte weist Cloudflare die Anfrage genauso ab wie ganz ohne.
     */
    fun cfServiceToken(): CloudflareServiceToken? =
        CloudflareServiceToken.of(cfAccessClientId, cfAccessClientSecret)

    /** SAF-Ordner-URI der Zertifikate; null, solange keiner gewählt wurde. */
    var certFolderUri: String?
        get() = prefs.getString(KEY_CERT_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_CERT_FOLDER_URI, value).apply()

    /**
     * Kennung des aktuellen Zugangs (Modus + Endpunkt). Offline-Ablage und
     * „zuletzt gewähltes Kind“ hängen daran: ein Serverwechsel zeigt weder
     * fremde Einträge noch lädt er eine Warteschlange zum falschen Server.
     */
    fun zugang(): String = when (mode) {
        DataSourceMode.MTLS -> "mtls|${endpunkt(mtlsUrl)}"
        DataSourceMode.CLOUDFLARE -> "cloudflare|${endpunkt(cloudflareUrl)}"
        DataSourceMode.LOKAL -> "lokal"
    }

    /** Zuletzt beim Erfassen gewähltes Kind des aktuellen Zugangs. */
    var letztesKind: Long?
        get() = prefs.getLong("$KEY_LETZTES_KIND:${zugang()}", -1L).takeIf { it > 0 }
        set(value) = prefs.edit().apply {
            if (value == null) remove("$KEY_LETZTES_KIND:${zugang()}") else putLong("$KEY_LETZTES_KIND:${zugang()}", value)
        }.apply()

    companion object {
        const val PREFS_NAME = "meltdown_settings"

        /**
         * Aus der eingegebenen Adresse den Endpunkt `api.php` machen. Erlaubt
         * sind beide Formen:
         * `https://host/meltdown-tracker/` und `https://host/meltdown-tracker/api.php`.
         * Leer bleibt leer (die API meldet dann „keine URL konfiguriert“).
         */
        fun endpunkt(eingabe: String): String {
            val url = eingabe.trim()
            if (url.isEmpty()) return ""
            val ohneQuery = url.substringBefore('?').substringBefore('#')
            if (ohneQuery.endsWith(".php")) return ohneQuery
            return (if (ohneQuery.endsWith("/")) ohneQuery else "$ohneQuery/") + "api.php"
        }

        private const val KEY_MODE = "data_source_mode"
        private const val KEY_MTLS_URL = "mtls_url"
        private const val KEY_MTLS_API_KEY = "mtls_api_key"
        private const val KEY_CLOUDFLARE_URL = "cloudflare_url"
        private const val KEY_CLOUDFLARE_API_KEY = "cloudflare_api_key"
        private const val KEY_CF_CLIENT_ID = "cf_access_client_id"
        private const val KEY_CF_CLIENT_SECRET = "cf_access_client_secret"
        private const val KEY_CERT_FOLDER_URI = "cert_folder_uri"
        private const val KEY_LETZTES_KIND = "letztes_kind"
    }
}
