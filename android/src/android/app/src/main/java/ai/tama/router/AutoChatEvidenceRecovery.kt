package ai.tama.router

/** A read-only catalogue lookup is not a dispatch, an import, or a grant. */
internal object AutoChatEvidenceRecovery {
    private val catalogueNeeds = setOf(Need.CHAT, Need.TOOLS)

    fun candidates(
        none: ModelRouter.Choice.None,
        choices: List<ModelRouter.Candidate>,
        needs: Set<Need>,
    ): Set<String> {
        val rejected = none.rejected.filter {
            it.reason in setOf(ModelRouter.Reason.UNVERIFIED_COST, ModelRouter.Reason.NO_CREDENTIAL,
                ModelRouter.Reason.MISSING_CAPABILITY)
        }.mapTo(hashSetOf()) { it.entryId }
        // On reopen the expired catalogue can be precisely what was providing chat and
        // tools metadata. Vision (or any other missing feature) cannot come from this check.
        return choices.filter { candidate ->
            candidate.entryId in rejected && candidate.enabled && candidate.visible && candidate.automaticApproved &&
                catalogueNeeds.containsAll(needs - candidate.capabilities)
        }.mapTo(hashSetOf()) { it.entryId }
    }
}
