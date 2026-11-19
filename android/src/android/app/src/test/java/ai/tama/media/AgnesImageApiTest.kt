package ai.tama.media

import ai.tama.providers.ProviderEndpoints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgnesImageApiTest {
    private val key = "fixture-agnes-key"
    private val prompt = "crie uma imagem de um gato astronauta"

    private fun client(server: MockWebServer, timeoutMs: Long = 120_000): AgnesImageApi {
        // Rewrite only after checking the production URL, preserving paths and headers.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertTrue(request.url.isHttps)
            if (request.method == "POST") assertEquals("${ProviderEndpoints.AGNES_BASE}/images/generations", request.url.toString())
            else assertEquals("https://cdn.example.test/picture.png", request.url.toString())
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }.build()
        return AgnesImageApi(http, timeoutMs)
    }

    private fun png(w: Int = 32, h: Int = 32): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun image(bytes: ByteArray = png()) = MockResponse().setBody("""{"data":[{"b64_json":"${b64(bytes)}"}]}""")
    private fun next(s: MockWebServer) = requireNotNull(s.takeRequest(4, TimeUnit.SECONDS))

    @Test fun `sizes use nearest supported ratios while the first returned image is untouched`() = runBlocking {
        val samples = listOf("1024x1024" to "1024x1024", "768x1024" to "768x1024", "1024x768" to "1024x768",
            "576x1024" to "576x1024", "1024x576" to "1024x576", "1344x576" to "1024x576")
        val aspects = listOf("1:1", "3:4", "4:3", "9:16", "16:9", "16:9")
        MockWebServer().use { server ->
            val api = client(server)
            for ((i, sample) in samples.withIndex()) {
                val (asked, sent) = sample
                val (w, h) = sent.split('x').map(String::toInt)
                val actual = png(w, h)
                server.enqueue(image(actual))
                val model = AgnesImages.models[i % 2].id
                val bytes = api.request(key, model, prompt, asked) {}
                val request = next(server)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("model", "prompt", "size", "n"), body.keys().asSequence().toSet())
                assertEquals(model, body.getString("model"))
                assertEquals(prompt, body.getString("prompt"))
                assertEquals(sent, body.getString("size"))
                assertEquals(1, body.getInt("n"))
                assertEquals("Bearer $key", request.getHeader("Authorization"))
                assertFalse(body.toString().contains(key))
                assertArrayEquals(actual, bytes)
                val decoded = ImageIO.read(ByteArrayInputStream(bytes))
                assertEquals(aspects[i], AgnesImages.aspectRatio(decoded.width, decoded.height))
            }
            assertEquals(samples.size, server.requestCount)
            assertEquals("1024x576", AgnesImages.sizeFromPrompt("crie uma imagem de um gato em 21:9"))
        }
        Unit
    }

    @Test fun `URL result has one unauthenticated GET and reports the delivered ratio`() = runBlocking {
        MockWebServer().use { server ->
            val picture = png(64, 48) // Return something other than the requested square.
            server.enqueue(MockResponse().setBody("""{"data":[{"url":"https://cdn.example.test/picture.png"},{"b64_json":"ignored"}]}"""))
            server.enqueue(MockResponse().setBody(Buffer().write(picture)))
            var checks = 0
            val bytes = client(server).request(key, AgnesImages.DEFAULT, prompt, "1024x1024") { checks++ }
            assertArrayEquals(picture, bytes)
            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals("4:3", AgnesImages.aspectRatio(decoded.width, decoded.height))
            assertEquals("POST", next(server).method)
            val download = next(server)
            assertEquals("GET", download.method)
            assertEquals("/picture.png", download.path)
            assertNull(download.getHeader("Authorization"))
            assertEquals(2, checks)
            assertEquals(2, server.requestCount)
        }
        Unit
    }

    @Test fun `images array accepts raw PNG base64 and data URLs`() = runBlocking {
        MockWebServer().use { server ->
            val expected = png()
            val api = client(server)
            for (value in listOf(b64(expected), "data:image/png;base64,${b64(expected)}")) {
                server.enqueue(MockResponse().setBody("""{"images":["$value","not used"]}"""))
                assertArrayEquals(expected, api.request(key, AgnesImages.DEFAULT, prompt, "1024x1024") {})
            }
            assertEquals(2, server.requestCount)
        }
        Unit
    }

    @Test fun `multiple references are sent as original PNG JPEG and WebP data urls in order`() = runBlocking {
        val samples = listOf("image/png" to png(), "image/jpeg" to byteArrayOf(-1, -40, -1, 42),
            "image/webp" to "RIFF0000WEBP".toByteArray())
        MockWebServer().use { server ->
            server.enqueue(image())
            client(server).request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", samples.map { it.second }) {}
            val body = JSONObject(next(server).body.readUtf8())
            val images = body.getJSONArray("image")
            assertEquals(samples.size, images.length())
            for ((i, sample) in samples.withIndex()) assertEquals("data:${sample.first};base64,${b64(sample.second)}", images.getString(i))
            assertFalse(body.has("extra_body"))
            assertEquals(1, server.requestCount)
        }
        Unit
    }

    @Test fun `only explicit top-level field rejection permits the documented reserve edit`() = runBlocking {
        MockWebServer().use { server ->
            val reference = png()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown field: image"}}"""))
            server.enqueue(image())
            var checks = 0
            client(server).request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", listOf(reference)) { checks++ }
            val first = JSONObject(next(server).body.readUtf8())
            val second = JSONObject(next(server).body.readUtf8())
            assertEquals("data:image/png;base64,${b64(reference)}", first.getJSONArray("image").getString(0))
            assertFalse(second.has("image"))
            assertEquals(first.getString("model"), second.getString("model"))
            assertEquals(first.getString("size"), second.getString("size"))
            assertEquals("url", second.getJSONObject("extra_body").getString("response_format"))
            assertEquals(first.getJSONArray("image").toString(), second.getJSONObject("extra_body").getJSONArray("image").toString())
            assertEquals(2, checks)
            assertEquals(2, server.requestCount)
        }
        Unit
    }

    @Test fun `post errors distinguish queue limit explicit payment empty picture and generic denial without replay`() = runBlocking {
        val cases = listOf(
            Triple(503, """{"message":"busy"}""", AgnesImageApi.Problem.QUEUE),
            Triple(403, """{"error":{"message":"usage limit reached"}}""", AgnesImageApi.Problem.LIMIT),
            Triple(400, """{"message":"Insufficient credits"}""", AgnesImageApi.Problem.PAYMENT),
            Triple(402, "{}", AgnesImageApi.Problem.PAYMENT),
            Triple(200, """{"message":"Saldo insuficiente"}""", AgnesImageApi.Problem.PAYMENT),
            Triple(403, """{"message":"Access denied"}""", AgnesImageApi.Problem.FAILED),
            Triple(200, """{"data":[],"images":[]}""", AgnesImageApi.Problem.NO_IMAGE),
        )
        MockWebServer().use { server ->
            val api = client(server, 4_000)
            for ((status, reply, expected) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody(reply))
                val error = runCatching { api.request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", listOf(png())) {} }.exceptionOrNull()
                assertEquals(expected, (error as? AgnesImageApi.Failure)?.problem)
                assertEquals("POST", next(server).method)
            }
            assertEquals(cases.size, server.requestCount)
        }
        Unit
    }

    @Test fun `oversize and unsupported references fail before transport`() = runBlocking {
        MockWebServer().use { server ->
            val api = client(server)
            suspend fun failure(bytes: ByteArray) = runCatching {
                api.request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", listOf(bytes)) { error("unexpected dispatch") }
            }.exceptionOrNull() as AgnesImageApi.Failure
            assertEquals(AgnesImageApi.Problem.REFERENCE_SIZE, failure(ByteArray(AgnesImages.MAX_REFERENCE_BYTES + 1)).problem)
            assertEquals(AgnesImageApi.Problem.REFERENCE_FORMAT, failure("GIF89a".toByteArray()).problem)
            assertEquals(0, server.requestCount)
        }
        Unit
    }

    @Test fun `timeout and cancellation do not replay ambiguous edits`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val short = client(server, 800)
            val failure = runCatching { short.request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", listOf(png())) {} }.exceptionOrNull()
            assertEquals(AgnesImageApi.Problem.TIMEOUT, (failure as? AgnesImageApi.Failure)?.problem)
            next(server)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = async(kotlinx.coroutines.Dispatchers.Default) { client(server).request(key, AgnesImages.DEFAULT, prompt, "1024x1024") {} }
            next(server)
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(2, server.requestCount)
        }
        Unit
    }

    @Test fun `revocation is checked before reserve edit and before a download`() = runBlocking {
        for (reply in listOf(
            MockResponse().setResponseCode(400).setBody("""{"message":"Unknown parameter image"}"""),
            MockResponse().setBody("""{"data":[{"url":"https://cdn.example.test/picture.png"}]}"""),
        )) {
            MockWebServer().use { server ->
                server.enqueue(reply)
                var attempts = 0
                val changed = IllegalStateException("fixture revocation")
                val failure = runCatching { client(server).request(key, AgnesImages.DEFAULT, "Edit", "1024x1024", listOf(png())) {
                    if (++attempts == 2) throw changed
                } }.exceptionOrNull()
                assertSame(changed, failure)
                assertEquals(1, server.requestCount)
            }
        }
        Unit
    }
}
