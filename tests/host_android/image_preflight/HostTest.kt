package ai.tama.media

fun main() {
    for (code in listOf("no_image_model", "no_approved_image_model", "automatic_image_cost_unverified",
        "image_cost_unverified", "image_authorization_changed", "permission_denied",
        "automatic_image_edit_unavailable", "invalid_image_request", "media_model_change_blocked")) {
        val instruction = ImageFailureInstruction.forCode(code)
        check("charged" !in instruction)
        check("Do not retry" in instruction)
    }
    val unknown = ImageFailureInstruction.forCode("image_failed")
    check("may have been charged" in unknown)
    check("Do not retry" in unknown)
    check("charged" !in ImageFailureInstruction.forCode("image_failed", neutralCharge = true))
    check(!OwnKeyImagePolicy.generates(true, listOf("text"), setOf("vision")))
    check(!OwnKeyImagePolicy.generates(false, listOf("image"), setOf("image_gen")))
    check(OwnKeyImagePolicy.generates(true, listOf("text", "image"), emptySet()))
    check(OwnKeyImagePolicy.generates(true, null, setOf("image_gen")))
    check(OwnKeyImagePolicy.reason(false) == OwnKeyImagePolicy.Reason.NO_GENERATOR)
    check(OwnKeyImagePolicy.reason(true) == OwnKeyImagePolicy.Reason.NO_VERIFIED_FREE_GENERATOR)
    println("PASSOU: avisos de bloqueio e falha sem rede")
}
