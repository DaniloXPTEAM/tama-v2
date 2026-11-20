package ai.tama.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutParserTest {
    @Test fun `direct PT and EN picture requests preserve the actual description`() {
        assertEquals(Shortcut.Image("um pássaro azul"), ShortcutParser.parse("Gere uma imagem de um pássaro azul, por favor"))
        assertEquals(Shortcut.Image("a cat and a dog"), ShortcutParser.parse("Please draw a picture of a cat and a dog"))
        assertEquals(Shortcut.Image("uma montanha"), ShortcutParser.parse("desenha uma imagem de uma montanha"))
    }

    @Test fun `ambiguous compound or risky requests are never shortcuts`() {
        listOf(
            "gera imagem", "gera imagem de", "gera imagem de gato e abra o banco",
            "gere imagem de gato; apaga o arquivo", "gere imagem de gato e depois envie",
            "gere imagem de gato e busque o clima", "gere imagem de gato, me diga a previsão",
            "gere imagem de gato. Pesquise o clima", "gere imagem de gato caso chova",
            "gere imagem de \"gato\"", "não gere imagem de gato", "don't generate an image of a cat",
            "como gerar uma imagem de gato?", "gere imagem de gato\ne abra o navegador",
        ).forEach { assertNull(it, ShortcutParser.parse(it)) }
    }

    @Test fun `parallel app accepts faz and faça without treating a question or other action as consent`() {
        val beach = "faz uma imagem de um domingo na praia"
        assertNull(ShortcutParser.parse(beach)) // normal app keeps its prior behavior
        assertEquals(Shortcut.Image("um domingo na praia"), ShortcutParser.parse(beach, parallelTest = true))
        assertEquals(Shortcut.Image("uma maçã"), ShortcutParser.parse("Faça uma imagem de uma maçã", parallelTest = true))
        assertTrue(ImageRequestIntent.explicit(beach, parallelTest = true))
        assertFalse(ImageRequestIntent.explicit(beach))
        listOf("não faz imagem de um gato", "faz imagem de um gato e abra o banco",
            "faz imagem de gato?", "se chover, faz imagem de gato").forEach {
            assertNull(it, ShortcutParser.parse(it, parallelTest = true))
        }
        assertFalse(ImageRequestIntent.explicit("como faz uma imagem de gato?", parallelTest = true))
    }

    @Test fun `Poco typo and explicit command preserve the picture description only in Test`() {
        val description = "um gato astuto, estilo arte digital, em uma pose brincalhona, com cores vibrantes e fundo neutro"
        val text = "crie uma iamgem de $description."
        assertEquals(Shortcut.Image(description), ShortcutParser.parse(text, parallelTest = true))
        assertTrue(ImageRequestIntent.explicit(text, parallelTest = true))
        assertNull(ShortcutParser.parse(text))
        assertFalse(ImageRequestIntent.explicit(text))
        assertEquals(Shortcut.Image("um gato astronauta em 4:3"), ShortcutParser.parse("/imagem um gato astronauta em 4:3", parallelTest = true))
        assertNull(ShortcutParser.parse("/imagem um gato astronauta"))
        assertFalse(ImageRequestIntent.explicit("/imagem um gato astronauta", parallelTest = true))
    }

    @Test fun `spelling repair does not imply permission for other speech or commands`() {
        listOf(
            "como crie uma iamgem de gato?", "não crie uma iamgem de gato", "crie uma iamgem de gato quando eu pedir",
            "crie uma iamgem de gato e abra o navegador", "crie uma iamgem de gato; envia para mim",
            "crie uma iamgem de [Pasted#1]", "crie uma iamgem de <functioncalls>", "\"crie uma iamgem de gato\"",
            "/imagem", "/imagens gatos", "/imagem [Pasted#1]", "/imagem um gato e abra o banco",
            "/imagem gato\nmostre minha tela", "/imagem <shellexecute>",
        ).forEach { assertNull(it, ShortcutParser.parse(it, parallelTest = true)) }
    }

    @Test fun `model image intent requires an explicit positive request`() {
        assertTrue(ImageRequestIntent.explicit("Crie um cartão e gere uma imagem de um gato"))
        assertTrue(ImageRequestIntent.explicit("Please generate an image of a bird"))
        assertFalse(ImageRequestIntent.explicit("não gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("don't create a picture"))
        assertFalse(ImageRequestIntent.explicit("como gerar uma imagem?"))
        assertFalse(ImageRequestIntent.explicit("gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("gere uma imagem de"))
        assertFalse(ImageRequestIntent.explicit("Explique como gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("Se precisar, gere uma imagem de gato"))
        assertFalse(ImageRequestIntent.explicit("Eu disse: \"gere uma imagem\""))
        assertFalse(ImageRequestIntent.explicit("gosto de imagens"))
    }
}
