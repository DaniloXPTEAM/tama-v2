"""Static wiring checks; not an Android chat or billing test."""
from pathlib import Path
import unittest

VM = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt'

class LargerContextWiring(unittest.TestCase):
    def test_local_capacity_stop_uses_revalidated_automatic_selector(self):
        text = VM.read_text()
        select = text.split('suspend fun nmAutoSelect(stickyEntryId:', 1)[1].split('suspend fun nmAutoActivate', 1)[0]
        self.assertIn('LargerContext.eligible(largerThanWindow, it.candidate.contextLimit)', select)
        self.assertIn('turnState.pick(', select)
        self.assertIn('nmAutoFit(', select)
        guard = text.split('when (inLoopContextCheck(contextMaintenance, requestBudget,', 1)[1]
        guard = guard.split('// The loop cannot present a modal mid-flight', 1)[0]
        for item in ('tamaV2Test && nmAutoTurn != null', 'largerThanWindow = requestBudget.window',
                     'nmAutoTurn.exclude(prior)', 'nmAutoActivate(larger)', 'continue@agentTurns'):
            self.assertIn(item, guard)
        self.assertNotIn('setAllowUnverifiedAutomaticCost', guard)

if __name__ == '__main__':
    unittest.main()
