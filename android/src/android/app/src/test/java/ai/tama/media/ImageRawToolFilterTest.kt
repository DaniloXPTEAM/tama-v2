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
        assertEquals("Oi [removed] final", filtered("Oi <to", "ol_call>{secret</", "tool_call> final"))
        assertEquals("[removed] fim", filtered("<function=", "generate_image>{secret}</func", "tion> fim"))
        assertEquals("antes [removed]", filtered("antes <tool_call>{secret"))
        assertEquals("texto normal < 3", filtered("texto normal < 3"))
        assertEquals("texto <to", filtered("texto <to"))
        assertEquals("texto [removed]", filtered("texto <tool"))
        assertEquals("[removed][removed]", filtered("<TOOL_CALL>secret</TOOL_CALL><function=x>secret</function>"))
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
