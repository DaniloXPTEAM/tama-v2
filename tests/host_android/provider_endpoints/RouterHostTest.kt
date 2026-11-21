import ai.tama.router.*
import ai.tama.net.OwnKeyFailureSignals
import ai.tama.net.OwnKeyFailureSignals.Hint
import ai.tama.providers.OwnKeyScope
import ai.tama.providers.ProviderDirectory

/** Selection policy only, no HTTP, Android, Cloud, pricing catalogue or chat UI. */
fun main() {
    val health = ModelHealth { 1000L }
    val turn = AutoModelTurn(health, modelScopedRateLimits = true)
    val choices = listOf("a", "b", "c").mapIndexed { i, id ->
        ModelRouter.Candidate(entryId = id, providerId = "synthetic-account-$id",
            capabilities = setOf(Need.CHAT), profile = Profile.LEAN,
            inputTokens = 12, outputTokens = 12, automaticApproved = true,
            automaticCostAllowed = true, verifiedFree = true, preference = i)
    }
    fun pick() = (turn.pick(choices, setOf(Need.CHAT), null) as ModelRouter.Choice.Selected).candidate
    val first = pick(); check(first.entryId == "a")
    turn.fail(first.entryId, first.providerId, ModelHealth.Failure.RATE_LIMIT, 7)
    val second = pick(); check(second.entryId == "b")
    turn.fail(second.entryId, second.providerId, ModelHealth.Failure.SERVER)
    val third = pick(); check(third.entryId == "c")
    turn.fail(third.entryId, third.providerId, ModelHealth.Failure.NETWORK)
    check(turn.pick(choices, setOf(Need.CHAT), null) is ModelRouter.Choice.None)
    val advertisedFree = choices[0].copy(entryId = "free", preference = null, verifiedFree = true)
    val unknown = choices[1].copy(entryId = "unknown", preference = null, verifiedFree = false)
    val ranked = ModelRouter.pick(listOf(unknown, advertisedFree), setOf(Need.CHAT)) as ModelRouter.Choice.Selected
    check(ranked.candidate.entryId == "free")
    val sticky = ModelRouter.pick(listOf(unknown, advertisedFree), setOf(Need.CHAT), "unknown") as ModelRouter.Choice.Selected
    check(sticky.candidate.entryId == "unknown")
    val freeBlocked = ModelRouter.pick(listOf(unknown, advertisedFree.copy(automaticCostAllowed = false)), setOf(Need.CHAT)) as ModelRouter.Choice.Selected
    check(freeBlocked.candidate.entryId == "unknown")
    val noCostConsent = choices.map { it.copy(automaticCostAllowed = false) }
    val blocked = ModelRouter.pick(noCostConsent, setOf(Need.CHAT)) as ModelRouter.Choice.None
    check(blocked.rejected.all { it.reason == ModelRouter.Reason.UNVERIFIED_COST })
    val lackingTools = ModelRouter.pick(choices, setOf(Need.CHAT, Need.TOOLS)) as ModelRouter.Choice.None
    check(lackingTools.rejected.all { it.reason == ModelRouter.Reason.MISSING_CAPABILITY })
    check(!OwnKeyScope.active(true, true, null, false))
    check(!OwnKeyScope.active(true, true, "   ", false))
    check(!OwnKeyScope.active(true, true, "fixture-relay", true))
    check(!OwnKeyScope.active(true, false, "fixture-oauth", false))
    check(!OwnKeyScope.active(false, true, "fixture-key", false))
    check(OwnKeyScope.active(true, true, "fixture-key", false))
    check(OwnKeyFailureSignals.detect(403, listOf("key_limit_exceeded"), "blocked") == Hint.SPEND_LIMIT)
    check(OwnKeyFailureSignals.detect(403, emptyList(), "Key limit exceeded (spent 0)") == Hint.SPEND_LIMIT)
    check(OwnKeyFailureSignals.detect(403, listOf("moderation"), "access denied") == Hint.POLICY_REFUSAL)
    check(OwnKeyFailureSignals.detect(403, emptyList(), "Forbidden") == null)
    check(OwnKeyFailureSignals.detect(404, listOf("model_decommissioned"), "retired") == Hint.MODEL_UNAVAILABLE)
    check(OwnKeyFailureSignals.detect(404, emptyList(), "Not found") == null)
    check(OwnKeyFailureSignals.detect(400, listOf("unsupported_parameter"), "model does not accept temperature") == null)
    val nvidia = ProviderDirectory.Vendor("nvidia", "NVIDIA", "@ai-sdk/openai-compatible", "https://integrate.api.nvidia.com/v1", null)
    val unknownVendor = ProviderDirectory.Vendor("other", "Other", "sdk-with-unknown-auth", "https://example.com/v1", null)
    check(ProviderDirectory.search(listOf(nvidia, unknownVendor), "Nvidia integrate") == listOf(nvidia))
    check(ProviderDirectory.endpoint(nvidia)?.base == "https://integrate.api.nvidia.com/v1")
    check(ProviderDirectory.endpoint(unknownVendor) == null)
    check(!ProviderDirectory.validCustomAddress("https://example.com/v1?key=fixture"))
    check(ProviderDirectory.validCustomAddress("https://example.com/v1"))
    val code = TurnRouter.route("Escreva um código de exemplo", preferTextCode = true)
    check(code.codeRequested && code.needs == setOf(Need.CHAT) && code.profile == Profile.LEAN)
    check(Need.TOOLS in TurnRouter.route("Escreva um código e rode", preferTextCode = true).needs)
    check(!TurnRouter.route("Conte uma história").codeRequested)
    val coder = advertisedFree.copy(entryId = "coder", codePreference = CodeModelPreference.rank(nvidia.base,
        "qwen/qwen3-coder-480b-a35b-instruct"))
    check(coder.codePreference > 0)
    fun winner(list: List<ModelRouter.Candidate>) =
        (ModelRouter.pick(list, code.needs, "free") as ModelRouter.Choice.Selected).candidate.entryId
    check(winner(listOf(advertisedFree, coder)) == "coder")
    check(winner(listOf(advertisedFree, coder.copy(automaticApproved = false))) == "free")
    check(winner(listOf(advertisedFree, coder.copy(automaticCostAllowed = false))) == "free")
    check(winner(listOf(advertisedFree.copy(preference = 0), coder.copy(preference = 1))) == "free")
    println("PASSOU: seleção e cooldowns fictícios; não testa chat, provedor ou rede")
}
