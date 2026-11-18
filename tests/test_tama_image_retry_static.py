"""Static integration checks, not a Compose, network, or on-device test."""
from pathlib import Path
import unittest

VM = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"


class ImageRetryIntegration(unittest.TestCase):
    def test_only_local_failed_image_offers_retry_and_dispatches_normally(self):
        text = VM.read_text()
        send = text.split("private fun nmSendMessage(text:", 1)[1].split("private suspend fun nmRunImageShortcut", 1)[0]
        for guard in ('APPLICATION_ID == "ai.tama.assistant.tamav2test"', 'petGeneration != null',
                      '_attachments.value.isEmpty()', '_pastedTexts.value.isEmpty()',
                      '_editingMessageId.value == null', '!_hasInjectedShareContent.value',
                      'nmImageRetryOffer.consume(', 'nmStartImageShortcut(text, petGeneration, retry)'):
            self.assertIn(guard, send)
        execute = text.split("private fun nmStartImageShortcut", 1)[1].split("private fun nmContinueShortcutQueue", 1)[0]
        for guard in ('!reply.success', '_promptQueue.value.isEmpty()', 'activeSessionId == sid',
                      'nm_image_retry_question', 'nmImageRetryOffer.offer(', 'nmRunImageShortcut(sid, image)'):
            self.assertIn(guard, execute)
        self.assertNotIn('setAllowUnverifiedAutomaticCost', execute)


if __name__ == "__main__":
    unittest.main()
