package ai.tama.router

fun main() {
    check(LargerContext.eligible(4_000, 8_000))
    check(!LargerContext.eligible(4_000, 4_000))
    check(!LargerContext.eligible(4_000, 2_000))
    check(!LargerContext.eligible(4_000, null))
    check(!LargerContext.eligible(0, 8_000))
    check(!LargerContext.eligible(-1, 8_000))
    check(LargerContext.serverRejected(400, "context_length_exceeded"))
    check(!LargerContext.serverRejected(413, "payload byte limit"))
    check(!LargerContext.serverRejected(400, "max_tokens must be 2048"))
    check(!LargerContext.serverRejected(403, "context_length_exceeded"))
    check(!LargerContext.serverRejected(null, "context_length_exceeded"))
    println("PASSOU: alternativa de contexto exige limite conhecido e maior")
}
