package org.dwarftsch.meltdown.data

import android.content.Context

/**
 * Erstellt die aktuell konfigurierte Datenquelle.
 *
 * [offlineFaehig] legt in den Server-Modi die Warteschlange darüber. Die
 * Oberfläche will das; der Verbindungstest in den Einstellungen bewusst
 * nicht – er soll den echten Zustand zeigen, nicht den zwischengespeicherten.
 */
fun createConfiguredService(
    context: Context,
    settings: AppSettings,
    certSource: CertSource,
    offlineFaehig: Boolean = false,
): MeltdownService {
    val dienst = when (settings.mode) {
        DataSourceMode.MTLS -> ApiService(
            endpunkt = AppSettings.endpunkt(settings.mtlsUrl),
            certSource = certSource,
            // Optional: leer bedeutet „nur mTLS“.
            apiKey = settings.mtlsApiKey.ifEmpty { null },
        )
        // Cloudflare Access sichert den Zugang am Rand; der Key geht nur
        // raus, wenn er hinterlegt ist.
        DataSourceMode.CLOUDFLARE -> ApiService(
            endpunkt = AppSettings.endpunkt(settings.cloudflareUrl),
            apiKey = settings.cloudflareApiKey.ifEmpty { null },
            cfToken = settings.cfServiceToken(),
        )
        DataSourceMode.LOKAL -> return LocalService(context)
    }
    if (!offlineFaehig) return dienst
    return OfflineService(dienst, OfflineSpeicher(context, settings.zugang()))
}
