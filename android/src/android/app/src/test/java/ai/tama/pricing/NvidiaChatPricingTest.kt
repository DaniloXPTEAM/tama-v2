package ai.tama.pricing

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class NvidiaChatPricingTest {
    private val now = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    private val base = DocumentedFreeChat.NVIDIA_BASE

    @Test fun `only exact bundled NVIDIA text chat zero quotes can dispatch`() {
        val relative = "src/main/assets/models-dev-api.json"
        val file = listOf(File(relative), File("android/src/android/app/$relative")).first { it.isFile }
        val provider = JSONObject(file.readText()).getJSONObject("nvidia")
        assertEquals(base, provider.getString("api"))
        val models = provider.getJSONObject("models")
        assertEquals(103, models.length())
        assertEquals(99, models.keys().asSequence().count { NvidiaChatPricing.isZero(models.getJSONObject(it).optJSONObject("cost")) })
        fun allowed(id: String, base: String = this.base, at: Long = now): Boolean {
            val row = models.getJSONObject(id)
            val modalities = row.getJSONObject("modalities")
            fun values(key: String) = modalities.getJSONArray(key).let { arr -> (0 until arr.length()).map(arr::getString) }
            return NvidiaChatPricing.allows(base, id, NvidiaChatPricing.isZero(row.optJSONObject("cost")),
                values("input"), values("output"), at)
        }
        val free = "nvidia/nemotron-3-nano-30b-a3b"
        assertTrue(allowed(free))
        assertFalse(allowed(free, "https://example.test/v1"))
        assertFalse(allowed(free, at = Instant.parse("2027-02-18T00:00:00Z").toEpochMilli()))
        assertFalse(allowed("qwen/qwen-image"))
        assertFalse(allowed("nvidia/nv-embed-v1"))
        assertFalse(allowed("baai/bge-m3"))
        assertFalse(allowed("nvidia/riva-translate-4b-instruct-v1.1"))
        assertFalse(allowed("nvidia/nemotron-3-super-120b-a12b"))
    }

    @Test fun `no missing malformed or positive NVIDIA quote is zero`() {
        assertFalse(NvidiaChatPricing.isZero(null))
        for (json in listOf("""{"input":0}""", """{"input":0,"output":null}""",
            """{"input":"1e-500","output":"0"}""", """{"input":0,"output":0,"request":1}""")) {
            assertFalse(json, NvidiaChatPricing.isZero(JSONObject(json)))
        }
        assertTrue(NvidiaChatPricing.isZero(JSONObject("""{"input":0,"output":"0.0","cache_read":"0"}""")))
    }
}
