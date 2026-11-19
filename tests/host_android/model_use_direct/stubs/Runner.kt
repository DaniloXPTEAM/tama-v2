import org.junit.runner.JUnitCore
fun main(args: Array<String>) {
    if (args.firstOrNull() == "normal") com.openminis.app.BuildConfig.APPLICATION_ID = "ai.tama.assistant"
    val result = JUnitCore.runClasses(
        ai.tama.net.AnthropicDirectModelUseTransportTest::class.java,
        ai.tama.net.DirectModelUseTransportTest::class.java,
        ai.tama.media.AgnesImageApiTest::class.java,
        ai.tama.providers.ChatModelKindTest::class.java,
        ai.tama.router.AutoModelStoreTest::class.java,
        ai.tama.net.ModelUseAttemptTrackerTest::class.java,
        ai.tama.net.DirectModelUseGuardTest::class.java,
        ai.tama.pricing.ModelCostGuardTest::class.java,
        ai.tama.pricing.FreeModelImportTest::class.java,
        ai.tama.pricing.DocumentedFreeChatTest::class.java,
        ai.tama.providers.ProviderKeyEnrollmentTest::class.java,
        ai.tama.providers.ReviewedModelFeaturesTest::class.java,
        ai.tama.pricing.NvidiaChatPricingTest::class.java,
        ai.tama.pricing.OpenRouterPricingTest::class.java,
        ModelUseHandlerHostTest::class.java,
        StreamReceiptHostTest::class.java,
        *(if (args.firstOrNull() == "normal") emptyArray<Class<*>>()
          else arrayOf(ai.tama.net.GeminiDocumentedChatWireTest::class.java,
              ai.tama.net.FreeChatCompletionsWireTest::class.java, ai.tama.net.KeyProbeWireTest::class.java)),
    )
    result.failures.forEach { System.err.println(it.trace) }
    println("identity=${com.openminis.app.BuildConfig.APPLICATION_ID} run=${result.runCount} ignored=${result.ignoreCount} assumptions=${result.assumptionFailureCount} failures=${result.failureCount}")
    check(result.wasSuccessful())
}
