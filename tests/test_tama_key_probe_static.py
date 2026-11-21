"""Static integration checks only; no HTTP or Compose runtime."""
from pathlib import Path
import unittest
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/src/android/app"
MAIN = APP / "src/main/java"


class KeyProbeIntegration(unittest.TestCase):
    def test_probe_is_opt_in_and_destination_review_precedes_call(self):
        ui = (MAIN / "com/openminis/app/ui/settings/AddProviderScreen.kt").read_text()
        self.assertIn("KeyProbeSection(providerType, apiKey, nmDestination, nmDestinationAllowed)", ui)
        self.assertIn("onApiKeyChange = onApiKeyChange", ui)
        detail = (MAIN / "com/openminis/app/ui/settings/ProviderDetailScreen.kt").read_text()
        self.assertIn("instance.providerType, storedKey, nmLiveDestination,", detail)
        self.assertIn("nmDestinationAllowed && snapshot.key == storedKey, version", detail)
        self.assertIn("providerRepository.nmOAuthKeyStillCurrent(snapshot)", detail)
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

    def test_ci_compiles_parallel_instrumentation_only_on_no_delivery_runs(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        step = workflow.split("- name: Compile parallel Compose instrumentation locally", 1)[1].split("- name:", 1)[0]
        self.assertIn("if: ${{ !inputs.deliver_to_drive }}", step)
        self.assertIn("-PtamaTestInstrumentation=true :app:compileTamaTestAndroidTestKotlin", step)
        self.assertNotIn("secrets.", step)
        for forbidden in ("connectedTamaTestAndroidTest", "adb ", "assemble", "upload-artifact"):
            self.assertNotIn(forbidden, step)
        self.assertIn(
            'if (providers.gradleProperty("tamaTestInstrumentation").orNull == "true") testBuildType = "tamaTest"',
            (APP / "build.gradle.kts").read_text(),
        )

    def test_portuguese_success_does_not_claim_the_key_is_valid(self):
        resources = MAIN.parent / "res/values-pt-rBR/nm_strings.xml"
        root = ElementTree.parse(resources)
        text = root.find("string[@name='nm_key_probe_accepted']").text
        self.assertIn("não garante", text)
        self.assertIn("chave", text)


if __name__ == "__main__":
    unittest.main()
