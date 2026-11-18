"""Static wiring checks; does not exercise Compose or a real model."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'

class ImageRawWiring(unittest.TestCase):
    def test_parallel_image_turn_only_and_native_tool_events_untouched(self):
        vm = (ROOT / 'com/openminis/app/ui/chat/ChatViewModel.kt').read_text()
        self.assertIn('if (tamaV2Test && nmIsImagePersonTurn)', vm)
        self.assertIn('ImageToolStream.filter(sourceStream', vm)
        self.assertIn('nmIsImagePersonTurn = handled.explicitImageRequest', vm)
        stream = (ROOT / 'ai/tama/media/ImageToolStream.kt').read_text()
        self.assertIn('if (chunk is LLMStreamChunk.Text)', stream)
        self.assertIn('else emit(chunk)', stream)
        self.assertNotIn('execute(', stream)

if __name__ == '__main__':
    unittest.main()
