package ai.tama.pricing

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FreeModelImportTest {
    private val now = 2_000_000_000_000L
    private fun quote(id: String, prompt: String? = "0", completion: String? = "0") = JSONObject()
        .put("id", id)
        .put("pricing", JSONObject().apply { if (prompt != null) put("prompt", prompt); if (completion != null) put("completion", completion) })
        .put("architecture", JSONObject().put("input_modalities", JSONArray().put("text"))
            .put("output_modalities", JSONArray().put("text")))
        .put("endpoints", JSONArray().put("/api/v1/chat/completions"))
    private fun model(id: String) = LLMModel(id, id, "Fixture")

    @Test fun `bulk route selection excludes other accounts hidden entries and paid ids`() {
        val rows = listOf(
            FreeModelBulk.Entry("one", "a", "vendor/one:free", false),
            FreeModelBulk.Entry("two", "a", "vendor/two:free", false),
            FreeModelBulk.Entry("paid", "a", "vendor/paid:free", false),
            FreeModelBulk.Entry("hidden", "a", "vendor/hidden:free", true),
            FreeModelBulk.Entry("other", "b", "vendor/one:free", false),
        )
        assertEquals(listOf("one", "two"), FreeModelBulk.select("a", setOf("vendor/one:free", "vendor/two:free"), rows))
        assertTrue(FreeModelBulk.select("a", emptySet(), rows).isEmpty())
        val free = setOf("vendor/one:free", "vendor/two:free")
        assertEquals(listOf("two"), FreeModelBulk.select("a", free, rows +
            FreeModelBulk.Entry("duplicate", "a", "vendor/one:free", true)))
        assertEquals(listOf("two"), FreeModelBulk.select("a", free, rows +
            FreeModelBulk.Entry("one", "b", "vendor/other:free", false)))
    }

    @Test fun `free list is a reversible view and unknown evidence is not free`() {
        val original = listOf("vendor/one:free", "vendor/paid", "vendor/two:free")
        val free = setOf("vendor/one:free", "vendor/two:free")
        assertEquals(listOf("vendor/one:free", "vendor/two:free"), FreeModelList.filter(original, true, free) { it })
        assertTrue(FreeModelList.filter(original, true, null) { it }.isEmpty())
        assertEquals(original, FreeModelList.filter(original, false, null) { it })
        assertEquals(3, original.size)
    }

    @Test fun `only fresh evidence for exact zero-price IDs is eligible`() {
        val raw = JSONArray().put(quote("vendor/a:free")).put(quote("openrouter/free"))
            .put(quote("vendor/paid:free", "0.01"))
            .put(quote("vendor/incomplete:free", completion = null))
            .put(quote("vendor/not-a-variant"))
            .put(quote("vendor/duplicate:free")).put(quote("vendor/duplicate:free"))
        val catalogue = OpenRouterPricing.parse(raw, now)
        val list = listOf("vendor/a:free", "openrouter/free", "vendor/paid:free", "vendor/incomplete:free",
            "vendor/not-a-variant", "vendor/duplicate:free", "vendor/absent:free", "vendor/a:free").map(::model)
        assertEquals(listOf("vendor/a:free", "openrouter/free", "vendor/not-a-variant"), FreeModelImport.eligible(list, catalogue, now).map { it.id })
        assertTrue(FreeModelImport.eligible(list, null, now).isEmpty())
        assertTrue(FreeModelImport.eligible(list, catalogue, now + OpenRouterPricing.MAX_AGE_MILLIS + 1).isEmpty())
    }

    @Test fun `Agnes summary never counts declared image evidence as free chat`() {
        val images = setOf("agnes-image-2.1-flash", "agnes-image-2.0-flash")
        val visible = images + setOf("agnes-2.5-flash", "agnes-3.0-flash")
        assertEquals(FreeModelImportStatus.Complete(0, 0, 2, declaredImages = 2),
            FreeModelImport.summarize(visible, images, added = 0, declaredImageIds = images))
        assertEquals(FreeModelImportStatus.Complete(0, 0, 2, declaredImages = 1),
            FreeModelImport.summarize(visible - "agnes-image-2.1-flash", emptySet(), added = 0, declaredImageIds = images))
        assertEquals(FreeModelImportStatus.Complete(1, 1, 1),
            FreeModelImport.summarize(setOf("fixture/free", "fixture/unknown"), setOf("fixture/free"), added = 1))
    }

    @Test fun `import is additive idempotent and preserves customized existing entries`() {
        val preserved = ModelEntry("one", model("vendor/a:free"), overrides = ModelOverrides(displayName = "My model"), isHidden = true, uuid = "stable")
        val other = ModelEntry("two", model("vendor/b:free"), uuid = "other")
        val existing = listOf(preserved, other)
        val eligible = listOf(model("vendor/a:free"), model("vendor/b:free"), model("vendor/b:free"))
        val added = FreeModelImport.additions("one", existing, eligible)
        assertEquals(listOf("vendor/b:free"), added.map { it.baseModel.id })
        assertEquals("one", added.single().providerInstanceId)
        assertEquals("stable", existing.first().id)
        assertEquals("My model", existing.first().model.displayName)
        assertTrue(existing.first().isHidden)
        assertTrue(FreeModelImport.additions("one", existing + added, eligible).isEmpty())
    }

    @Test fun `unsupported providers never claim verified free imports`() {
        assertTrue(FreeModelImport.supports(ProviderType.openRouter))
        for (type in ProviderType.entries.filter { it != ProviderType.openRouter }) assertFalse(type.name, FreeModelImport.supports(type))
    }

    @Test fun `only latest matching provider mode and route can commit including ABA changes`() {
        val gate = ModelRefreshGate()
        val a = gate.begin("one", true, "route-a")
        val other = gate.begin("two", true, "route-a")
        assertTrue(gate.isCurrent(a, true, "route-a"))
        assertFalse(gate.isCurrent(a, false, "route-a"))
        assertFalse(gate.isCurrent(a, true, "route-b"))
        gate.invalidate("one")
        assertFalse(gate.isCurrent(a, true, "route-a"))
        assertTrue(gate.isCurrent(other, true, "route-a"))
        val b = gate.begin("one", false, "route-b")
        val laterA = gate.begin("one", true, "route-a")
        assertFalse(gate.isCurrent(a, true, "route-a"))
        assertFalse(gate.isCurrent(b, false, "route-b"))
        assertTrue(gate.isCurrent(laterA, true, "route-a"))
        gate.invalidateAll()
        assertFalse(gate.isCurrent(laterA, true, "route-a"))
        assertFalse(gate.isCurrent(other, true, "route-a"))
    }

    @Test fun `fresh exact catalogue supplies chat and declared tools without the import flag`() {
        val withTools = quote("vendor/tools").put("supported_parameters", JSONArray().put("tools"))
        val withoutTools = quote("vendor/simple:free")
        val paid = quote("vendor/paid:free", "0.1").put("supported_parameters", JSONArray().put("tools"))
        val catalogue = FreeChatCatalogue.OpenRouter(OpenRouterPricing.parse(JSONArray().put(withTools).put(withoutTools).put(paid), now))
        val evidence = FreeChatCatalogueEvidence("route-a", 7, catalogue)
        fun caps(id: String, time: Long = now, version: Long = 7, route: String = "route-a") =
            (evidence.current(route, version, time) as? FreeChatCatalogue.OpenRouter)?.capabilities(id, time).orEmpty()
        assertEquals(setOf("chat", "tools"), caps("vendor/tools"))
        assertEquals(setOf("chat"), caps("vendor/simple:free"))
        for (id in listOf("vendor/paid:free", "vendor/missing:free", "vendor/tools:free")) assertTrue(caps(id).isEmpty())
        assertTrue(caps("vendor/tools", time = now + OpenRouterPricing.MAX_AGE_MILLIS + 1).isEmpty())
        assertTrue(caps("vendor/tools", version = 8).isEmpty())
        assertTrue(caps("vendor/tools", route = "other").isEmpty())
    }

    @Test fun `send candidates require the saved route account and unexpired quote`() {
        val soon = quote("vendor/soon:free").put("expiration_date", java.time.Instant.ofEpochMilli(now + 1_000).toString())
        val catalogue = FreeChatCatalogue.OpenRouter(OpenRouterPricing.parse(JSONArray().put(quote("vendor/a")).put(soon)
            .put(quote("vendor/paid:free", "0.1")), now))
        val evidence = FreeChatCatalogueEvidence("route-a", 7, catalogue)
        val current = evidence.current("route-a", 7, now) as FreeChatCatalogue.OpenRouter
        assertEquals(DocumentedFreeChat.Status.FREE, current.status("vendor/a", now))
        assertEquals(DocumentedFreeChat.Status.PAID, current.status("vendor/paid:free", now))
        assertEquals(DocumentedFreeChat.Status.FREE, current.status("vendor/soon:free", now))
        assertEquals(DocumentedFreeChat.Status.UNKNOWN, current.status("vendor/soon:free", now + 1_000))
        assertNull(evidence.current("route-b", 7, now))
        assertNull(evidence.current("route-a", 8, now)) // key A → B → A still has a new generation
        assertNull(evidence.current("route-a", 7, now - 1))
        assertNull(evidence.current("route-a", 7, now + OpenRouterPricing.MAX_AGE_MILLIS + 1))
    }
}
