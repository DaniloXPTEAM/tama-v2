"""Static integration checks: these do not exercise Android preferences or Compose."""
from pathlib import Path
import unittest

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class FreeBulkIntegration(unittest.TestCase):
    def test_documented_offers_can_be_imported_on_the_first_refresh(self):
        repo = (MAIN / "com/openminis/app/data/repository/ProviderRepository.kt").read_text()
        mode = repo.split("private fun nmFreeRefreshMode", 1)[1].split("// nanoMuse:", 1)[0]
        self.assertIn("instance.tamaImportFreeOnly || tamaZeroSpendLimit(instance)", mode)
        refresh = repo.split("private suspend fun nmRefreshFreeModels", 1)[1].split("private fun nmRefreshCanStart", 1)[0]
        for guard in ("ModelsDevApi.bundledModels(tamaModelBase(instance))", "nmWithCurrentFreeRefresh(refresh, keySnapshot)",
                      "models.filter { tamaDocumentedChatAllowed(instance, it.id) }", "FreeModelImport.additions("):
            self.assertIn(guard, refresh)
        self.assertNotIn("replaceEntries(", refresh)

    def test_first_choice_rechecks_account_route_and_live_filter(self):
        slots = (MAIN / "io/github/nanomuse/models/ModelSlots.kt").read_text()
        body = slots.split("suspend fun applyProvider", 1)[1].split("private fun pickEntry", 1)[0]
        for guard in ("tamaFreeChatOnly(inst)", "nmOAuthKeySnapshot(inst.id)", "nmOAuthKeyStillCurrent(ownKey)",
                      "routeFingerprint(live)", "tamaFreeChatOnly(live) == freeOnly",
                      "freeOnly && slot != Slot.CHAT", "!followPick(context, option.entryId)"):
            self.assertIn(guard, body)
        self.assertIn("if (!stillCurrent()) return emptySet()", body.split("repo.refreshModels(inst)", 1)[1])

    def test_revalidated_and_cost_policy_is_not_changed(self):
        repo = (MAIN / "com/openminis/app/data/repository/ProviderRepository.kt").read_text()
        section = repo.split("private fun tamaApproveFreeChatModels", 1)[1].split("/** Fresh exact-model", 1)[0]
        for guard in ("APPLICATION_ID", "synchronized(configLock)", "matchingInstances.singleOrNull()",
                      "ProviderType.openRouter",
                      "ProviderCredential.apiKey", "tamaFreeChatOnly(live)", "credentialTransaction",
                      "evidence.current(", "accountVersion(instanceId)", "FreeModelBulk.select(",
                      "approveFreeChatBatch(context, identities)"):
            self.assertIn(guard, section)
        for excluded in ("setEnabled(", "setAllowUnverifiedAutomaticCost(", "setImageApproved(", "refreshModels("):
            self.assertNotIn(excluded, section)
        store = (MAIN / "ai/tama/router/AutoModelStore.kt").read_text()
        batch = store.split("internal fun approveFreeChatBatch", 1)[1].split("/** Image endpoints", 1)[0]
        self.assertIn('"approved", true', batch)
        self.assertNotIn("image_approved", batch)
        self.assertNotIn("ALLOW_UNVERIFIED_COST", batch)
        ui = (MAIN / "com/openminis/app/ui/settings/ProviderDetailScreen.kt").read_text()
        self.assertIn("nmParallel &&", ui)
        self.assertIn("tamaUseAllFreeChatModels(instanceId, key)", ui)
        bulk = repo.split("internal suspend fun tamaUseAllFreeChatModels", 1)[1].split("private fun tamaApproveFreeChatModels", 1)[0]
        self.assertIn("setTamaImportFreeOnly(instanceId, true, key)", bulk)
        self.assertIn("nmBeginRefresh(instanceId, key)", bulk)
        self.assertNotIn("setAllowUnverifiedAutomaticCost", bulk)
        refresh = repo.split("private fun nmBeginRefresh", 1)[1].split("private fun nmRefreshCurrent", 1)[0]
        self.assertIn("key.version != expected.version", refresh)
        self.assertLess(refresh.index("key.version != expected.version"), refresh.index("nmRefreshGate.begin("))


if __name__ == "__main__":
    unittest.main()
