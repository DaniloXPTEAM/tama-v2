import ai.tama.router.ModelRouteHistory
import ai.tama.router.ImageRequestIntent
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

fun main() {
    val args = JSONObject()
    val call = AgentContentPart.ToolUse("synthetic-call", "read", args, "opaque")
    val result = AgentContentPart.ToolResult("synthetic-call", "read", "synthetic result")
    val assistant = LLMMessage(LLMMessage.Role.ASSISTANT, "answer", contentParts = listOf(call),
        reasoningContent = "private", originModelId = "m", originProviderInstanceId = "p", originRouteFingerprint = "f")
    val user = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result))
    val input = listOf(assistant, user)
    check(ModelRouteHistory.forProvider(input, "p", "m", "f")[0] === assistant)
    for ((provider, model, fingerprint) in listOf(Triple("q", "m", "f"), Triple("p", "n", "f"), Triple("p", "m", "g"))) {
        val out = ModelRouteHistory.forProvider(input, provider, model, fingerprint)
        check(out[0].reasoningContent == null)
        check((out[0].contentParts.single() as AgentContentPart.ToolUse).let { it.id == "synthetic-call" && it.thoughtSignature == null })
        check(out[1] === user && (out[1].contentParts.single() as AgentContentPart.ToolResult).content == "synthetic result")
    }
    check(assistant.reasoningContent == "private" && call.thoughtSignature == "opaque")
    check(ImageRequestIntent.explicit("faça uma imagem de uma praia", parallelTest = true))
    check(!ImageRequestIntent.explicit("não faça uma imagem de praia", parallelTest = true))
    check(!ImageRequestIntent.explicit("como faço uma imagem?", parallelTest = true))
    println("PASSOU: histórico entre rotas e intenção de imagem; simulação sem rede")
}
