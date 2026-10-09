package com.lumena.android.listing

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.modules.ListingAttentionModule
import com.lumena.android.modules.PracujModule
import com.lumena.android.settings.ListingAttentionStore
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owner's Pracuj.pl watch: what to search, how often, and the last result.
 * Runs in WorkManager, so it survives app restarts and needs no Termux.
 */
object PracujWatch {
    const val MIN_INTERVAL_MINUTES = 15
    private const val PREFS = "lumena_pracuj_watch"
    private const val PERIODIC = "lumena-pracuj-watch"
    private const val NOW = "lumena-pracuj-watch-now"

    data class Config(
        val enabled: Boolean = false,
        val search: PracujSearch = PracujSearch(),
        val intervalMinutes: Int = 30
    )

    data class Status(
        val at: Long = 0,
        val message: String = "ще не запускалось"
    )

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean("enabled", false),
            search = PracujSearch(
                city = p.getString("city", null) ?: "legionowo",
                radiusKm = p.getInt("radius", 10),
                keywords = p.getString("keywords", null).orEmpty()
            ),
            intervalMinutes = p.getInt("interval", 30).coerceAtLeast(MIN_INTERVAL_MINUTES)
        )
    }

    /** Saves the owner's settings and (re)schedules or cancels the periodic check. */
    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean("enabled", config.enabled)
            .putString("city", config.search.city.trim())
            .putInt("radius", config.search.radiusKm.coerceIn(0, 100))
            .putString("keywords", config.search.keywords.trim())
            .putInt("interval", config.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES))
            .apply()
        val work = WorkManager.getInstance(context.applicationContext)
        if (config.enabled) {
            val request = PeriodicWorkRequestBuilder<PracujWatchWorker>(
                config.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong(),
                TimeUnit.MINUTES
            ).setConstraints(connected()).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            work.cancelUniqueWork(PERIODIC)
        }
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PracujWatchWorker>().setConstraints(connected()).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(p.getLong("status_at", 0), p.getString("status", null) ?: Status().message)
    }

    internal fun recordStatus(context: Context, message: String) {
        prefs(context).edit()
            .putLong("status_at", System.currentTimeMillis())
            .putString("status", message)
            .apply()
    }

    /** A changed search is a new baseline, not 50 fresh job alerts. */
    internal fun needsBaseline(context: Context, url: String): Boolean =
        prefs(context).getString("baselined_url", null) != url

    internal fun markBaselined(context: Context, url: String) {
        prefs(context).edit().putString("baselined_url", url).apply()
    }

    private fun connected() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** One read of the public search page; never retried, never disguised. */
internal object PracujClient {
    private const val MAX_BYTES = 4 * 1024 * 1024
    internal val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // A server redirect must not send a request to an unrelated domain.
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Bound actual bytes, not only Content-Length (which can be unknown). */
    internal fun readBounded(stream: InputStream, limit: Int = MAX_BYTES): ByteArray {
        require(limit in 1..MAX_BYTES)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            check(out.size().toLong() + n <= limit) { "сторінка завелика" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun fetch(url: String): Result<String> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lumena/0.12 (personal job alerts; no automation disguise)")
            .header("Accept-Language", "pl-PL,pl;q=0.9")
            .build()
        check(request.url.scheme == "https" && request.url.host == "www.pracuj.pl") {
            "дозволений лише https://www.pracuj.pl/"
        }
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                when (response.code) {
                    403, 429 -> "Pracuj.pl обмежив доступ (HTTP ${response.code}); Lumena не обходить обмеження"
                    in 300..399 -> "Pracuj.pl перенаправляє (HTTP ${response.code}); переходи заборонені"
                    else -> "Pracuj.pl відповів HTTP ${response.code}"
                }
            }
            val body = checkNotNull(response.body) { "порожня відповідь" }
            check(body.contentLength() <= MAX_BYTES) { "сторінка завелика" }
            val kind = body.contentType()?.let { "${it.type}/${it.subtype}" }
            check(kind == null || kind in setOf("text/html", "application/xhtml+xml")) {
                "неочікуваний тип сторінки: $kind"
            }
            readBounded(body.byteStream()).toString(Charsets.UTF_8)
        }
    }
}

class PracujWatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    private companion object {
        const val MAX_ALERTS_PER_RUN = 3
    }

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ModuleRegistry.isEnabled(PracujModule.ID) || !ModuleRegistry.isEnabled(ListingAttentionModule.ID)) {
            PracujWatch.recordStatus(app, "модуль вимкнено — перевірку пропущено")
            return Result.success()
        }
        val config = PracujWatch.load(app)
        val url = runCatching { PracujSource.searchUrl(config.search) }.getOrElse { failure ->
            PracujWatch.recordStatus(app, "некоректні параметри: ${failure.message}")
            return Result.success()
        }
        val html = withContext(Dispatchers.IO) { PracujClient.fetch(url) }.getOrElse { failure ->
            PracujWatch.recordStatus(app, "помилка: ${failure.message}")
            // No retry: a failed or limited site is not hammered; the next period tries again.
            return Result.success()
        }
        when (val page = PracujSource.parse(html, System.currentTimeMillis())) {
            is PracujPage.Blocked -> PracujWatch.recordStatus(app, "зупинено: ${page.reason}")
            is PracujPage.Offers -> {
                val firstForSearch = PracujWatch.needsBaseline(app, url)
                val records = withContext(Dispatchers.IO) {
                    runCatching { ListingAttentionStore.ingestNotices(app, page.notices) }
                }.getOrElse { failure ->
                    PracujWatch.recordStatus(app, "помилка збереження: ${failure.message}")
                    return Result.success()
                }
                // Mark the first complete parse as baseline only after successful persistence.
                if (firstForSearch) PracujWatch.markBaselined(app, url)
                val alerts = if (firstForSearch) emptyList() else records.filter { it.decision == ListingDecision.ALERT }
                alerts.take(MAX_ALERTS_PER_RUN).forEach { record -> runCatching { ListingAlerts.post(app, record, null) } }
                PracujWatch.recordStatus(
                    app,
                    if (firstForSearch) "початкова база: ${page.notices.size} вакансій, без тривог"
                    else "на сторінці ${page.notices.size}, нових ${records.size}, тривог ${alerts.size}"
                )
            }
        }
        return Result.success()
    }
}
