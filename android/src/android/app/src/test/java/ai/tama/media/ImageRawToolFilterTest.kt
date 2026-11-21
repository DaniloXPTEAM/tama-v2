package ai.tama.media

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageRawToolFilterTest {
    private fun filtered(vararg chunks: String): String {
        val filter = ImageRawToolFilter("[removed]")
        return chunks.joinToString("") { filter.push(it) } + filter.finish()
    }

    @Test fun textMarkersSplitAcrossChunksDoNotLeak() {
        assertEquals("Oi [removed]", filtered("Oi <to", "ol_call>{secret</", "tool_call> final"))
        assertEquals("[removed]", filtered("<function=", "generate_image>{secret}</func", "tion> fim"))
        assertEquals("antes [removed]", filtered("antes <tool_call>{secret"))
        assertEquals("texto normal < 3", filtered("texto normal < 3"))
        assertEquals("texto <to", filtered("texto <to"))
        assertEquals("texto [removed]", filtered("texto <tool"))
        assertEquals("[removed]", filtered("<TOOL_CALL>secret</TOOL_CALL><function=x>secret</function>"))
    }

    @Test fun `Poco transcripts cannot show a fake JSON result or an image link`() {
        val prefix = "Vou tentar.\n"
        val fake = """{"ok":true,"path":"/var/minis/attachments/gato.png","markdown":"![gato](minis://attachments/gato.png)"}"""
        val payloads = listOf(
            "<functioncalls><invoke name=\"nanomuse-media.image\">gato</invoke></functioncalls><functionresult>$fake</function_result>",
            "<shellexecute><argparam>path</argkey><argvalue>nanomuse-media status</argvalue></tool_call>",
            "<function_result>$fake</functionresult>",
            "<TOOL_CALL>fictício</TOOL_CALL>$fake",
        )
        for (payload in payloads) {
            val text = "$prefix$payload\nAqui está: ![gato](minis://attachments/gato.png)"
            for (i in text.indices) assertEquals("split $i", prefix + "[removed]", filtered(text.take(i), text.drop(i)))
            assertEquals(prefix + "[removed]", filtered(*text.map(Char::toString).toTypedArray()))
        }
        assertEquals(prefix + "[removed]", filtered(prefix, "</function_re"))
        assertEquals("```json\n{\"ok\":true}\n```", filtered("```json\n{\"ok\":true}\n```"))
    }

    @Test fun `finish event follows any pending text`() = runBlocking {
        val end = LLMStreamChunk.Finished("stop")
        val result = ImageToolStream.filter(flowOf(LLMStreamChunk.Text("texto <shell"), end), "[removed]").toList()
        assertEquals("texto [removed]", result.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertSame(end, result.last())
    }

    @Test fun nativeToolEventsAreNotConvertedOrExecuted() = runBlocking {
        val native = LLMStreamChunk.ToolUseStart("id", "native")
        val result = ImageToolStream.filter(flowOf(LLMStreamChunk.Text("<tool_"),
            LLMStreamChunk.Text("call>secret</tool_call>"), native, LLMStreamChunk.Finished("stop")), "[removed]").toList()
        assertEquals("[removed]", result.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertSame(native, result.filterIsInstance<LLMStreamChunk.ToolUseStart>().single())
        assertEquals("stop", result.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }
}
