"""Static wiring only; no Compose, preference, or live-provider execution."""
from pathlib import Path
import unittest

MAIN = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'

class ReviewCapabilities(unittest.TestCase):
    def test_review_uses_same_scoped_evidence_as_auto_selection(self):
        repo = (MAIN / 'com/openminis/app/data/repository/ProviderRepository.kt').read_text()
        review = repo.split('internal fun nmAutoReviewCapabilities', 1)[1].split('internal fun nmSaveAutoReview', 1)[0]
        for part in ('nmIdentityIsCurrent(identity)', 'BuildConfig.APPLICATION_ID',
                     'NanoMuseCloud.instance(context)?.id != identity.providerId',
                     'tamaFreeModelCapabilities(identity.providerId, identity.modelId)',
                     'AutoModelStore.capabilities(context, identity, catalogue + discovered)'):
            self.assertIn(part, review)
        self.assertNotIn('saveReview(', review)
        self.assertNotIn('setAllowedForCurrentProcess', review)
        ui = (MAIN / 'com/openminis/app/ui/settings/ModelEntryDetailScreen.kt').read_text()
        self.assertIn('nmFreeImportStatus.collectAsState()', ui)
        self.assertIn('remember(autoIdentity, catalogueCapabilities, nmImportStatuses[instanceId])', ui)

if __name__ == '__main__':
    unittest.main()
