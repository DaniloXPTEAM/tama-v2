package ai.tama.media

import java.util.Locale

/**
 * Removes literal tool transcripts from an assistant text stream. Once a known
 * marker is seen, drop the suffix of this completion: it can contain a forged
 * result or attachment even after the closing tag. This is not a tool parser.
 */
internal class ImageRawToolFilter(private val replacement: String) {
    private val pending = StringBuilder()
    private var discarded = false

    fun push(text: String): String {
        if (discarded) return ""
        if (pending.isEmpty() && '<' !in text) return text
        return buildString {
            for (char in text) {
                pending.append(char)
                while (pending.isNotEmpty()) {
                    val prefix = pending.toString().lowercase(Locale.ROOT)
                    if (prefix in markers) {
                        pending.setLength(0)
                        discarded = true
                        append(replacement)
                        return@buildString
                    }
                    if (markers.any { it.startsWith(prefix) }) break
                    append(pending[0])
                    pending.deleteCharAt(0)
                }
            }
        }
    }

    fun finish(): String {
        val value = pending.toString()
        pending.setLength(0)
        // The stream can end in the middle of a recognizable marker.
        val name = value.lowercase(Locale.ROOT).removePrefix("<").removePrefix("/")
        return if (tagPrefixes.any { name.startsWith(it) }) replacement else value
    }

    private companion object {
        val markers = setOf(
            "<tool_call", "</tool_call>", "<function=", "</function>",
            "<functioncalls>", "</functioncalls>", "<function_calls>", "</function_calls>",
            "<functionresult>", "</functionresult>", "<function_result>", "</function_result>",
            "<tool_result>", "</tool_result>", "<invoke name=", "</invoke>",
            "<shellexecute>", "</shellexecute>", "<shell_execute>", "</shell_execute>",
        )
        val tagPrefixes = setOf("tool", "function", "shell", "invoke")
    }
}
