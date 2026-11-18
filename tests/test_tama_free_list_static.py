"""Static check: not a Compose, billing or live catalogue test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'

class FreeListWiring(unittest.TestCase):
    def test_parallel_own_key_list_is_reversible_and_does_not_change_saved_models(self):
        text = (ROOT / 'com/openminis/app/ui/settings/ProviderDetailScreen.kt').read_text()
        view = text.split('// nanoMuse: this is a reversible view filter', 1)[1].split('nmDisplayedEntries.forEachIndexed', 1)[0]
        for guard in ('nmParallel', 'ProviderType.openRouter', 'ProviderCredential.apiKey',
                      'instance.tamaImportFreeOnly', '?.id?.let { it != instanceId } == true',
                      'tamaEligibleFreeModelIds(instanceId)',
                      'FreeModelList.filter(entries, nmShowFreeFilter && nmFilterFree, nmFreeIds)',
                      'onCheckedChange = { nmFilterFree = it }', 'nm_free_list_refresh'):
            self.assertIn(guard, view)
        self.assertNotIn('removeEntry(', view)
        self.assertNotIn('updateEntry(', view)
        self.assertNotIn('setAllowUnverifiedAutomaticCost(', view)

if __name__ == '__main__':
    unittest.main()
