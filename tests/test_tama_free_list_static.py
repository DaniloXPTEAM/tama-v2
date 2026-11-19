"""Static check: not a Compose, billing or live catalogue test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'

class FreeListWiring(unittest.TestCase):
    def test_parallel_own_key_list_is_reversible_and_does_not_change_saved_models(self):
        text = (ROOT / 'com/openminis/app/ui/settings/ProviderDetailScreen.kt').read_text()
        view = text.split('// nanoMuse: one per-key choice', 1)[1].split('nmDisplayedEntries.forEachIndexed', 1)[0]
        for guard in ('if (nmHasOwnKey)', 'checked = nmOnlyFree', 'lockedByZeroLimit = nmZeroLimit',
                      'tamaEligibleFreeModelIds(instanceId)', 'FreeModelList.filterForDisplay(entries, nmOnlyFree)', 'hasCatalogue = nmHasFreeCatalogue',
                      'setTamaImportFreeOnly(instanceId, enabled)', 'nm_free_list_refresh'):
            self.assertIn(guard, view)
        self.assertIn('storedKey.isNotBlank() && providerRepository.tamaOwnApiKey(instance) != null', text)
        chat = (ROOT / 'com/openminis/app/ui/chat/ChatViewModel.kt').read_text()
        self.assertIn('if (!nmFreeChatAllows(currentProvider) || (needsFreeChat && ownInstance != null && freeChat == null))', chat)
        self.assertIn('throw ai.tama.pricing.FreeModelList.Blocked(', chat)
        self.assertIn('if (!tamaV2Test || this@ChatViewModel.currentProvider !== client) return true', chat)
        self.assertIn('nmOwnClientCurrent(currentProvider)', chat)
        self.assertIn('own.accountVersion == AutoModelRuntime.accountVersion(own.providerId)', chat)
        self.assertIn('nmForceFreeRemainder = true', chat)
        paid_check = chat.split('if (!nmFreeChatAllows(currentProvider)', 1)[1].split('val providerStream =', 1)[0]
        self.assertIn('tamaFreeChatStatus(it, currentProvider.model.id)', paid_check)
        self.assertIn('actual is ai.tama.pricing.FreeModelList.Blocked -> context.getString(ai.tama.pricing.freeStatusAction(actual.status))', chat)
        self.assertNotIn('removeEntry(', view)
        self.assertNotIn('setAllowUnverifiedAutomaticCost(', view)

if __name__ == '__main__':
    unittest.main()
