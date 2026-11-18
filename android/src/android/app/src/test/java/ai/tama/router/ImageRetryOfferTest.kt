package ai.tama.router

import org.junit.Assert.*
import org.junit.Test

class ImageRetryOfferTest {
    @Test fun `only a direct answer to the current offer can retry once`() {
        val offer = ImageRetryOffer()
        fun prepare() = offer.offer("session", "reply", "a blue cat", 1_000)
        prepare()
        assertTrue(offer.matches("session", "reply", "Sim!", true, 1_001))
        assertEquals("a blue cat", offer.consume("session", "reply", "sim", true, 1_001)?.prompt)
        assertNull(offer.consume("session", "reply", "sim", true, 1_001))
        prepare(); assertEquals("a blue cat", offer.consume("session", "reply", "tenta de novo", true, 1_001)?.prompt)
        prepare(); assertNull(offer.consume("session", "reply", "sim, mas outra imagem", true, 1_001))
        prepare(); assertNull(offer.consume("different", "reply", "sim", true, 1_001))
        prepare(); assertNull(offer.consume("session", "another-message", "sim", true, 1_001))
        prepare(); assertNull(offer.consume("session", "reply", "sim", false, 1_001))
        prepare(); assertNull(offer.consume("session", "reply", "sim", true, 999))
        prepare(); assertNull(offer.consume("session", "reply", "sim", true, 301_001))
        prepare(); offer.clear(); assertNull(offer.consume("session", "reply", "sim", true, 1_001))
    }
}
