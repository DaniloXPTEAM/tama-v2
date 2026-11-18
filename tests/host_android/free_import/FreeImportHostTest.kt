package ai.tama.pricing

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
    println("PASSOU: capacidades de catálogo filtradas por rota, conta, tempo e preço")
}
