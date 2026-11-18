"""Static integration checks: these do not exercise Android preferences or Compose."""
from pathlib import Path
import unittest

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class FreeBulkIntegration(unittest.TestCase):
    def test_revalidated_and_cost_policy_is_not_changed(self):
        repo = (MAIN / "com/openminis/app/data/repository/ProviderRepository.kt").read_text()
        section = repo.split("internal fun tamaApproveFreeChatModels", 1)[1].split("/** Fresh exact-model", 1)[0]
        for guard in ("APPLICATION_ID", "synchronized(configLock)", "matchingInstances.singleOrNull()", "ProviderType.openRouter",
                      "ProviderCredential.apiKey", "live.tamaImportFreeOnly", "credentialTransaction",
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
        self.assertIn("tamaApproveFreeChatModels(instanceId)", ui)


if __name__ == "__main__":
    unittest.main()
