"""Integration wiring only; the JVM wire tests cover actual serializer and SSE transport."""
from pathlib import Path
import unittest


MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class P01ChatWiring(unittest.TestCase):
    def test_selected_chat_can_read_evidence_and_scope_only_the_main_stream(self):
        chat = (MAIN / "com/openminis/app/ui/chat/ChatViewModel.kt").read_text()
        self.assertIn("if (nmOwnKeyTurn) nmEnsureFreeChatEvidence(provider)", chat)
        dispatch = chat.split("val needsFreeChat =", 1)[1].split("visibleStream.collect", 1)[0]
        for guard in ("!ai.tama.pricing.ModelCostGuard.permits()", "!ai.tama.router.AutomaticCostGuard.permits(context)",
                      "tamaFreeChatTicket(", "ownInstance != null && freeChat == null", "streamMessage(",
                      "providerStream.flowOn(freeChat)"):
            self.assertIn(guard, dispatch)
        self.assertNotIn("setAllowedForCurrentProcess", dispatch)
        self.assertNotIn("setAllowUnverifiedAutomaticCost", dispatch)
        self.assertEqual(1, chat.count("flowOn(freeChat)"))
        recovery = chat.split("val actual = unwrapFlowException(e)", 1)[1]
        self.assertLess(recovery.index("tamaRecordPaymentRefusal("), recovery.index("if (nmDispatchedFreeChat) throw actual"))
        self.assertLess(recovery.index("if (nmDispatchedFreeChat) throw actual"), recovery.index("val isTransient ="))

    def test_price_verification_is_not_import_permission(self):
        repo = (MAIN / "com/openminis/app/data/repository/ProviderRepository.kt").read_text()
        check = repo.split("private fun nmBeginCatalogueCheck", 1)[1].split("private fun nmBeginRefresh", 1)[0]
        self.assertIn("nmCatalogueGate.begin(live.id, false, route)", check)
        self.assertIn("nmOAuthKeyStillCurrent(key)", check)
        self.assertIn("nmCatalogueGate.isCurrent(check, false, AutoModelStore.routeFingerprint(live))", check)
        self.assertNotIn("replaceEntries(", check)
        self.assertNotIn("tamaImportFreeOnly", check)
        self.assertIn("OpenRouterModelsApi.fetchModels(apiKey, onTextCatalogue", repo)
        self.assertIn("nmReadOpenRouterCatalogue(instance, keySnapshot)", repo)
        self.assertNotIn("OpenRouterCatalogueCapture", repo)


if __name__ == "__main__":
    unittest.main()
