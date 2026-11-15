"""The filtered Android CI command must include the paid-image and permission tests."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "android/src/android/app/src/test/java"


def test_android_unit_job_includes_image_and_permission_tests() -> None:
    workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    unit_step = workflow.split("      - name: Unit tests\n", 1)[1].split("\n      - name:", 1)[0]
    for test_class in (
        "com.openminis.app.offload.OffloadPermissionQueueTest",
        "com.openminis.app.tools.ImageGenerationToolTest",
    ):
        source = TESTS / (test_class.replace(".", "/") + ".kt")
        assert source.is_file()
        assert f"--tests '{test_class}'" in unit_step
    assert "--tests 'io.github.nanomuse.*'" in unit_step
    assert (TESTS / "io/github/nanomuse/media/MediaImageGateTest.kt").is_file()
