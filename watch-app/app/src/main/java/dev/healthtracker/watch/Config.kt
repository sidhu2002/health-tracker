package dev.healthtracker.watch

/**
 * Personal-use config. Adjust for your setup.
 *
 * There are two profiles:
 *   USE_PROD = false → talk to the wrangler dev server on your laptop LAN.
 *   USE_PROD = true  → talk to the deployed Cloudflare Worker over HTTPS.
 *
 * After you deploy to Cloudflare, fill in PROD_BACKEND_URL + PROD_WATCH_TOKEN,
 * flip USE_PROD to true, hit Run in Android Studio to reinstall.
 */
object Config {

    // ------- Toggle -------
    private const val USE_PROD: Boolean = true

    // ------- Dev (LAN) -------
    private const val DEV_BACKEND_URL: String = "http://192.168.201.113:8787"
    private const val DEV_WATCH_TOKEN: String = "dev-token-change-me-before-deploy"

    // ------- Prod (Cloudflare) -------
    private const val PROD_BACKEND_URL: String = "https://health-api.siddeshwar.com"
    private val PROD_WATCH_TOKEN: String = BuildConfig.PROD_WATCH_TOKEN

    // ------- Resolved -------
    val BACKEND_URL: String get() = if (USE_PROD) PROD_BACKEND_URL else DEV_BACKEND_URL
    val WATCH_TOKEN: String get() = if (USE_PROD) PROD_WATCH_TOKEN else DEV_WATCH_TOKEN

    // Stable identifier for this watch.
    const val DEVICE_ID: String = "galaxy-watch-4"

    // How often the background worker attempts to drain the buffer.
    const val UPLOAD_INTERVAL_MINUTES: Long = 15
}
