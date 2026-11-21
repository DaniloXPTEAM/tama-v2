"""Static wiring checks; does not exercise Compose or a real model."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'

class ImageRawWiring(unittest.TestCase):
    def test_parallel_filter_covers_followups_and_preserves_native_events(self):
        vm = (ROOT / 'com/openminis/app/ui/chat/ChatViewModel.kt').read_text()
        dispatch = vm.split('val sourceStream =', 1)[1].split('visibleStream.collect', 1)[0]
        self.assertIn('val visibleStream = if (tamaV2Test)', dispatch)
        self.assertIn('ImageToolStream.filter(sourceStream', dispatch)
        self.assertIn('else sourceStream', dispatch)
        self.assertIn('nmImageGuard.newTurn(handled.explicitImageRequest)', vm)
        stream = (ROOT / 'ai/tama/media/ImageToolStream.kt').read_text()
        self.assertIn('if (chunk is LLMStreamChunk.Text)', stream)
        self.assertIn('emit(chunk)', stream)
        self.assertNotIn('execute(', stream)

    def test_missing_local_image_file_does_not_build_a_preview(self):
        renderer = (ROOT / 'com/openminis/app/ui/chat/StreamingMarkdownText.kt').read_text()
        image = renderer.split('is MdBlock.Image -> {', 1)[1].split('is MdBlock.Video', 1)[0]
        self.assertIn('block.url.startsWith("minis://attachments/") && file == null', image)
        guard = image.split('file == null) {', 1)[1].split('SubcomposeAsyncImage(', 1)[0]
        self.assertIn('R.string.filepreview_image_load_error', guard)
        self.assertIn('return', guard)

if __name__ == '__main__':
    unittest.main()
