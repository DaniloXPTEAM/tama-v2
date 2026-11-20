package ai.tama.media

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Display-only filter. Native tool events pass through unchanged. */
internal object ImageToolStream {
    fun filter(upstream: Flow<LLMStreamChunk>, replacement: String): Flow<LLMStreamChunk> = flow {
        val filter = ImageRawToolFilter(replacement)
        upstream.collect { chunk ->
            if (chunk is LLMStreamChunk.Text) {
                val text = filter.push(chunk.text)
                if (text.isNotEmpty()) emit(LLMStreamChunk.Text(text))
            } else {
                // Flush a held suffix before the terminal event, not after it.
                if (chunk is LLMStreamChunk.Finished) {
                    val rest = filter.finish()
                    if (rest.isNotEmpty()) emit(LLMStreamChunk.Text(rest))
                }
                emit(chunk)
            }
        }
        val rest = filter.finish()
        if (rest.isNotEmpty()) emit(LLMStreamChunk.Text(rest))
    }
}
