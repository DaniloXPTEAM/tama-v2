package ai.tama.providers

import ai.tama.pricing.DocumentedFreeChat
import org.junit.Assert.*
import org.junit.Test

class ReviewedModelFeaturesTest {
    @Test fun codeIsATextFeatureNotPermissionToRunTools() {
        val base = DocumentedFreeChat.GEMINI_BASE
        val id = "gemma-4-31b-it"
        assertTrue(ReviewedModelFeatures.coding(base, id))
        assertFalse(ReviewedModelFeatures.coding("https://example.test/v1beta", id))
        assertFalse(ReviewedModelFeatures.coding(base, "$id:free"))
        val text = ModelModalities.from(listOf("text", "image"), listOf("text"), coding = true)
        assertTrue(text.coding)
        assertEquals(setOf("chat", "vision"), text.chatCapabilities)
        assertEquals(setOf("text"), text.outputs)
    }

    @Test fun vlAndOmniDoNotInheritAnotherVariantsModalitiesOrGenerateMedia() {
        val catalogue = listOf("text", "image", "video")
        val oldRow = catalogue + "audio_input"
        val vl = ModelModalities.from(oldRow, listOf("text"), exactInput = catalogue)
        val omni = ModelModalities.from(oldRow, listOf("text"), exactInput = catalogue + "audio")
        assertEquals(catalogue.toSet(), vl.inputs)
        assertEquals(catalogue.toSet() + "audio", omni.inputs)
        assertEquals(setOf("text"), omni.outputs)
        assertFalse(omni.coding)
    }
}
