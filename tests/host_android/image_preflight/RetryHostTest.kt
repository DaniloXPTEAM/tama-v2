package ai.tama.router

fun main() {
    for (verb in listOf("faz", "cria", "gera", "desenha")) {
        val text = "$verb uma imagem de uma praia ao entardecer"
        var prompt: String? = null
        ImageShortcutEligibility.dispatch(text, ImageShortcutContext(person = true, parallelTest = true),
            image = { prompt = it.prompt }, chat = { error("Chamou chat para pedido de imagem") })
        check(prompt == "uma praia ao entardecer")
    }
    // Same dispatcher used by the composer; the typo must not touch a chat client.
    val photo = "crie uma iamgem de um gato astuto, estilo arte digital."
    val testContext = ImageShortcutContext(person = true, parallelTest = true)
    var drawing = ""
    ImageShortcutEligibility.dispatch(photo, testContext,
        image = { drawing = it.prompt }, chat = { error("Poco request was sent to chat") })
    check(drawing == "um gato astuto, estilo arte digital")
    check(ShortcutParser.parse(photo) == null)
    check(ImageShortcutEligibility.select("/imagem um gato", testContext)?.prompt == "um gato")
    check(ImageShortcutEligibility.select("/imagem um gato", testContext.copy(hasAttachments = true)) == null)
    check(ImageShortcutEligibility.select(photo, testContext.copy(batchSize = 2)) == null)
    val r = ImageRetryOffer()
    fun offer() = r.offer("session", "reply", "a blue cat", 1_000)
    offer()
    check(r.matches("session", "reply", "Sim!", true, 1_001))
    check(r.consume("session", "reply", "Sim!", true, 1_001)?.prompt == "a blue cat")
    check(r.consume("session", "reply", "sim", true, 1_002) == null)
    offer(); check(r.consume("session", "reply", "tenta de novo", true, 1_001)?.prompt == "a blue cat")
    offer(); check(r.consume("session", "reply", "sim, mas outra imagem", true, 1_001) == null)
    offer(); check(r.consume("session", "reply", "tente d renovo. vc mandou como texto não executou comando", true, 1_001) == null)
    offer(); check(r.consume("other", "reply", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "new-message", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sim", false, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sim", true, 999) == null)
    offer(); check(r.consume("session", "reply", "sim", true, 301_001) == null)
    offer(); r.clear(); check(r.consume("session", "reply", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sí", true, 1_001)?.prompt == "a blue cat")
    println("PASSOU: resposta ao convite de imagem exige sessão, última mensagem e intenção exata")
}
