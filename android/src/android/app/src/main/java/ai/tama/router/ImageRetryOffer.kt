package ai.tama.router

/** Short-lived, in-memory retry offered only after a standalone image request failed.
 * It grants no permission and is consumed by the next user message, even if declined. */
internal class ImageRetryOffer {
    private class Pending(val session: String, val lastMessageId: String, val prompt: String, val sinceMillis: Long, val untilMillis: Long)
    private var pending: Pending? = null

    @Synchronized
    fun offer(session: String, lastMessageId: String, prompt: String, nowMillis: Long) {
        pending = if (session.isNotBlank() && lastMessageId.isNotBlank() && prompt.isNotBlank() &&
            nowMillis >= 0 && nowMillis <= Long.MAX_VALUE - TTL_MILLIS)
            Pending(session, lastMessageId, prompt, nowMillis, nowMillis + TTL_MILLIS) else null
    }

    @Synchronized
    fun clear() { pending = null }

    @Synchronized
    fun matches(session: String, lastMessageId: String?, text: String, eligible: Boolean, nowMillis: Long): Boolean {
        val saved = pending ?: return false
        if (!eligible || saved.session != session || saved.lastMessageId != lastMessageId ||
            nowMillis < saved.sinceMillis || nowMillis > saved.untilMillis) return false
        val reply = ShortcutParser.normalized(text.trim()).replace(Regex("[.!。！]+$"), "").trim()
        return reply in replies
    }

    @Synchronized
    fun consume(session: String, lastMessageId: String?, text: String, eligible: Boolean, nowMillis: Long): Shortcut.Image? {
        val saved = pending
        val matches = matches(session, lastMessageId, text, eligible, nowMillis)
        pending = null
        return if (matches && saved != null) Shortcut.Image(saved.prompt) else null
    }

    companion object {
        private const val TTL_MILLIS = 5 * 60 * 1000L
        private val replies = setOf(
            "sim", "sim, por favor", "sim por favor", "tenta de novo", "tente de novo",
            "tenta novamente", "tente novamente", "tentar novamente", "tenta de novo, por favor", "tente novamente, por favor",
            "yes", "try again", "si", "reintentar", "ja", "oui", "oo", "ya", "はい", "もう一度",
            "네", "다시 시도", "tak", "da", "да", "повторить", "ใช่", "ลองอีกครั้ง", "evet", "是", "重试", "重試",
        )
    }
}
