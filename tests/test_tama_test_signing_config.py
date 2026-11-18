"""Static checks only: no Gradle, Android SDK, real secrets or APKs are used."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PrivateTestSigningConfig(unittest.TestCase):
    def test_signer_is_optional_and_only_assigned_to_parallel_build(self):
        build = (ROOT / "android/src/android/app/build.gradle.kts").read_text()
        start = build.index('create("tamaTest")')
        end = build.index('create("release")', start) if 'create("release")' in build[start:] else len(build)
        self.assertIn('signingConfigs.findByName("tamaPrivateTest")', build[start:end])
        self.assertIn('System.getenv("TAMA_TEST_KEYSTORE_FILE")', build)
        self.assertIn('required("TAMA_TEST_STORE_PASSWORD")', build)
        self.assertIn('required("TAMA_TEST_KEY_ALIAS")', build)
        self.assertIn('required("TAMA_TEST_KEY_PASSWORD")', build)

    def test_workflow_has_private_input_no_upload_and_cleanup(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn('secrets.TAMA_TEST_KEYSTORE_BASE64', workflow)
        self.assertIn('umask 077', workflow)
        self.assertIn('if: ${{ always() }}', workflow)
        self.assertIn('rm -f "$RUNNER_TEMP/tama-test-signing.jks"', workflow)
        self.assertNotIn('actions/upload-artifact', workflow)
        self.assertNotIn('gh release upload', workflow)
        self.assertIn('No stable test signer configured', workflow)


if __name__ == "__main__":
    unittest.main()
