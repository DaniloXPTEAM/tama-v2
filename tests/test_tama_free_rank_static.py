"""Static wiring checks; no Android, real account, billing or routing UI."""
from pathlib import Path
import unittest

VM = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt'

class FreeRankWiring(unittest.TestCase):
    def test_fresh_rank_is_parallel_own_key_and_same_instance_only(self):
        source = VM.read_text().split('fun nmAutoCandidates():', 1)[1].split('fun nmAutoChoiceStillEligible', 1)[0]
        for item in ('tamaV2Test &&', 'ProviderType.openRouter', 'ProviderCredential.apiKey',
                     'NanoMuseCloud.instance(context)?.id', 'tamaEligibleFreeModelIds(it.id)',
                     'instance.id == freeRankInstance?.id && entry.model.id in freeRankIds',
                     'nmAutoChatCostAllowed(entry, instance)', 'preference = preference'):
            self.assertIn(item, source)

if __name__ == '__main__':
    unittest.main()
