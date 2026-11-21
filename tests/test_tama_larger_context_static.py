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
                     'nmAutoTurn.exclude(prior)', 'nmAutoActivate(larger, largerWindow = true)', 'continue@agentTurns'):
            self.assertIn(item, guard)
        self.assertNotIn('setAllowUnverifiedAutomaticCost', guard)

    def test_fallback_rebuild_guard_also_checks_a_larger_candidate(self):
        text = VM.read_text()
        rebuild = text.split('// nanoMuse: fallback may change provider, model vision and OAuth prefix.', 1)[1]
        rebuild = rebuild.split('val requestMessages = checkNotNull(preparedMessages)', 1)[0]
        for item in ('ContextMaintenance.Action.STOP', 'tamaV2Test && nmAutoTurn != null',
                     'largerThanWindow = requestBudget.window', 'nmAutoTurn.exclude(prior)',
                     'nmAutoActivate(larger, largerWindow = true)', 'return@streamBlock true', 'nm_context_limit_reached'):
            self.assertIn(item, rebuild)
        self.assertNotIn('setAllowUnverifiedAutomaticCost', rebuild)

    def test_own_key_server_overflow_only_uses_revalidated_larger_route(self):
        text = VM.read_text().split('val ownInputOverflow =', 1)[1].split('// nanoMuse: never forward', 1)[0]
        for item in ('nmOwnKeyTurn', 'LargerContext.serverRejected', 'largerThanWindow = requestBudget.window',
                     'nmAutoActivate(larger ?: nmAutoSelect(null), largerWindow = larger != null)',
                     'nm_own_context_limit'):
            self.assertIn(item, text)
        self.assertNotIn('setAllowUnverifiedAutomaticCost', text)

if __name__ == '__main__':
    unittest.main()
