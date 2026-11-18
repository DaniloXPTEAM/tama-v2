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
}
