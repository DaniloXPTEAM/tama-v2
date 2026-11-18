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

    def test_workflow_preserves_failure_and_cleans_log(self):
        workflow = (SCRIPT.parents[2] / '.github/workflows/android.yml').read_text()
        assemble = workflow.split('- name: Assemble parallel test locally', 1)[1].split('- name: Assemble normal debug locally', 1)[0]
        for part in ('set -o pipefail', 'status=$?', 'exit "$status"', 'summarize-build-failure.py', 'rm -f "$RUNNER_TEMP/tama-assemble.log"'):
            self.assertIn(part, assemble)
        self.assertIn('"$RUNNER_TEMP/tama-test-signing.jks" "$RUNNER_TEMP/tama-assemble.log"', workflow)
        self.assertNotIn('cat "$RUNNER_TEMP/tama-assemble.log"', workflow)

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
