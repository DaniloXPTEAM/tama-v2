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
    println("PASSOU: avisos de bloqueio e falha sem rede")
}
