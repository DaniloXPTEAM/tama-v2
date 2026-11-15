package io.github.nanomuse.media

import com.openminis.app.tools.ToolExecutionResult
import io.github.nanomuse.router.Shortcut
import io.github.nanomuse.router.ShortcutParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageShortcutRunnerTest {
    private val failure = "May have been charged. Do not retry without a new request."

    @Test fun `a classified shortcut invokes only the image executor once`() = runBlocking {
        val image = ShortcutParser.parse("gera imagem de um pássaro") as Shortcut.Image
        var imageCalls = 0
        val answer = ImageShortcutRunner.run(image.prompt, failure, "Settings") { prompt ->
            imageCalls++
            assertEquals("um pássaro", prompt)
            ToolExecutionResult(JSONObject().put("markdown", "![bird](minis://attachments/bird.png)").toString(), true)
        }
        assertEquals("![bird](minis://attachments/bird.png)", answer.text)
        assertEquals(true, answer.success)
        assertEquals(1, imageCalls)
    }

    @Test fun `missing provider keeps the exact message and a settings link`() = runBlocking {
        val message = "Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo"
        val answer = ImageShortcutRunner.run("bird", failure, "Configurações") {
            ToolExecutionResult(JSONObject().put("error", "no_image_model").put("message", message)
                .put("settings", "minis://settings/media").toString(), false)
        }
        assertEquals("$message\n\n[Configurações](minis://settings/media)", answer.text)
        assertEquals(false, answer.success)
    }

    @Test fun `provider errors never echo text and never trigger a retry`() = runBlocking {
        var calls = 0
        val answer = ImageShortcutRunner.run("bird", failure, "Settings") {
            calls++
            ToolExecutionResult(JSONObject().put("error", "image_failed").put("message", "secret and prompt")
                .put("settings", "https://attacker.invalid").toString(), false)
        }
        assertEquals(failure, answer.text)
        assertEquals(false, answer.success)
        assertFalse(answer.text.contains("secret"))
        assertEquals(1, calls)
    }

    @Test fun `invalid success does not pretend an image was generated`() = runBlocking {
        val answer = ImageShortcutRunner.run("bird", failure, "Settings") {
            ToolExecutionResult("{\"markdown\":\"[fake](https://example.com)\"}", true)
        }
        assertEquals(failure, answer.text)
        assertEquals(false, answer.success)
    }
}
