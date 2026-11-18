package ai.tama.pricing

import ai.tama.providers.ModelModalities
import org.json.JSONArray
import org.json.JSONObject

/** Real catalogue policy and refresh gate, no Android, services, credentials or transport. */
fun main() {
    val now = 2_000_000_000_000L
    fun quote(id: String, price: String, tools: Boolean) = JSONObject()
        .put("id", id).put("pricing", JSONObject().put("prompt", price).put("completion", "0"))
        .put("architecture", JSONObject().put("input_modalities", JSONArray().put("text"))
            .put("output_modalities", JSONArray().put("text")))
        .put("endpoints", JSONArray().put("/api/v1/chat/completions"))
        .put("supported_parameters", if (tools) JSONArray().put("tools") else JSONArray())
    val prices = OpenRouterPricing.parse(JSONArray().put(quote("fixture/one:free", "0", true))
        .put(quote("fixture/two:free", "0", false)).put(quote("fixture/paid:free", "1", true)), now)
    val freeRows = listOf(
        FreeModelBulk.Entry("one", "instance", "fixture/one:free", false),
        FreeModelBulk.Entry("two", "instance", "fixture/two:free", false),
        FreeModelBulk.Entry("paid", "instance", "fixture/paid:free", false),
        FreeModelBulk.Entry("hidden", "instance", "fixture/hidden:free", true),
        FreeModelBulk.Entry("other", "other", "fixture/one:free", false),
        FreeModelBulk.Entry("", "instance", "fixture/blank:free", false),
    )
    val freeIds = setOf("fixture/one:free", "fixture/two:free")
    check(FreeModelBulk.select("instance", freeIds, freeRows) == listOf("one", "two"))
    check(FreeModelBulk.select("instance", emptySet(), freeRows).isEmpty())
    check(FreeModelBulk.select("missing", freeIds, freeRows).isEmpty())
    check(FreeModelBulk.select("instance", freeIds, freeRows +
        FreeModelBulk.Entry("hiddenDuplicate", "instance", "fixture/one:free", true)) == listOf("two"))
    check(FreeModelBulk.select("instance", freeIds, freeRows +
        FreeModelBulk.Entry("one", "other", "fixture/other:free", false)) == listOf("two"))
    val original = listOf("fixture/one:free", "fixture/paid:free", "fixture/two:free")
    check(FreeModelList.filter(original, true, freeIds) { it } == listOf("fixture/one:free", "fixture/two:free"))
    check(FreeModelList.filter(original, true, null) { it }.isEmpty())
    check(FreeModelList.filter(original, false, null) { it } == original)
    check(original.size == 3) // filter never removes saved rows
    check(!FreeModelList.restricted(hasOwnKey = false, requested = true, zeroSpendLimit = true))
    check(FreeModelList.restricted(hasOwnKey = true, requested = false, zeroSpendLimit = true))
    check(FreeModelList.restricted(hasOwnKey = true, requested = true, zeroSpendLimit = false))
    check(!FreeModelList.restricted(hasOwnKey = true, requested = false, zeroSpendLimit = false))
    val gate = ModelRefreshGate()
    val ticket = gate.begin("instance", true, "route")
    val evidence = FreeModelImportEvidence(ticket, 1, prices,
        setOf("fixture/one:free", "fixture/two:free", "fixture/paid:free"))
    fun caps(id: String, enabled: Boolean = true, time: Long = now, account: Long = 1, route: String = "route") =
        evidence.currentCapabilities(gate, enabled, route, account, time, id)
    check(caps("fixture/one:free") == setOf("chat", "tools"))
    check(caps("fixture/two:free") == setOf("chat"))
    check(caps("fixture/paid:free").isEmpty())
    check(caps("fixture/unknown:free").isEmpty())
    check(caps("fixture/one:free", enabled = false).isEmpty())
    check(caps("fixture/one:free", account = 2).isEmpty())
    check(caps("fixture/one:free", route = "changed").isEmpty())
    check(caps("fixture/one:free", time = now + OpenRouterPricing.MAX_AGE_MILLIS + 1).isEmpty())
    gate.begin("instance", true, "route")
    check(caps("fixture/one:free").isEmpty())
    val visionIds = listOf("google/gemma-4-31b-it:free", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free")
    val visionData = JSONArray()
    visionIds.forEach { id ->
        visionData.put(quote(id, "0", true).put("architecture", JSONObject()
            .put("input_modalities", JSONArray().put("text").put("IMAGE_INPUT"))
            .put("output_modalities", JSONArray().put("TEXT_OUTPUT"))))
    }
    val capTicket = gate.begin("instance", true, "route")
    val vision = FreeModelImportEvidence(capTicket, 1, OpenRouterPricing.parse(visionData, now), visionIds.toSet())
    visionIds.forEach { id ->
        check(vision.currentCapabilities(gate, true, "route", 1, now, id) == setOf("chat", "vision", "tools"))
    }
    val imageReader = ModelModalities.from(listOf("text", "image"), listOf("text"))
    check("vision" in imageReader.chatCapabilities && "image" !in imageReader.outputs.orEmpty())
    val imageWriter = ModelModalities.from(listOf("text"), listOf("image"))
    check("vision" !in imageWriter.chatCapabilities && "image" in imageWriter.outputs.orEmpty())
    val textOnly = ModelModalities.from(listOf("text"), listOf("text"))
    check(textOnly.chatCapabilities == setOf("chat"))
    val oldVoiceGuess = ModelModalities.from(listOf("text"), listOf("audio"), exactOutput = null)
    check("audio" !in oldVoiceGuess.outputs.orEmpty())
    val exactOmni = ModelModalities.from(listOf("text", "audio", "video", "image"), listOf("text"))
    check(exactOmni.inputs == setOf("text", "audio", "video", "image"))
    val review = java.time.Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    val gemini = DocumentedFreeChat.GEMINI_BASE
    val gemma = "gemma-4-31b-it"
    check(DocumentedFreeChat.status(gemini, gemma, review, false) == DocumentedFreeChat.Status.FREE)
    check(DocumentedFreeChat.status(gemini, "gemini-2.5-flash-image", review, true) == DocumentedFreeChat.Status.PAID)
    check(DocumentedFreeChat.status("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", review, true) ==
        DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED)
    var valid = true
    val grant = DocumentedChatDispatch(gemma, "fixture-key") { valid }
    DocumentedChatDispatch.checkGemini(grant, gemini, "fixture-key", gemma, true, review)
    check(!ModelCostGuard.permits())
    check(runCatching { DocumentedChatDispatch.checkGemini(grant, gemini, "other-fixture", gemma, true, review) }
        .exceptionOrNull() is ModelCostGuard.Blocked)
    valid = false
    check(runCatching { DocumentedChatDispatch.checkGemini(grant, gemini, "fixture-key", gemma, true, review) }
        .exceptionOrNull() is ModelCostGuard.Blocked)
    println("PASSOU: oferta exclusiva sem preco, pago conhecido, rota exata e conta revista")
    println("PASSOU: capacidades de catálogo filtradas por rota, conta, tempo e preço")
}
