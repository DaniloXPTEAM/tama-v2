package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.pet.PetMapPreferences
import io.github.nanomuse.router.AutoModelStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class AutomaticModelUseGateTest {
    @Test fun `automatic mode blocks the direct run escape hatch before touching the repository`() {
        val context = Mockito.mock(Context::class.java)
        val preferences = PetMapPreferences()
        Mockito.`when`(context.getSharedPreferences("tama_auto_models", Context.MODE_PRIVATE)).thenReturn(preferences)
        Mockito.`when`(context.getString(R.string.nm_auto_model_use_blocked)).thenReturn("Direct use unavailable in automatic mode")
        val repository = Mockito.mock(ProviderRepository::class.java)
        val handler = ModelUseOffloadHandler(context, repository)
        val request = NativeOffloadRequest(1, listOf("minis-model-use", "run", "--model", "image"), emptyMap(), "/", "session")
        AutoModelStore.setEnabled(context, true)
        val blocked = handler.handle(request)
        assertEquals(3, blocked.exitCode)
        assertEquals("automatic_direct_model_use_unavailable", JSONObject(blocked.output).getString("error"))
        Mockito.verifyNoInteractions(repository)

        AutoModelStore.setEnabled(context, false)
        // Missing --model reaches the existing manual validation instead of the automatic gate.
        val manual = handler.handle(request.copy(argv = listOf("minis-model-use", "run")))
        assertEquals(2, manual.exitCode)
        assertTrue(manual.output.contains("--model is required"))
        Mockito.verifyNoInteractions(repository)
    }
}
