"""Static integration checks only; no HTTP or Compose runtime."""
from pathlib import Path
import unittest

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class KeyProbeIntegration(unittest.TestCase):
    def test_probe_is_opt_in_and_destination_review_precedes_call(self):
        ui = (MAIN / "com/openminis/app/ui/settings/AddProviderScreen.kt").read_text()
        start = ui.index("// nanoMuse: this opt-in probe")
        stop = ui.index("// ── Save button", start)
        code = ui[start:stop]
        for text in ("if (nmParallel)", "!autoSuggested", "nmDestinationAllowed",
                     "KeyDestinationGuard.permits(probeKey, nmDestination)",
                     "KeyProbePlan.validKey(probeKey)",
                     "remember(providerType, apiKey, nmDestination, autoSuggested)"):
            self.assertIn(text, code)
        self.assertLess(code.index("KeyDestinationGuard.permits"), code.index("KeyProbe.check("))
        probe = (MAIN / "ai/tama/providers/KeyProbe.kt").read_text()
        for text in (".get()", ".followRedirects(false)", ".followSslRedirects(false)",
                     ".retryOnConnectionFailure(false)", ".callTimeout(15, TimeUnit.SECONDS)",
                     "suspendCancellableCoroutine", "invokeOnCancellation { call.cancel() }", "response.use {"):
            self.assertIn(text, probe)
        self.assertNotIn("Log.", probe)
        self.assertNotIn(".post(", probe)


if __name__ == "__main__":
    unittest.main()
