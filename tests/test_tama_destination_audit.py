"""Evidence anchors for a manual destination audit, not a transport safety test."""
from pathlib import Path
import unittest

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


class DestinationAuditAnchors(unittest.TestCase):
    def test_known_entry_points_are_present_for_review(self):
        expected = {
            "com/openminis/app/provider/openai/OpenAIProvider.kt": (
                "private fun rawStreamMessage(", "suspend fun rawPassthroughRequest(",
                "suspend fun generateImage(", "suspend fun editImage(",
                "private suspend fun buildRequest(", "chatExtraHeaders", "absoluteEndpointOverride",
            ),
            "com/openminis/app/provider/voice/VoiceProvider.kt": (
                "fun buildVoiceInputRequest(", "fun buildVoiceOutputRequest(", "fun applyVoiceAuth(",
            ),
            "com/openminis/app/provider/voice/VoiceProviderVendors.kt": ("class GroqVoiceProvider", "class XAIVoiceProvider"),
            "io/github/nanomuse/speech/DashScopeVoiceProvider.kt": ("class DashScopeVoiceProvider",),
            "com/openminis/app/data/repository/ProviderRepository.kt": ("private suspend fun nmRunRefresh(",),
        }
        for name, anchors in expected.items():
            source = (MAIN / name).read_text()
            for anchor in anchors:
                with self.subTest(file=name, anchor=anchor):
                    self.assertIn(anchor, source)


if __name__ == "__main__":
    unittest.main()
