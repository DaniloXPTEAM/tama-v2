"""The filtered Android CI command must include the paid-image and permission tests."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "android/src/android/app/src/test/java"


def test_android_unit_job_includes_image_and_permission_tests() -> None:
    workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    unit_step = workflow.split("      - name: Unit tests\n", 1)[1].split("\n      - name:", 1)[0]
    legacy_test = "com.openminis.app.offload.OffloadPermissionQueueTest"
    assert (TESTS / (legacy_test.replace(".", "/") + ".kt")).is_file()
    assert f"--tests '{legacy_test}'" in unit_step
    assert "--tests 'io.github.nanomuse.*'" in unit_step
    assert "--tests 'ai.tama.*'" in unit_step
    assert (TESTS / "ai/tama/tools/ImageGenerationToolTest.kt").is_file()
    assert (TESTS / "ai/tama/media/MediaImageGateTest.kt").is_file()
    assert (TESTS / "ai/tama/chat/PromptProfileMeasurementTest.kt").is_file()
