package io.github.nanomuse.media

import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.router.AutoModelStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Exercises the actual offload handler with an in-process image service, never a paid API. */
class MediaOffloadImageTest {
    @get:Rule val temp = TemporaryFolder()

    private val selection = AutoImageSelection.Selection(
        "image-entry", "provider", "image-model", AutoModelStore.Identity("image-entry", "provider", "image-model", "route"), 0,
    )
    private val endpoint = ImageGen.Endpoint(
        ProviderInstance("provider", "Image provider", ProviderType.openAI, ProviderCredential.apiKey),
        "fixture-key", "image-model",
    )

    private fun context(): Context {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getString(R.string.nm_auto_image_missing)).thenReturn("Image permission or capability missing")
        Mockito.`when`(context.getString(R.string.nm_auto_image_changed)).thenReturn("Image authorization changed")
        Mockito.`when`(context.getString(R.string.nm_auto_image_edit_blocked)).thenReturn("Automatic edit unavailable")
        Mockito.`when`(context.getString(R.string.nm_auto_image_used, "image-model", "Image provider"))
            .thenReturn("Used image-model (Image provider)")
        return context
    }

    private fun request(session: String, vararg args: String) = NativeOffloadRequest(
        pid = 1, argv = listOf("nanomuse-media", "image") + args, env = emptyMap(), cwd = "/", sessionId = session,
    )

    private inner class FakeImages : MediaImageServices {
        var mode = true
        var chosen: AutoImageSelection.Selection? = selection
        var validates = 0
        var revokedOnValidation: Int? = null
        var selects = 0
        var manualCalls = 0
        var generateCalls = 0
        var editCalls = 0
        var lastFormatRetry: Boolean? = null
        var failure: Exception? = null
        var writeSucceeds = true
        override fun automatic() = mode
        override fun choose(): AutoImageSelection.Selection? { selects++; return chosen }
        override fun revalidate(selection: AutoImageSelection.Selection): ImageGen.Endpoint? {
            validates++
            return if (validates == revokedOnValidation) null else endpoint
        }
        override fun manualEndpoint(): ImageGen.Endpoint { manualCalls++; return endpoint }
        override fun agentEndpoint(): ImageGen.Endpoint? = if (!mode || chosen != null) endpoint else null
        override suspend fun generate(endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean): MediaPicture {
            generateCalls++
            lastFormatRetry = allowFormatRetry
            failure?.let { throw it }
            return MediaPicture { output ->
                if (writeSucceeds) output.write(byteArrayOf(1, 2, 3))
                writeSucceeds
            }
        }
        override suspend fun edit(endpoint: ImageGen.Endpoint, reference: Bitmap, prompt: String): MediaPicture {
            editCalls++
            return MediaPicture { output -> output.write(byteArrayOf(4)); true }
        }
    }

    @Test fun `missing authorization and automatic editing never invoke the image service`() {
        val fake = FakeImages().apply { chosen = null }
        val handler = MediaOffloadHandler(context(), fake)
        val sid = UUID.randomUUID().toString()
        val missing = handler.handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(3, missing.exitCode)
        assertEquals("no_approved_image_model", JSONObject(missing.output).getString("error"))
        assertEquals("minis://settings/models", JSONObject(missing.output).getString("settings"))
        assertEquals(0, fake.validates)
        val editing = handler.handle(request(sid, "--prompt", "Change it", "--from", "/private/reference.png"))
        assertEquals(3, editing.exitCode)
        assertEquals("automatic_image_edit_unavailable", JSONObject(editing.output).getString("error"))
        assertEquals(1, fake.selects) // only the missing-model attempt selected
        assertEquals(0, fake.generateCalls)
        assertEquals(0, fake.editCalls)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `revocation during validation stops without falling back or sending`() {
        val fake = FakeImages().apply { revokedOnValidation = 2 }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(3, result.exitCode)
        assertEquals("image_authorization_changed", JSONObject(result.output).getString("error"))
        assertEquals(2, fake.validates)
        assertEquals(0, fake.manualCalls)
        assertEquals(0, fake.generateCalls)
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `successful automatic request saves the selected result and queues session attribution`() {
        val fake = FakeImages()
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw a bird", "--name", "bird.png"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("image-model", json.getString("model"))
        assertEquals("Image provider", json.getString("provider"))
        assertEquals("Used image-model (Image provider)", json.getString("tell_user"))
        assertEquals(3, json.getInt("bytes"))
        assertTrue(temp.root.resolve("minis-sessions/$sid/attachments/bird.png").isFile)
        assertEquals(false, fake.lastFormatRetry)
        assertEquals(1, fake.generateCalls)
        assertEquals(0, fake.manualCalls)
        val notice = AutoImageNotices.takeFor(sid).single()
        assertEquals("image-model", notice.model)
        assertEquals("Image provider", notice.provider)
        assertFalse(result.output.contains(endpoint.apiKey))
    }

    @Test fun `manual mode keeps the configured route without adding automatic attribution`() {
        val fake = FakeImages().apply { mode = false }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(0, result.exitCode)
        assertEquals(1, fake.manualCalls)
        assertEquals(0, fake.selects)
        assertEquals(0, fake.validates)
        assertEquals(true, fake.lastFormatRetry)
        assertFalse(JSONObject(result.output).has("tell_user"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `a failed operation is redacted and never retried or attributed as success`() {
        val fake = FakeImages().apply { failure = IllegalStateException("provider echoed fixture-key") }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw a bird"))
        assertEquals(1, result.exitCode)
        assertEquals(1, fake.generateCalls)
        assertEquals(0, fake.manualCalls)
        assertFalse(result.output.contains("fixture-key"))
        assertTrue(result.output.contains("[redacted]"))
        assertTrue(JSONObject(result.output).getString("tell_user").contains("Do not retry"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `failed image encoding is not reported as a successful picture`() {
        val fake = FakeImages().apply { writeSucceeds = false }
        val sid = UUID.randomUUID().toString()
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw a bird", "--name", "failed.png"))
        assertEquals(1, result.exitCode)
        assertEquals("image_failed", JSONObject(result.output).getString("error"))
        assertFalse(temp.root.resolve("minis-sessions/$sid/attachments/failed.png").exists())
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }

    @Test fun `an existing attachment is never truncated by image output`() {
        val fake = FakeImages()
        val sid = UUID.randomUUID().toString()
        val file = temp.root.resolve("minis-sessions/$sid/attachments/keep.png")
        file.parentFile!!.mkdirs()
        file.writeText("original")
        val result = MediaOffloadHandler(context(), fake).handle(request(sid, "--prompt", "Draw", "--name", "keep.png"))
        assertEquals(1, result.exitCode)
        assertEquals("original", file.readText())
        assertTrue(JSONObject(result.output).getString("tell_user").contains("may have been charged"))
        assertTrue(AutoImageNotices.takeFor(sid).isEmpty())
    }
}
