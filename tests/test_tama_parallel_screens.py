"""Source checks only. Bounds, taps and accessibility still require Android."""
from pathlib import Path
from xml.etree import ElementTree
import unittest

APP = Path(__file__).resolve().parents[1] / 'android/src/android/app/src'
JAVA = APP / 'main/java'


class ParallelScreens(unittest.TestCase):
    def test_insets_and_cancel_are_local_to_parallel_app(self):
        avatar = (JAVA / 'io/github/nanomuse/ui/avatar/AvatarStudioScreen.kt').read_text()
        self.assertIn('if (parallel) Modifier.navigationBarsPadding() else Modifier', avatar)
        header = (JAVA / 'com/openminis/app/ui/chat/ChatScreen.kt').read_text()
        self.assertIn('if (nmReserveHeader) padding.calculateTopPadding() else 0.dp', header)
        self.assertIn('if (nmReserveHeader) 0.dp else padding.calculateTopPadding()', header)
        models = (JAVA / 'com/openminis/app/ui/settings/AddModelsToGroupScreen.kt').read_text()
        self.assertIn('if (parallel) R.string.common_cancel else R.string.model_group_detail_back', models)
        self.assertIn('if (!parallel) MinisTextButton(onClick = onBack)', models)

    def test_hands_setup_and_default_model_do_not_grant_permissions(self):
        hands = (JAVA / 'io/github/nanomuse/ui/hands/HandsScreen.kt').read_text()
        self.assertIn('if (parallel && visionEntries.isNotEmpty()) onOpenHandsModel else onOpenProviders', hands)
        self.assertIn('remember(tick, config, modelChange) { Hands.readiness(context) }', hands)
        slots = (JAVA / 'io/github/nanomuse/models/ModelSlots.kt').read_text()
        self.assertIn('if (!isCloud(context, inst)) return if (providers.tamaFreeChatOnly(inst))', slots)
        self.assertIn('if (!repo.tamaAllowsChatModel(inst, entry.model.id)) return false', slots)
        self.assertNotIn('setAllowedForCurrentProcess', slots)

    def test_parallel_only_copy(self):
        xml = APP / 'tamaTest/res/values-pt-rBR/strings.xml'
        root = ElementTree.parse(xml)
        self.assertEqual('Habilidades do TAMA AI', root.find("string[@name='skills_browser_title']").text)
        self.assertIn('Identificador', root.find("string[@name='nm_connectors_client_id']").text)
