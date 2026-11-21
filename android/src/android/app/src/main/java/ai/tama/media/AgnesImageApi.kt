package ai.tama.media

import ai.tama.net.OwnKeyFailureSignals
import ai.tama.net.singleUseImageBody
import ai.tama.net.singleUseImageClient
import ai.tama.providers.ProviderEndpoints
import io.github.nanomuse.net.LanOnly
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** One synchronous-result Images request, not chat completions, multipart edits or a polling job. */
internal class AgnesImageApi(http: OkHttpClient, private val timeoutMs: Long = 120_000) {
    private val client = http.singleUseImageClient().newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    enum class Problem { QUEUE, LIMIT, PAYMENT, AUTHENTICATION, ACCESS_DENIED, TIMEOUT, NO_IMAGE, REFERENCE_SIZE, REFERENCE_FORMAT, FAILED }
    class Failure(val problem: Problem) : IOException(problem.name)
    private data class Reply(val status: Int, val json: JSONObject?)
    private data class Error(val message: String, val codes: List<String>)

    suspend fun request(
        key: String, model: String, prompt: String, size: String, references: List<ByteArray> = emptyList(),
        beforeRequest: () -> Unit,
    ): ByteArray = withTimeoutOrNull(timeoutMs) {
        require(key.isNotBlank() && AgnesImages.model(model) != null)
        val images = JSONArray()
        references.forEach { bytes ->
            currentCoroutineContext().ensureActive()
            if (bytes.size > AgnesImages.MAX_REFERENCE_BYTES) throw Failure(Problem.REFERENCE_SIZE)
            val mime = AgnesImages.mimeType(bytes) ?: throw Failure(Problem.REFERENCE_FORMAT)
            images.put("data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
        }
        val actualSize = AgnesImages.requestSize(size)
        suspend fun post(reserve: Boolean): Reply {
            val json = JSONObject().put("model", model).put("prompt", prompt).put("size", actualSize).put("n", 1)
            if (reserve) json.put("extra_body", JSONObject().put("response_format", "url").put("image", images))
            else if (images.length() > 0) json.put("image", images)
            val request = Request.Builder().url("${ProviderEndpoints.AGNES_BASE}/images/generations")
                .header("Authorization", "Bearer $key")
                .post(json.toString().toRequestBody("application/json".toMediaType()).singleUseImageBody()).build()
            return exchange(request, beforeRequest) { response ->
                val text = response.body?.string().orEmpty()
                Reply(response.code, runCatching { JSONObject(text) }.getOrNull())
            }
        }
        var reply = post(reserve = false)
        var error = errorOf(reply.json)
        // Only a definitive field/schema rejection licenses the alternative
        // body. A timeout, missing picture or ambiguous failure may have billed.
        if (images.length() > 0 && schemaRefusal(reply.status, error)) {
            reply = post(reserve = true)
            error = errorOf(reply.json)
        }
        if (reply.status == 503) throw Failure(Problem.QUEUE)
        if (reply.status == 401) throw Failure(Problem.AUTHENTICATION)
        val payload = firstImage(reply.json)
        if (paymentRequired(reply.status, error, payload == null)) throw Failure(Problem.PAYMENT)
        if (reply.status == 429 || (payload == null && limit.containsMatchIn(error.message))) throw Failure(Problem.LIMIT)
        if (reply.status == 403) throw Failure(Problem.ACCESS_DENIED)
        if (reply.status !in 200..299) throw Failure(Problem.FAILED)
        if (payload == null) throw Failure(Problem.NO_IMAGE)
        val bytes = if (payload.startsWith("https://", true)) {
            val url = payload.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && !LanOnly.isLocal(it.host) }
                ?: throw Failure(Problem.NO_IMAGE)
            exchange(Request.Builder().url(url).get().build(), beforeRequest) { response ->
                if (!response.isSuccessful) throw Failure(Problem.FAILED)
                response.body?.bytes() ?: throw Failure(Problem.NO_IMAGE)
            }
        } else {
            val encoded = if (payload.startsWith("data:image/", true) && ";base64," in payload) payload.substringAfter(";base64,") else payload
            try { Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace)) } catch (_: IllegalArgumentException) { throw Failure(Problem.NO_IMAGE) }
        }
        bytes.takeIf { it.isNotEmpty() } ?: throw Failure(Problem.NO_IMAGE)
    } ?: throw Failure(Problem.TIMEOUT)

    private suspend fun <T : Any> exchange(request: Request, beforeRequest: () -> Unit, read: (Response) -> T): T {
        currentCoroutineContext().ensureActive()
        beforeRequest()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWith(Result.failure(networkFailure(e)))
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use(read) }.recoverCatching {
                        throw if (it is IOException && it !is Failure) networkFailure(it) else it
                    })
                }
            })
        }
    }

    private fun networkFailure(e: IOException) = Failure(
        if (e is java.io.InterruptedIOException) Problem.TIMEOUT else Problem.FAILED,
    )

    private fun errorOf(json: JSONObject?): Error {
        val nested = json?.optJSONObject("error")
        fun string(obj: JSONObject?, key: String) = (obj?.opt(key) as? String)?.take(1000).orEmpty()
        return Error(string(nested, "message").ifBlank { string(json, "message") },
            listOf(string(nested, "code"), string(nested, "type"), string(json, "code")))
    }

    private fun paymentRequired(status: Int, error: Error, noImage: Boolean): Boolean =
        status == 402 || (noImage && status in 200..499 && status != 429 &&
            (OwnKeyFailureSignals.reportsPaymentRequired(403, error.codes, error.message) ||
                explicitPayment.containsMatchIn(error.message.trim())))

    private fun schemaRefusal(status: Int, error: Error): Boolean = status in setOf(400, 422) &&
        !paymentRequired(status, error, noImage = true) && schema.containsMatchIn(error.message.trim())

    private fun firstImage(json: JSONObject?): String? {
        for (name in listOf("data", "images")) {
            val list = json?.optJSONArray(name) ?: continue
            for (i in 0 until list.length()) {
                val item = list.opt(i)
                val value = if (item is JSONObject) {
                    (item.opt("url") as? String)?.takeIf { it.isNotBlank() } ?: (item.opt("b64_json") as? String)
                } else if (name == "images") item as? String else null
                if (!value.isNullOrBlank()) return value.trim()
            }
        }
        return null
    }

    private companion object {
        val limit = Regex("(?i)\\b(?:rate limit|too many requests|quota|usage limit|limite de uso)\\b")
        val explicitPayment = Regex("(?i)^(?:insufficient balance|(?:this )?model (?:is paid|requires a paid plan)|saldo insuficiente|cr[eé]ditos? insuficientes?|pagamento (?:necess[aá]rio|obrigat[oó]rio))\\b")
        val schema = Regex("""(?i)^(?:(?:unknown|unexpected|unrecognized|unsupported) (?:top-level )?(?:field|parameter)[: ]+['"`]?image['"`]?\b|(?:field |parameter )?['"`]?image['"`]?(?: field| parameter)? (?:is not (?:allowed|supported)|must be (?:inside|under) extra_body)\b|(?:missing|required)(?: field| parameter)?[: ]+['"`]?extra_body\.image\b)""")
    }
}
