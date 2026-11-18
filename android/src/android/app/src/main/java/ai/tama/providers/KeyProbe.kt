package ai.tama.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Explicit one-shot GET of the official model list; no inference, logs, redirects or retries. */
internal object KeyProbe {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun check(plan: KeyProbePlan.Plan, key: String): KeyProbePlan.Outcome = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(plan.url).get().header(
                plan.header, if (plan.header == "Authorization") "Bearer $key" else key,
            ).apply { if (plan.anthropicVersion) header("anthropic-version", "2023-06-01") }.build()
            client.newCall(request).execute().use { KeyProbePlan.classify(it.code) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            KeyProbePlan.Outcome.UNAVAILABLE
        }
    }
}
