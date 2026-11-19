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
        self.assertLess(recovery.index("tamaRecordPaymentRefusal("), recovery.index("if (nmDispatchedFreeChat) {"))
        self.assertLess(recovery.index("if (nmDispatchedFreeChat) {"), recovery.index("val isTransient ="))
        free_failure = recovery.split("if (nmDispatchedFreeChat) {", 1)[1].split("val freeOnlyBlocked", 1)[0]
        self.assertIn("canSwitchFreeChat(decision,", free_failure)
        self.assertIn("nmOwnKeyTurn && nmAutoTurn != null, nmReceivedFreeChatChunk", free_failure)
        self.assertIn("nmForceFreeRemainder = true", free_failure)
        self.assertIn("if (!nmDispatchedFreeChat && (smallerProfile", recovery)

    def test_automatic_none_rechecks_only_approved_accounts_and_explains_exhaustion(self):
        chat = (MAIN / "com/openminis/app/ui/chat/ChatViewModel.kt").read_text()
        select = chat.split("suspend fun nmAutoSelect", 1)[1].split("suspend fun nmAutoActivate", 1)[0]
        for gate in ("it.candidate.automaticApproved", "nmOwnKeyRecipientAllowed(it.instance)",
                     "nmAutoRecheckedAccounts.add(it.instance.id)",
                     "tamaEnsureFreeChatEvidence(", "throw nmAutoNoCandidate(choices, selection)"):
            self.assertIn(gate, select)
        self.assertIn("actual is LocalAutoNoCandidate -> actual.explanation", chat)
        self.assertIn("context.getString(R.string.nm_auto_no_routes)).joinToString", chat)

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
