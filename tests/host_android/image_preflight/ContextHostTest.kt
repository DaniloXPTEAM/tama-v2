package ai.tama.router

fun main() {
    check(LargerContext.eligible(4_000, 8_000))
    check(!LargerContext.eligible(4_000, 4_000))
    check(!LargerContext.eligible(4_000, 2_000))
    check(!LargerContext.eligible(4_000, null))
    check(!LargerContext.eligible(0, 8_000))
    check(!LargerContext.eligible(-1, 8_000))
    println("PASSOU: alternativa de contexto exige limite conhecido e maior")
}
