package ai.tama.providers

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderKeyEnrollmentTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun duplicateSubmissionCannotRemoveTheSavedCustomKey() {
        val config = File(dir.root, "instance")
        val secrets = File(dir.root, "synthetic-secret")
        fun create(key: String) = ProviderKeyEnrollment.add(config::exists,
            { secrets.takeIf(File::exists)?.readText() }, key,
            { secrets.writeText(it); true }, { config.writeText("fixture/account"); true },
            { if (it == null) secrets.delete() else secrets.writeText(it) })
        assertTrue(create("fixture-first"))
        assertFalse(create("fixture-duplicate"))
        // A fresh reader reopens the two files, not UI state.
        assertEquals("fixture/account", File(dir.root, "instance").readText())
        assertEquals("fixture-first", File(dir.root, "synthetic-secret").readText())
    }

    @Test fun failedStoreOrPublishDoesNotAcknowledgeAnOrphan() {
        for (storeSucceeds in listOf(true, false)) {
            var saved: String? = "fixture-before"
            var publishes = 0
            assertFalse(ProviderKeyEnrollment.add({ false }, { saved }, "fixture-new",
                { saved = it; storeSucceeds }, { publishes++; false }, { saved = it }))
            assertEquals("fixture-before", saved)
            assertEquals(if (storeSucceeds) 1 else 0, publishes)
        }
    }
}
