package ai.tama.net

/** Coarse stage timings only: no request content, model, URL, session or credential. */
internal class GeminiStreamTiming(private val now: () -> Long = System::nanoTime) {
    private val start = now()
    private var sent: Long? = null
    private var headers: Long? = null
    private var text: Long? = null

    fun dispatch() { sent = now() }
    fun headers() { headers = now() }
    fun firstText() { if (text == null) text = now() }

    private fun ms(from: Long?, until: Long?): String =
        if (from == null || until == null) "-" else ((until - from).coerceAtLeast(0) / 1_000_000L).toString()

    fun line(requestBytes: Long): String =
        "prepare_ms=${ms(start, sent)} http_headers_ms=${ms(sent, headers)} " +
            "first_text_ms=${ms(sent, text)} total_ms=${ms(start, now())} request_bytes=$requestBytes"
}
