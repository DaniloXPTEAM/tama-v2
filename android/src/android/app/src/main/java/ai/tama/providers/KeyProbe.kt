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
import kotlin.coroutines.resume

/** Explicit one-shot metadata GET; no inference, logs, redirects or retries. */
internal object KeyProbe {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    private val unavailable = KeyProbePlan.Result(KeyProbePlan.Outcome.UNAVAILABLE)

    suspend fun check(plan: KeyProbePlan.Plan, key: String): KeyProbePlan.Result =
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder().url(plan.url).get().header(
                    plan.header, if (plan.header == "Authorization") "Bearer $key" else key,
                ).apply { if (plan.anthropicVersion) header("anthropic-version", "2023-06-01") }.build()
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
        val readError = status == 402 || status == 403
        val body = if (readInfo || readError) runCatching { JSONObject(response.peekBody(65_536).string()) }.getOrNull() else null
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
        return KeyProbePlan.Result(KeyProbePlan.classify(status, zeroSpend, noCredit), zeroSpend)
    }

}
