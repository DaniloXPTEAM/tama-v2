package ai.tama.media

/**
 * Filters literal tool syntax from an image reply, including markers split across
 * stream chunks. It never parses or executes text as a tool. Pending memory is
 * bounded by the marker length, even when a provider never closes the tag.
 */
internal class ImageRawToolFilter(private val replacement: String) {
    private val markers = listOf("<tool_call", "<function=")
    private val pending = StringBuilder()
    private val tail = StringBuilder()
    private var close: String? = null

    fun push(text: String): String = buildString {
        for (char in text) {
            val closing = close
            if (closing != null) {
                tail.append(char.lowercaseChar())
                if (tail.length > closing.length) tail.deleteCharAt(0)
                if (tail.toString() == closing) { close = null; tail.setLength(0) }
                continue
            }
            pending.append(char)
            while (pending.isNotEmpty()) {
                val prefix = pending.toString().lowercase(java.util.Locale.ROOT)
                val match = markers.firstOrNull { it == prefix }
                if (match != null) {
                    append(replacement)
                    pending.setLength(0)
                    close = if (match == "<tool_call") "</tool_call>" else "</function>"
                    tail.setLength(0)
                    break
                }
                if (markers.any { it.startsWith(prefix) }) break
                append(pending[0])
                pending.deleteCharAt(0)
            }
        }
    }

    fun finish(): String {
        if (close != null) { pending.setLength(0); return "" }
        val value = pending.toString()
        pending.setLength(0)
        val lower = value.lowercase(java.util.Locale.ROOT)
        return if (lower.startsWith("<tool") || lower.startsWith("<function")) replacement else value
    }
}
