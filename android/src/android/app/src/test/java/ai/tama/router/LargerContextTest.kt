package ai.tama.router

import org.junit.Assert.*
import org.junit.Test

class LargerContextTest {
    @Test fun onlyKnownStrictlyLargerWindowsQualify() {
        assertTrue(LargerContext.eligible(4000, 8000))
        assertFalse(LargerContext.eligible(4000, 4000))
        assertFalse(LargerContext.eligible(4000, 2000))
        assertFalse(LargerContext.eligible(4000, null))
        assertFalse(LargerContext.eligible(0, 8000))
    }

    @Test fun largerWindowStillRequiresApprovalCostCapabilitiesAndFit() {
        fun route(id: String, window: Int?) = ModelRouter.Candidate(
            entryId = id, providerId = id, capabilities = setOf(Need.CHAT, Need.TOOLS),
            profile = Profile.BASIC, inputTokens = 3_000, outputTokens = 500,
            contextLimit = window, automaticApproved = true, automaticCostAllowed = true,
        )
        val health = ModelHealth { 0L }
        val turn = AutoModelTurn(health)
        val all = listOf(
            route("same", 4_000), route("unknown", null),
            route("no-consent", 12_000).copy(automaticApproved = false),
            route("no-cost-consent", 14_000).copy(automaticCostAllowed = false),
            route("no-tools", 16_000).copy(capabilities = setOf(Need.CHAT)),
            route("hidden", 24_000).copy(visible = false),
            route("sufficient", 8_000),
        )
        val larger = all.filter { LargerContext.eligible(4_000, it.contextLimit) }
        val result = turn.pick(larger, setOf(Need.CHAT, Need.TOOLS), null) as ModelRouter.Choice.Selected
        assertEquals("sufficient", result.candidate.entryId)
        assertEquals(setOf(ModelRouter.Reason.NOT_APPROVED, ModelRouter.Reason.UNVERIFIED_COST,
            ModelRouter.Reason.MISSING_CAPABILITY, ModelRouter.Reason.HIDDEN), result.rejected.map { it.reason }.toSet())
        val unavailable = turn.pick(larger.filter { it.entryId != "sufficient" }, setOf(Need.CHAT, Need.TOOLS), null)
        assertTrue(unavailable is ModelRouter.Choice.None)
        // A local size failure alone must not create a provider health cooldown.
        assertNull(health.snapshot("same", "same").cooldown)
    }
}
