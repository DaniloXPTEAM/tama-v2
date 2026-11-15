"""The phone-test variant must not take the installed app's identity or authorities."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/android/verify-parallel-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_parallel_apk", SCRIPT)
assert SPEC and SPEC.loader
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


def fixture() -> tuple[str, str, str, str]:
    badging = "\n".join(
        [
            "package: name='ai.tama.assistant.tamav2test' versionCode='530'",
            "application-label:'Tama V2 Test'",
            "application-debuggable",
            "launchable-activity: name='com.openminis.app.MainActivityIconAuto' label='Tama V2 Test'",
            "native-code: 'arm64-v8a'",
        ]
    )
    normal = "package: name='ai.tama.assistant' versionCode='530'"
    aliases = "\n".join(
        f'    E: activity-alias (line=1)\n      A: android:name(0x01)="com.openminis.app.{name}"\n'
        '      A: android:label(0x02)="Tama V2 Test"\n      E: intent-filter (line=2)'
        for name in sorted(verify.ALIASES)
    )
    test_tree = (
        'A: android:authorities(0x01)="ai.tama.assistant.tamav2test.fileprovider"\n'
        'A: android:authorities(0x01)="ai.tama.assistant.tamav2test.shizuku"\n'
        'A: android:name(0x01)="ai.tama.assistant.tamav2test.permission.ALARM"\n' + aliases
    )
    normal_tree = 'A: android:authorities(0x01)="ai.tama.assistant.fileprovider"'
    return badging, normal, test_tree, normal_tree


def test_accepts_only_disjoint_parallel_identity() -> None:
    verify.validate(*fixture())


@pytest.mark.parametrize(
    "change", ["package", "label", "abi", "authority", "alias", "alarm", "debuggable"]
)
def test_rejects_install_conflicts_or_wrong_variant(change: str) -> None:
    badging, normal, tree, normal_tree = fixture()
    if change == "package":
        badging = badging.replace("ai.tama.assistant.tamav2test", "ai.tama.assistant")
    elif change == "label":
        badging = badging.replace("application-label:'Tama V2 Test'", "application-label:'TAMA AI'")
    elif change == "abi":
        badging = badging.replace("arm64-v8a", "x86_64")
    elif change == "authority":
        normal_tree += '\nA: android:authorities(0x01)="ai.tama.assistant.tamav2test.fileprovider"'
    elif change == "alias":
        tree = tree.replace(
            'android:label(0x02)="Tama V2 Test"', 'android:label(0x02)="TAMA AI"', 1
        )
    elif change == "alarm":
        tree = tree.replace(
            "ai.tama.assistant.tamav2test.permission.ALARM", "wrong.permission.ALARM"
        )
    else:
        badging = badging.replace("application-debuggable", "")
    with pytest.raises(ValueError):
        verify.validate(badging, normal, tree, normal_tree)
