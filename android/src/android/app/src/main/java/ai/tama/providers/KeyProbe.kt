package ai.tama.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Explicit one-shot GET of the official model list; no inference, logs, redirects or retries. */
internal object KeyProbe {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun check(plan: KeyProbePlan.Plan, key: String): KeyProbePlan.Outcome =
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder().url(plan.url).get().header(
                    plan.header, if (plan.header == "Authorization") "Bearer $key" else key,
                ).apply { if (plan.anthropicVersion) header("anthropic-version", "2023-06-01") }.build()
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(KeyProbePlan.Outcome.UNAVAILABLE)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val outcome = response.use { KeyProbePlan.classify(it.code) }
                        continuation.resume(outcome)
                    }
                })
            } catch (_: Exception) {
                continuation.resume(KeyProbePlan.Outcome.UNAVAILABLE)
            }
        }
}
