import ai.tama.router.*

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
    println("PASSOU: seleção e cooldowns fictícios; não testa chat, provedor ou rede")
}
