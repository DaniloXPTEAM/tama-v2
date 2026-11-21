from pathlib import Path
import unittest


MAIN = Path(__file__).resolve().parents[1] / 'android/src/android/app/src/main/java'


class Round4WiringTest(unittest.TestCase):
    def test_onboarding_opens_ready_provider_list_not_custom_form(self):
        home = (MAIN / 'io/github/nanomuse/ui/home/NanoMuseHome.kt').read_text()
        setup = home.split('HomePhase.SETUP -> FirstRunSetupScreen(', 1)[1]
        self.assertIn('onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) }', setup)
        form = (MAIN / 'com/openminis/app/ui/settings/AddProviderScreen.kt').read_text()
        self.assertIn('AddProviderStep.CONFIGURE else AddProviderStep.CHOOSE_TYPE', form)
        self.assertIn('KnownProviderSection(onSelect = onSelectVoiceTemplate)', form)

    def test_agnes_goes_through_existing_attachment_and_avatar_paths(self):
        image_gen = (MAIN / 'io/github/nanomuse/avatar/ImageGen.kt').read_text()
        self.assertIn('AgnesImages.account(ep.instance)) return@withContext agnes(', image_gen)
        self.assertIn('repo.tamaRecordPaymentRefusal(ep.instanceId, ep.model, snapshot.version)', image_gen)
        self.assertIn('return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)', image_gen)
        handler = (MAIN / 'io/github/nanomuse/media/MediaOffloadHandler.kt').read_text()
        self.assertIn('save(sessionId, name) { out -> check(picture.writeTo(out)) }', handler)
        self.assertIn('picture.aspectRatio?.let { ratio -> result.put("aspect_ratio", ratio) }', handler)
        studio = (MAIN / 'io/github/nanomuse/avatar/AvatarStudio.kt').read_text()
        self.assertIn('ImageGen.generate(context, ep, prompt, allowFormatRetry', studio)
        screen = (MAIN / 'io/github/nanomuse/ui/avatar/AvatarStudioScreen.kt').read_text()
        self.assertIn('bitmap = slot.bitmap.asImageBitmap()', screen)
        self.assertIn('contentScale = ContentScale.Crop', screen)

    def test_image_picker_reviews_existing_agnes_entry_without_granting_approval(self):
        picker = (MAIN / 'io/github/nanomuse/ui/models/SlotPickerScreen.kt').read_text()
        review = picker.split('if (slot == Slot.IMAGE && parallelTest)', 1)[1].split('// nanoMuse: distinguish', 1)[0]
        self.assertIn('AgnesImageReviewSection(', review)
        ui = (MAIN / 'ai/tama/media/AgnesImageReviewSection.kt').read_text()
        self.assertIn('AutoImageSelection.prepareAgnesReview(context, setup)', ui)
        self.assertIn('onReview(setup.providerId, entryId)', ui)
        for mutator in ('setImageApproved', 'nmSaveAutoReview', 'ImageGen.save', 'addEntry'):
            self.assertNotIn(mutator, ui)
        media = (MAIN / 'io/github/nanomuse/ui/media/MediaModelsScreen.kt').read_text()
        self.assertIn('AgnesImageReviewSection(choiceRevision, onReviewImageModel)', media)
        navigation = (MAIN / 'com/openminis/app/ui/navigation/AppNavigation.kt').read_text()
        self.assertIn('onReviewImageModel = { instanceId, entryId ->\n'
                      '                    navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))', navigation)

    def test_configured_image_is_reported_to_the_model(self):
        media = (MAIN / 'io/github/nanomuse/media/MediaModels.kt').read_text()
        prompt = media.split('fun promptParagraph(context: Context)', 1)[1]
        self.assertIn('val image = agentImageEndpoint(context)', prompt)
        self.assertIn('MediaPrompt.build(imageStatus,', prompt)
        self.assertIn('reportImageAspect = image?.let { ai.tama.media.AgnesImages.route(it.instance) } == true', prompt)


if __name__ == '__main__':
    unittest.main()
