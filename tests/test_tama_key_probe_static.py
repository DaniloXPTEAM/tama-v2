"""Static integration checks only; no HTTP or Compose runtime."""
from pathlib import Path
import unittest
from xml.etree import ElementTree

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class KeyProbeIntegration(unittest.TestCase):
    def test_probe_is_opt_in_and_destination_review_precedes_call(self):
        ui = (MAIN / "com/openminis/app/ui/settings/AddProviderScreen.kt").read_text()
        self.assertIn("KeyProbeSection(providerType, apiKey, nmDestination, nmDestinationAllowed)", ui)
        self.assertIn("onApiKeyChange = onApiKeyChange", ui)
        detail = (MAIN / "com/openminis/app/ui/settings/ProviderDetailScreen.kt").read_text()
        self.assertIn("KeyProbeSection(instance.providerType, storedKey, nmLiveDestination, nmDestinationAllowed, version)", detail)
        code = (MAIN / "ai/tama/providers/KeyProbeSection.kt").read_text()
        for text in ("key(type, apiKey, destination, allowed, generation)", "rememberCoroutineScope()",
                     "plan != null && allowed && KeyProbePlan.validKey(probeKey)", "KeyProbe::check"):
            self.assertIn(text, code)
        self.assertLess(code.index("KeyDestinationGuard.permits"), code.index("check(plan, probeKey)"))
        probe = (MAIN / "ai/tama/providers/KeyProbe.kt").read_text()
        for text in (".get()", ".followRedirects(false)", ".followSslRedirects(false)",
                     ".retryOnConnectionFailure(false)", ".callTimeout(15, TimeUnit.SECONDS)",
                     "suspendCancellableCoroutine", "invokeOnCancellation { call.cancel() }", "response.use {"):
            self.assertIn(text, probe)
        self.assertNotIn("Log.", probe)
        self.assertNotIn(".post(", probe)

    def test_portuguese_success_does_not_claim_the_key_is_valid(self):
        resources = MAIN.parent / "res/values-pt-rBR/nm_strings.xml"
        root = ElementTree.parse(resources)
        text = root.find("string[@name='nm_key_probe_accepted']").text
        self.assertIn("não garante", text)
        self.assertIn("chave", text)


if __name__ == "__main__":
    unittest.main()
