package org.dwarftsch.meltdown.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.EntryPage
import org.dwarftsch.meltdown.parseIsoZeit
import org.dwarftsch.meltdown.rfc3339
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spricht `meltdown-tracker/api.php` an. Authentifizierung per
 * mTLS-Client-Zertifikat ([certSource]), per Cloudflare Service Token
 * ([cfToken]) und/oder per API-Key ([apiKey], Header `X-API-Key`).
 *
 * Die API kennt nur GET und POST mit `?action=…`; Schreibzugriffe schicken
 * ein JSON-Objekt mit genau den erlaubten Feldern (unbekannte Felder → 400).
 */
class ApiService(
    /** Endpunkt inkl. `api.php`, siehe [AppSettings.endpunkt]. */
    private val endpunkt: String,
    /** Quelle für client.crt/client.key; null ohne Client-Zertifikat. */
    private val certSource: CertSource? = null,
    /** Wird als `X-API-Key` mitgesendet, falls gesetzt. */
    private val apiKey: String? = null,
    /** Cloudflare-Access-Service-Token; null ohne Access davor. */
    private val cfToken: CloudflareServiceToken? = null,
) : MeltdownService {

    private var client: OkHttpClient? = null

    private suspend fun httpClient(): OkHttpClient {
        client?.let { return it }
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
        val source = certSource
        if (source != null) {
            val (cert, key) = source.readCredentials()
            val (factory, trust) = ClientCertificates.socketFactoryMitTrust(cert, key)
            builder.sslSocketFactory(factory, trust)
        }
        return builder.build().also { client = it }
    }

    override fun dispose() {
        // Abbau auf einem Hintergrund-Thread: Conscrypt schreibt beim close()
        // noch das close_notify ins Socket – auf dem Main-Thread wirft
        // StrictMode dafür eine NetworkOnMainThreadException.
        val alterClient = client ?: return
        client = null
        Thread({
            alterClient.dispatcher.executorService.shutdown()
            alterClient.connectionPool.evictAll()
        }, "meltdown-api-dispose").start()
    }

    private fun url(action: String, parameter: Map<String, String> = emptyMap()): HttpUrl {
        if (endpunkt.isBlank()) {
            throw ApiException(
                "Keine API-URL konfiguriert. Bitte in den Einstellungen die Adresse des Servers hinterlegen.",
            )
        }
        val basis = endpunkt.toHttpUrlOrNull() ?: throw ApiException("Ungültige API-URL: $endpunkt")
        // Klartext nur für Loopback (Unit-Tests gegen MockWebServer); echte
        // Server-Adressen müssen https sein.
        if (basis.scheme != "https" && basis.host !in LOOPBACK) {
            throw ApiException("Die API-URL muss mit https:// beginnen.")
        }
        return basis.newBuilder()
            .addQueryParameter("action", action)
            .apply { parameter.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    private suspend fun get(action: String, parameter: Map<String, String> = emptyMap()): JSONObject =
        sende(url(action, parameter), null)

    private suspend fun post(action: String, body: JSONObject): JSONObject =
        sende(url(action), body)

    private suspend fun sende(url: HttpUrl, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (!apiKey.isNullOrEmpty()) header("X-API-Key", apiKey)
                    cfToken?.anwenden(this)
                    if (body == null) {
                        get()
                    } else {
                        post(body.toString().toRequestBody(JSON))
                    }
                }
                .build()

            httpClient().newCall(request).execute().use { response ->
                CloudflareServiceToken.abweisung(response)?.let {
                    throw ApiException(it, statusCode = response.code)
                }
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (response.code in 200..299) {
                    return@use json ?: throw ApiException(
                        "Unerwartete Antwort (kein JSON): ${snippet(text)}",
                        statusCode = response.code,
                    )
                }
                val meldung = json?.optString("error")?.takeIf { it.isNotEmpty() }
                    ?: standardMeldung(response.code)
                    ?: text.ifEmpty { "Anfrage fehlgeschlagen" }.let(::snippet)
                throw ApiException(meldung, statusCode = response.code)
            }
        }

    // ── Kinder ──────────────────────────────────────────────────────────────

    override suspend fun getChildren(): List<Child> {
        val liste = get("children").optJSONArray("children")
            ?: throw ApiException("Unerwartete Antwort der API.")
        return (0 until liste.length()).map { kindAusJson(liste.getJSONObject(it)) }
    }

    override suspend fun createChild(name: String): Child =
        kindAusJson(post("child_create", JSONObject().put("name", name)).getJSONObject("child"))

    override suspend fun renameChild(id: Long, name: String): Child =
        kindAusJson(post("child_rename", JSONObject().put("id", id).put("name", name)).getJSONObject("child"))

    override suspend fun deleteChild(id: Long) {
        post("child_delete", JSONObject().put("id", id))
    }

    // ── Einträge ────────────────────────────────────────────────────────────

    override suspend fun getEntries(childId: Long?, offset: Int, limit: Int): EntryPage {
        val parameter = buildMap {
            if (childId != null) put("child_id", childId.toString())
            put("limit", limit.toString())
            put("offset", offset.toString())
        }
        val liste = get("entries", parameter).optJSONArray("entries")
            ?: throw ApiException("Unerwartete Antwort der API.")
        val eintraege = eintraegeAusJson(liste)
        return EntryPage(
            entries = eintraege,
            naechsterOffset = offset + liste.length(),
            hatMehr = liste.length() >= limit,
        )
    }

    override suspend fun createEntry(neu: EntryInput): Entry {
        val body = JSONObject()
            .put("child_id", neu.childId)
            .put("intensity", neu.intensity)
        // Ohne create_time setzt der Server „jetzt“ – eine vorgehende Handyuhr
        // würde sonst als Zukunft abgewiesen.
        neu.createTime?.let { body.put("create_time", rfc3339(it)) }
        neu.trigger?.let { body.put("trigger", it) }
        return eintragAusJson(post("entry_create", body).getJSONObject("entry"))
    }

    override suspend fun updateEntry(id: Long, neu: EntryInput): Entry {
        val zeit = neu.createTime ?: throw ApiException("Zeitpunkt fehlt.")
        val body = JSONObject()
            .put("id", id)
            .put("child_id", neu.childId)
            .put("create_time", rfc3339(zeit))
            .put("intensity", neu.intensity)
            // null löscht einen vorhandenen Auslöser.
            .put("trigger", neu.trigger ?: JSONObject.NULL)
        return eintragAusJson(post("entry_update", body).getJSONObject("entry"))
    }

    override suspend fun deleteEntry(id: Long) {
        post("entry_delete", JSONObject().put("id", id))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        fun kindAusJson(json: JSONObject): Child = Child(
            id = json.getLong("id"),
            name = json.getString("name"),
            entries = json.optInt("entries", 0),
        )

        fun eintragAusJson(json: JSONObject): Entry = Entry(
            id = json.getLong("id"),
            childId = json.getLong("child_id"),
            childName = json.optString("child_name"),
            createTime = parseIsoZeit(json.getString("create_time")),
            intensity = json.getInt("intensity"),
            trigger = if (json.isNull("trigger")) null else json.optString("trigger").ifEmpty { null },
        )

        /** Liest eine Liste; einzelne kaputte Einträge fliegen raus statt alles scheitern zu lassen. */
        fun eintraegeAusJson(liste: JSONArray): List<Entry> =
            (0 until liste.length()).mapNotNull { i ->
                liste.optJSONObject(i)?.let { runCatching { eintragAusJson(it) }.getOrNull() }
            }

        /** Verständliche Meldungen für Fälle ohne JSON-Fehlertext. */
        fun standardMeldung(code: Int): String? = when (code) {
            400 -> "Der Server hat die Anfrage abgelehnt (400). Bei mTLS: Client-Zertifikat fehlt oder ist ungültig."
            401 -> "Nicht angemeldet (401). API-Key bzw. Zertifikat prüfen."
            403 -> "Zugriff verweigert (403)."
            404 -> "Nicht gefunden (404). API-URL prüfen."
            421 -> "Der Server kennt diese Adresse nicht (421)."
            503 -> "Der Meltdown-Tracker ist auf dem Server gerade nicht verfügbar (503)."
            else -> null
        }

        /** Kürzt eine (Fehler-)Antwort für die Anzeige. */
        fun snippet(s: String): String {
            val clean = s.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 200) clean.take(200) + "…" else clean
        }
    }
}
