package ai.tama.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Explicit one-shot metadata GET; no inference, logs, redirects or retries. */
internal object KeyProbe {
    private class Attempt(val sent: AtomicBoolean = AtomicBoolean(false))

    private fun singleExchange(client: OkHttpClient) = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        // OkHttp can still schedule a follow-up for a 503 with Retry-After: 0.
        // Reject any second HTTP exchange before it can write the key again.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.tag(Attempt::class.java)?.sent?.compareAndSet(false, true) != true) {
                throw IOException("Key probe cannot repeat a request")
            }
            chain.proceed(request)
        }
        .callTimeout(15, TimeUnit.SECONDS).build()

    private val client = singleExchange(OkHttpClient())

    internal val unavailable = KeyProbePlan.Result(KeyProbePlan.Outcome.UNAVAILABLE)

    suspend fun check(plan: KeyProbePlan.Plan, key: String): KeyProbePlan.Result = execute(plan, key, client)

    // Lets a JVM test inspect the real request without sending a live credential.
    internal suspend fun check(plan: KeyProbePlan.Plan, key: String, client: OkHttpClient): KeyProbePlan.Result =
        execute(plan, key, singleExchange(client))

    private suspend fun execute(plan: KeyProbePlan.Plan, key: String, client: OkHttpClient): KeyProbePlan.Result =
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder().url(plan.url).get().header(
                    plan.header, if (plan.header == "Authorization") "Bearer $key" else key,
                ).apply { if (plan.anthropicVersion) header("anthropic-version", "2023-06-01") }
                    .tag(Attempt::class.java, Attempt()).build()
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(unavailable)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = try { response.use { readResult(plan, it) } }
                            catch (_: Exception) { unavailable }
                        continuation.resume(result)
                    }
                })
            } catch (_: Exception) {
                continuation.resume(unavailable)
            }
        }

    private fun readResult(plan: KeyProbePlan.Plan, response: Response): KeyProbePlan.Result {
        val status = response.code
        val readInfo = plan.openRouterKeyInfo && response.isSuccessful
        val readModels = plan.groqModelList && response.isSuccessful
        val readError = status == 402 || status == 403
        val body = if (readInfo || readModels || readError) runCatching { JSONObject(response.peekBody(65_536).string()) }.getOrNull() else null
        val models = if (readModels) {
            val rows = body?.optJSONArray("data") ?: return unavailable
            if (body.has("error")) return unavailable
            buildSet<String> {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: return unavailable
                    val id = (row.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return unavailable
                    if (row.opt("active") != false) add(id)
                }
            }
        } else null
        val data = body?.optJSONObject("data")
        if (readInfo && (data == null || !data.has("limit"))) return unavailable
        val limit = if (readInfo) data?.opt("limit") else null
        val zeroSpend = when {
            !readInfo -> null
            limit === JSONObject.NULL -> false // documented: null means no configured cap
            else -> KeyProbePlan.zeroSpendingLimit(limit) ?: return unavailable
        }
        val error = body?.optJSONObject("error") ?: body
        val noCredit = readError && KeyProbePlan.reportsNoCredit(
            error?.opt("code") as? String, error?.opt("message") as? String,
            error?.optJSONObject("metadata")?.opt("limit_source") as? String,
        )
        val groqPayment = plan.groqModelList && ai.tama.net.OwnKeyFailureSignals.reportsPaymentRequired(
            status, listOfNotNull(error?.opt("code") as? String, error?.opt("type") as? String),
            (error?.opt("message") as? String).orEmpty(),
        )
        return KeyProbePlan.Result(KeyProbePlan.classify(status, zeroSpend, noCredit || groqPayment), zeroSpend, models)
    }

}
