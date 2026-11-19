"""The CI summary must print only closed, locally authored categories."""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/android/summarize-build-failure.py"
spec = importlib.util.spec_from_file_location("summary", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FailureSummary(unittest.TestCase):
    def test_categories_are_fixed_and_unknown_stays_unknown(self):
        self.assertEqual(["Compiler reported an error"], module.categories("e: file:/fixture.kt: failure", 1))
        self.assertEqual(["Resource pressure or terminated build process may be involved"], module.categories("", 137))
        self.assertEqual(["No known failure category was found in the captured build output"], module.categories("unrecognized", 1))

    def test_unit_test_diagnostics_distinguish_assertions_from_missing_filters(self):
        for message, category in (
            ("There were failing tests. See the report at: file:///fixture", "Gradle reported failing unit tests"),
            ("No tests found for given includes: [fixture]", "Gradle found no tests for the selected filters"),
        ):
            self.assertEqual([category], module.categories(message, 1))

    def test_workflow_preserves_failure_and_cleans_log(self):
        workflow = (SCRIPT.parents[2] / '.github/workflows/android.yml').read_text()
        for step, log in (
            ('Assemble parallel test locally', 'tama-assemble.log'),
            ('Run parallel routing, key probe and history tests', 'tama-unit-tests.log'),
            ('Compile parallel Compose instrumentation locally', 'tama-compose-compile.log'),
        ):
            body = workflow.split(f'- name: {step}', 1)[1].split('- name:', 1)[0]
            for part in ('umask 077', 'status=$?', 'exit "$status"', 'summarize-build-failure.py',
                         f'> "$RUNNER_TEMP/{log}" 2>&1',
                         f'rm -f "$RUNNER_TEMP/{log}"'):
                self.assertIn(part, body)
            self.assertIn(f'"$RUNNER_TEMP/{log}"', workflow.split('- name: Remove private test signer', 1)[1])
            self.assertNotIn(f'cat "$RUNNER_TEMP/{log}"', workflow)
            self.assertNotIn(f'tee "$RUNNER_TEMP/{log}"', workflow)
        self.assertLess(workflow.index('- name: Verify separate APK identities locally'),
                        workflow.index('- name: Run parallel routing, key probe and history tests'))
        self.assertLess(workflow.index('- name: Run normal debug isolation tests'),
                        workflow.index('- name: Deliver only to a new restricted Drive folder'))

    def test_compiler_locations_include_only_checkout_kotlin_file_and_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            relative = 'android/src/android/app/src/main/java/Fixture.kt'
            file = root / relative
            file.parent.mkdir(parents=True)
            file.write_text('package fixture')
            text = (f'e: file://{file}:18:7 fictional-sensitive-detail\n'
                    'e: file:///tmp/private/Hidden.kt:18:9 denied\n'
                    f'e: file://{file}:18:9 duplicate\n')
            self.assertEqual([(relative, 18)], module.compiler_locations(text, root))

    def test_log_data_is_never_echoed(self):
        fixture = "fixture-private-token-XYZ123"
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "output"
            file.write_text(f"could not resolve {fixture}\n{fixture}\n")
            result = subprocess.run([sys.executable, str(SCRIPT), str(file), "1"], check=True, capture_output=True, text=True)
        self.assertNotIn(fixture, result.stdout + result.stderr)
        self.assertIn("Dependency resolution or network failure may be involved", result.stdout)


if __name__ == "__main__":
    unittest.main()
