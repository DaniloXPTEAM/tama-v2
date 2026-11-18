package ai.tama.media

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    fun filtered(vararg chunks: String): String {
        val f = ImageRawToolFilter("[removed]")
        return chunks.joinToString("") { f.push(it) } + f.finish()
    }
    check(filtered("Oi <to", "ol_call>{private</", "tool_call> final") == "Oi [removed] final")
    check(filtered("<function=", "generate_image>{secret}</func", "tion>", " fim") == "[removed] fim")
    check(filtered("antes <tool_call>{private") == "antes [removed]")
    check(filtered("texto normal < 3") == "texto normal < 3")
    check(filtered("texto <to") == "texto <to")
    check(filtered("texto <tool") == "texto [removed]")
    check(filtered("<TOOL_CALL>secret</TOOL_CALL><function=x>secret</function>") == "[removed][removed]")
    val raw: List<LLMStreamChunk> = listOf(LLMStreamChunk.Text("Olá <tool_"),
        LLMStreamChunk.Text("call>private</tool_call>"), LLMStreamChunk.ToolUseStart("id", "native"),
        LLMStreamChunk.Finished("stop"))
    val events = ImageToolStream.filter(flowOf(*raw.toTypedArray()), "[removed]").toList()
    check(events.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text } == "Olá [removed]")
    check(events.filterIsInstance<LLMStreamChunk.ToolUseStart>().single() == LLMStreamChunk.ToolUseStart("id", "native"))
    check(events.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason == "stop")
    println("PASSOU: blocos de ferramenta em texto filtrados entre chunks; eventos nativos preservados")
}
