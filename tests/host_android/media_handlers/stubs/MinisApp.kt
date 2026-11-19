package com.openminis.app

import ai.tama.chat.SessionBindingSnapshot
import android.content.Context
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.flow.MutableStateFlow

// Real app repositories/Room are not present in the handler JVM fixture.
internal class MinisApp : Context() {
    val chatRepositoryOrNull: ChatRepository? = null
    val providerRepositoryOrNull: ProviderRepository? = null
}
internal class ChatRepository {
    suspend fun nmObserveBinding(sessionId: String): Pair<SessionBindingSnapshot, () -> Boolean>? = null
}
internal class ProviderRepository {
    val config = MutableStateFlow(ProviderConfig())
    fun tamaFreeChatOnly(instance: ProviderInstance): Boolean = false
}
