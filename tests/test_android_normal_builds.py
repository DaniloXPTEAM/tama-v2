"""Normal APK identity verification rejects altered and incomplete badging."""

import importlib.util
import sys
from dataclasses import replace
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "tama_normal_builds", ROOT / "scripts/android/verify-normal-builds.py"
)
assert SPEC and SPEC.loader
verify = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verify
SPEC.loader.exec_module(verify)

BADGING = """\
package: name='ai.tama.assistant' versionCode='530' versionName='1.0.9-tama.1' platformBuildVersionName='16'
sdkVersion:'26'
targetSdkVersion:'35'
application-label:'TAMA AI'
application: label='TAMA AI' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
native-code: 'arm64-v8a'
"""


def test_matching_debug_and_release_are_verified_separately() -> None:
    debug = verify.parse_badging(BADGING + "application-debuggable\n")
    release = verify.parse_badging(BADGING)
    verify.validate(debug, release)
    assert debug.debuggable and not release.debuggable
    assert debug.package == release.package == "ai.tama.assistant"
    assert debug.label == release.label == "TAMA AI"


@pytest.mark.parametrize(
    "change",
    [
        {"package": "ai.tama.assistant.tamav2test"},
        {"label": "Tama V2 Test"},
        {"icon": "res/mipmap-anydpi-v26/other.xml"},
        {"version_code": "531"},
        {"version_name": "other"},
        {"min_sdk": "27"},
        {"target_sdk": "36"},
        {"debuggable": True},
        {"abis": ("x86_64",)},
    ],
)
def test_unexpected_release_identity_is_rejected(change: dict) -> None:
    debug = verify.parse_badging(BADGING + "application-debuggable\n")
    release = verify.parse_badging(BADGING)
    with pytest.raises(ValueError):
        verify.validate(debug, replace(release, **change))


def test_missing_badging_never_passes() -> None:
    with pytest.raises(ValueError):
        verify.parse_badging("package: name='ai.tama.assistant'")
