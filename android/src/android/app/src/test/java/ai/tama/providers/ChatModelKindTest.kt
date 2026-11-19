package ai.tama.providers

import org.junit.Assert.*
import org.junit.Test

class ChatModelKindTest {
    @Test fun `dedicated non-chat endpoints stay out without relying on display names`() {
        listOf("text-embedding-3-large", "nvidia/nv-embed-v1", "baai/bge-m3", "openai/omni-moderation-latest",
            "meta-llama/llama-guard-4-12b", "openai/gpt-oss-safeguard-20b", "nvidia/riva-translate-4b-instruct-v1.1")
            .forEach { assertFalse(it, ChatModelKind.accepts(it, listOf("text"), listOf("text"), false)) }
        assertFalse(ChatModelKind.accepts("custom-endpoint", listOf("text"), listOf("embedding"), false))
        assertFalse(ChatModelKind.accepts("image-drawer", listOf("text", "image"), listOf("text", "image"), false))
        assertFalse(ChatModelKind.accepts("recognizer", listOf("audio"), listOf("text"), false))
    }

    @Test fun `vision and legacy model ids remain available for conversation`() {
        listOf("openai/gpt-oss-120b", "gemini-2.5-flash", "nvidia/llama-3.1-nemotron-ultra-253b-v1", "agnes-2.5-flash")
            .forEach { assertTrue(it, ChatModelKind.accepts(it, null, null, false)) }
        assertTrue(ChatModelKind.accepts("a-vision-model", listOf("text", "image"), listOf("text"), false))
    }
}
