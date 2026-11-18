package ai.tama.router

fun main() {
    val r = ImageRetryOffer()
    fun offer() = r.offer("session", "reply", "a blue cat", 1_000)
    offer()
    check(r.matches("session", "reply", "Sim!", true, 1_001))
    check(r.consume("session", "reply", "Sim!", true, 1_001)?.prompt == "a blue cat")
    check(r.consume("session", "reply", "sim", true, 1_002) == null)
    offer(); check(r.consume("session", "reply", "tenta de novo", true, 1_001)?.prompt == "a blue cat")
    offer(); check(r.consume("session", "reply", "sim, mas outra imagem", true, 1_001) == null)
    offer(); check(r.consume("other", "reply", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "new-message", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sim", false, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sim", true, 999) == null)
    offer(); check(r.consume("session", "reply", "sim", true, 301_001) == null)
    offer(); r.clear(); check(r.consume("session", "reply", "sim", true, 1_001) == null)
    offer(); check(r.consume("session", "reply", "sí", true, 1_001)?.prompt == "a blue cat")
    println("PASSOU: resposta ao convite de imagem exige sessão, última mensagem e intenção exata")
}
