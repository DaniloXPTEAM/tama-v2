"""Static notice checks; not a Compose layout test."""
from pathlib import Path
import unittest
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main'

class ContextNotice(unittest.TestCase):
    def test_notice_is_parallel_only_and_not_ellipsized(self):
        vm = (ROOT/'java/com/openminis/app/ui/chat/ChatViewModel.kt').read_text()
        ui = (ROOT/'java/com/openminis/app/ui/chat/ChatMiscViews.kt').read_text()
        self.assertIn('if (tamaV2Test) context.getString(R.string.nm_context_near_limit, tokens, window)', vm)
        self.assertIn('if (tamaV2Test) "tama_context_notice" else "compact"', vm)
        self.assertIn('maxLines = if (block.toolName == "tama_context_notice") Int.MAX_VALUE else 1', ui)
        for locale in ('values', 'values-pt-rBR'):
            root = ElementTree.parse(ROOT/'res'/locale/'nm_strings.xml')
            text = root.find("string[@name='nm_context_near_limit']").text
            self.assertIn('%1$d', text)
            self.assertIn('%2$d', text)

if __name__ == '__main__':
    unittest.main()
