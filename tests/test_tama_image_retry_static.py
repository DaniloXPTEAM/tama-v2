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


    def test_explicit_image_command_does_not_entangle_chat_or_queue(self):
        text = VM.read_text()
        entry = text.split("private fun nmSendMessage(text:", 1)[1].split("private suspend fun nmRunImageShortcut", 1)[0]
        self.assertIn('image = { image -> nmStartImageShortcut', entry)
        self.assertIn('if (imageCommand) nmShowImageCommandHint(text)', entry)
        self.assertNotIn('AutoModelStore.enabled(', entry)
        self.assertNotIn('_nmProfileOverride', entry)
        queue = text.split('private fun nmEnqueuePrompt(', 1)[1].split('val prompt = QueuedPrompt', 1)[0]
        self.assertIn('if (nmLocalImageCommand(trimmed, petGeneration))', queue)
        self.assertIn('nmShowImageCommandHint(text)', queue)

    def test_legacy_agnes_bindings_are_rejected_before_chat_client_creation(self):
        text = VM.read_text()
        restore = text.split('private fun restoreFromBinding(', 1)[1].split('private fun resolveProviderFromGroup', 1)[0]
        self.assertLess(restore.index('AgnesImages.imageOnlyInTest(entry.model.id)) return false'),
                        restore.index('currentProvider = nmCreateProvider'))
        group = text.split('private fun resolveProviderFromGroup(', 1)[1].split('fun selectGroup', 1)[0]
        self.assertIn('.filterNot { ai.tama.media.AgnesImages.imageOnlyInTest(it.model.id) }', group)
        self.assertIn('!ai.tama.media.AgnesImages.imageOnlyInTest(modelId)', text)


if __name__ == "__main__":
    unittest.main()
