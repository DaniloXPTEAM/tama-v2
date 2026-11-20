package ai.tama.media

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import ai.tama.pricing.ModelCostGuard
import ai.tama.router.AutoModelStore
import ai.tama.router.Need
import ai.tama.tools.ImageGenerationTool
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.MediaOffloadHandler
import io.github.nanomuse.pet.PetPrefsContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Production selection and consent store, with an in-memory repository and preferences. No network. */
class AutoImageSelectionTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(
        val context: Context,
        val identity: AutoModelStore.Identity,
        val repo: ProviderRepository,
        val config: ProviderConfig,
        val reopened: () -> Context,
    )

    private fun fixture(): Fixture {
        val preferences = PetPrefsContext()
        val app = Mockito.mock(MinisApp::class.java)
        val repo = Mockito.mock(ProviderRepository::class.java)
        fun reopen() = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = app
            override fun getFilesDir() = temp.root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getSharedPreferences(name, mode)
        }
        val context = reopen()
        val instance = ProviderInstance(
            UUID.randomUUID().toString(), "Fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://fixture.invalid/v1",
        )
        val model = LLMModel("image-model", "Fixture image", "fixture", outputModalities = listOf("image"))
        val entry = ModelEntry(instance.id, model)
        val config = ProviderConfig(instances = mutableListOf(instance), modelEntries = mutableListOf(entry))
        Mockito.`when`(app.providerRepositoryOrNull).thenReturn(repo)
        Mockito.`when`(repo.config).thenReturn(MutableStateFlow(config))
        Mockito.`when`(repo.usableApiKey(instance)).thenReturn("fixture-key")
        Mockito.`when`(repo.instance(instance.id)).thenReturn(instance)
        val identity = AutoModelStore.identity(entry, instance)
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, true)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        AutoModelStore.setImageApproved(context, identity, true)
        return Fixture(context, identity, repo, config, ::reopen)
    }

    private fun Fixture.addAgnes(): Pair<ProviderInstance, List<ModelEntry>> {
        val instance = ProviderInstance(UUID.randomUUID().toString(), "Agnes", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://apihub.agnes-ai.com/v1")
        val entries = AgnesImages.models.map { ModelEntry(instance.id, it.metadata()) }
        config.instances.add(instance)
        config.modelEntries.addAll(entries)
        Mockito.`when`(repo.instance(instance.id)).thenReturn(instance)
        Mockito.`when`(repo.tamaOwnApiKey(instance)).thenReturn("fixture-agnes-key")
        Mockito.`when`(repo.entriesFor(instance.id)).thenAnswer { config.modelEntries.filter { it.providerInstanceId == instance.id } }
        return instance to entries
    }

    @Test fun `automatic slot stays on Agnes with both cost permissions on and a paid alternate approved`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val paid = requireNotNull(AutoImageSelection.choose(f.context))
        assertEquals(f.identity.modelId, paid.modelId)
        val (agnes, entries) = f.addAgnes()
        ModelCostGuard.setAllowedForCurrentProcess(true)
        try {
            assertEquals(AgnesImages.DEFAULT, ImageGen.endpoint(f.context)?.model)
            assertNull(AutoImageSelection.endpointIfStillAllowed(f.context, paid))
            // The paid alternate is already approved; lack of Agnes consent is not permission to use it.
            assertNull(AutoImageSelection.choose(f.context))
            AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(entries.first(), agnes), true)
            val chosen = requireNotNull(AutoImageSelection.choose(f.context))
            val endpoint = requireNotNull(AutoImageSelection.endpointIfStillAllowed(f.context, chosen))
            assertEquals(agnes.id, endpoint.instanceId)
            assertEquals(AgnesImages.DEFAULT, endpoint.model)
            assertTrue(ImageGen.declaredFree(f.context, endpoint))
            assertEquals(AgnesImages.DEFAULT, AutoImageSelection.choose(f.context, freeOnly = true)?.modelId)

            // A saved intentional choice is not overwritten by installing the provider.
            ImageGen.save(f.context, paid.providerId, paid.modelId)
            assertEquals(paid.providerId, ImageGen.endpoint(f.context)?.instanceId)
            assertEquals(paid.providerId, AutoImageSelection.choose(f.context)?.providerId)
            assertNull(AutoImageSelection.choose(f.context, freeOnly = true))
        } finally { ModelCostGuard.setAllowedForCurrentProcess(false) }
    }

    @Test fun `shortcut native tool and CLI never submit to the approved paid alternate`() = runBlocking {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        val sent = mutableListOf<Pair<String, String>>()
        val permits = mutableListOf<Boolean>()
        var permissionGranted = true
        var freeOnlyForChat = false
        val services = object : MediaImageServices by AndroidMediaImageServices(f.context) {
            override suspend fun observeCliImageScope(sessionId: String?): () -> CliImageScope.State =
                { CliImageScope.State.UNRESTRICTED }
            override suspend fun generate(
                endpoint: ImageGen.Endpoint, prompt: String, size: String, allowFormatRetry: Boolean,
                beforeRequest: (() -> Unit)?,
            ): MediaPicture {
                requireNotNull(beforeRequest).invoke()
                sent.add(endpoint.instanceId to endpoint.model)
                return MediaPicture { output -> output.write(byteArrayOf(1, 2, 3)); true }
            }
        }
        val gate = object : MediaImageGate {
            override suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String,
                sessionId: String?, freshApproval: Boolean): Boolean {
                assertEquals(agnes.id, endpoint.instanceId)
                assertEquals(AgnesImages.DEFAULT, endpoint.model)
                assertEquals("um gato astronauta", prompt)
                permits.add(freshApproval)
                return permissionGranted
            }
            override fun observe(endpoint: ImageGen.Endpoint, sessionId: String?) = object : MediaPermissionObservation {
                override fun isCurrent() = true
                override fun close() = Unit
            }
        }
        val handler = MediaOffloadHandler(f.context, services, gate)
        val prompt = requireNotNull(ai.tama.router.ImageShortcutEligibility.select(
            "crie uma iamgem de um gato astronauta", ai.tama.router.ImageShortcutContext(person = true, parallelTest = true),
        )).prompt
        val sid = UUID.randomUUID().toString()
        val cli = NativeOffloadRequest(pid = 1, argv = listOf("nanomuse-media", "image", "--prompt", prompt),
            env = emptyMap(), cwd = "/", sessionId = sid)
        suspend fun tool(text: String) = ImageGenerationTool.execute(JSONObject().put("prompt", text).toString(), "Image") { p, size ->
            handler.generateImage(p, size, sid, freeOnly = freeOnlyForChat)
        }
        ModelCostGuard.setAllowedForCurrentProcess(true)
        try {
            assertTrue(services.automaticCostAllowed())
            assertTrue(services.modelCostAllowed())
            // An available but unapproved Agnes default cannot fall through to paid.
            assertEquals("agnes_auto_image_needs_approval", JSONObject(handler.handle(cli).output).getString("error"))
            assertEquals("agnes_auto_image_needs_approval", JSONObject(tool(prompt).output).getString("error"))
            assertTrue(sent.isEmpty())
            assertTrue(permits.isEmpty())

            // Set a pin and reopen the preferences without enabling any general cost permission.
            ImageGen.save(f.context, agnes.id, AgnesImages.DEFAULT)
            AutoModelStore.setEnabled(f.reopened(), false)
            AutoModelStore.setAllowUnverifiedAutomaticCost(f.reopened(), false)
            ModelCostGuard.setAllowedForCurrentProcess(false)
            freeOnlyForChat = true
            val manual = tool(prompt)
            assertTrue(manual.success)
            assertEquals(AgnesImages.DEFAULT, JSONObject(manual.output).getString("model"))

            AutoModelStore.setEnabled(f.reopened(), true)
            val review = AutoModelStore.review(f.reopened(), AutoModelStore.identity(entries.first(), agnes))
            assertTrue(AutoModelStore.saveReview(f.reopened(), review, chat = false, image = true, edits = emptyMap()))
            permissionGranted = false
            val denied = ImageShortcutRunner.run(prompt, "could not draw", "Settings",
                safeFailureMessages = mapOf("permission_denied" to "permission not granted"), execute = ::tool)
            assertFalse(denied.success)
            assertTrue(denied.text.contains("[Settings](minis://settings/permissions)"))
            assertEquals(listOf(agnes.id to AgnesImages.DEFAULT), sent)

            permissionGranted = true
            val shortcut = ImageShortcutRunner.run(prompt, "could not draw", "Settings", execute = ::tool)
            assertTrue(shortcut.success)
            assertTrue(shortcut.text.contains("](minis://attachments/"))
            val name = shortcut.text.substringAfter("minis://attachments/").substringBefore(')')
            assertArrayEquals(byteArrayOf(1, 2, 3), f.context.filesDir.resolve("minis-sessions/$sid/attachments/$name").readBytes())
            val fromCli = handler.handle(cli)
            assertEquals(0, fromCli.exitCode)
            assertEquals(AgnesImages.DEFAULT, JSONObject(fromCli.output).getString("model"))
            assertEquals(List(3) { agnes.id to AgnesImages.DEFAULT }, sent)
            assertEquals(listOf(false, false, false, true), permits) // CLI must still ask fresh permission.
        } finally { ModelCostGuard.setAllowedForCurrentProcess(false) }
    }

    @Test fun `restored image pin and review use exact saved identity independently of chat`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        val image = entries.first()
        val identity = AutoModelStore.identity(image, agnes)
        AutoModelStore.setAllowUnverifiedAutomaticCost(f.context, false)
        // The text account can be fully approved on its own. That approval is not image consent.
        val chat = ProviderInstance(UUID.randomUUID().toString(), "Fixture chat", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = "https://fixture-chat.invalid/v1", tamaImportFreeOnly = true)
        val chatEntry = ModelEntry(chat.id, LLMModel("text-model", "Fixture text", "fixture",
            inputModalities = listOf("text"), outputModalities = listOf("text")))
        f.config.instances.add(chat)
        f.config.modelEntries.add(chatEntry)
        Mockito.`when`(f.repo.tamaOwnApiKey(chat)).thenReturn("fixture-chat-key")
        Mockito.`when`(f.repo.instance(chat.id)).thenReturn(chat)
        Mockito.`when`(f.repo.tamaFreeChatOnly(chat)).thenReturn(true)
        val chatIdentity = AutoModelStore.identity(chatEntry, chat)
        AutoModelStore.setApproved(f.context, chatIdentity, true)
        ImageGen.save(f.context, agnes.id, image.model.id)
        val reopened = f.reopened()
        assertEquals(image.model.id, ImageGen.savedChoice(reopened)?.model)
        assertEquals(agnes.id, ImageGen.endpoint(reopened)?.instanceId)
        val setup = requireNotNull(AutoImageSelection.agnesSetup(reopened))
        assertEquals(CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_APPROVAL, setup.problem)
        assertEquals(image.id, AutoImageSelection.prepareAgnesReview(reopened, setup))
        assertNull(AutoImageSelection.choose(reopened, freeOnly = ImageGen.freeImagesOnly(reopened, chat.id)))

        val review = AutoModelStore.review(reopened, identity)
        assertTrue(AutoModelStore.saveReview(reopened, review, chat = false, image = true,
            edits = mapOf(Need.IMAGE_GENERATION to false)))
        assertEquals(CustomImageRoute.Problem.AGNES_IMAGE_CAPABILITY_OFF, AutoImageSelection.agnesSetup(f.reopened())?.problem)
        assertNull(AutoImageSelection.choose(reopened))
        assertTrue(AutoModelStore.saveReview(reopened, AutoModelStore.review(reopened, identity),
            chat = false, image = true, edits = mapOf(Need.IMAGE_GENERATION to true)))
        assertNull(requireNotNull(AutoImageSelection.agnesSetup(f.reopened())).problem)
        for (freeOnly in listOf(true, false)) {
            val chosen = requireNotNull(AutoImageSelection.choose(f.reopened(), freeOnly))
            assertEquals(identity, chosen.identity)
            assertEquals(image.model.id, AutoImageSelection.endpointIfStillAllowed(f.reopened(), chosen)?.model)
        }
        assertTrue(AutoModelStore.approved(reopened, chatIdentity))
        assertFalse(AutoModelStore.approved(reopened, identity))

        AutoModelStore.setEnabled(reopened, false)
        assertNull(AutoImageSelection.choose(f.reopened(), freeOnly = true))
        assertEquals(agnes.id, ImageGen.endpoint(f.reopened())?.instanceId)
        assertEquals(image.model.id, ImageGen.endpoint(f.reopened())?.model)
        AutoModelStore.setEnabled(reopened, true)
        val selected = requireNotNull(AutoImageSelection.choose(f.reopened(), freeOnly = true))
        AutoModelStore.accountChanged(reopened, agnes.id)
        assertNull(AutoImageSelection.endpointIfStillAllowed(f.reopened(), selected))
        assertEquals(CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_APPROVAL, AutoImageSelection.agnesSetup(f.reopened())?.problem)
    }

    @Test fun `missing and ambiguous legacy rows do not substitute a different model`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        val image = entries.first()
        ImageGen.save(f.context, agnes.id, image.model.id)
        f.config.modelEntries.remove(image)
        assertEquals(image.model.id, ImageGen.endpoint(f.context)?.model)
        assertNull(AutoImageSelection.choose(f.context))
        val missing = requireNotNull(AutoImageSelection.agnesSetup(f.context))
        assertEquals(CustomImageRoute.Problem.AGNES_IMAGE_NEEDS_ENTRY, missing.problem)
        assertNull(missing.entryId)

        val alternate = entries.last()
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(alternate, agnes), true)
        assertNull(AutoImageSelection.choose(f.context))
        f.config.modelEntries.addAll(List(2) { ModelEntry(agnes.id, image.model, isCustom = true) })
        assertNull(ImageGen.endpoint(f.context))
        assertEquals(CustomImageRoute.Problem.AGNES_IMAGE_AMBIGUOUS, ImageGen.selectionProblem(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertNull(AutoImageSelection.prepareAgnesReview(f.context, missing))
    }

    @Test fun `an exact Agnes selection blocks on missing approval or key and permits only a free payment alternate`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        val model = entries[1].model.id
        ImageGen.save(f.context, agnes.id, model)
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(entries[0], agnes), true)
        assertEquals(model, ImageGen.endpoint(f.context)?.model)
        assertNull(AutoImageSelection.choose(f.context))
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(entries[1], agnes), true)
        val selected = requireNotNull(AutoImageSelection.choose(f.context))
        assertEquals(model, selected.modelId)
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(f.context, selected))

        Mockito.`when`(f.repo.tamaPaymentRefused(agnes.id, model)).thenReturn(true)
        assertEquals(CustomImageRoute.Problem.AGNES_PAYMENT, ImageGen.selectionProblem(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertNull(ImageGen.endpoint(f.context)) // Manual never switches after payment.
        val alternate = requireNotNull(AutoImageSelection.choose(f.context, freeOnly = true, excluded = setOf(agnes.id to model)))
        assertEquals(agnes.id, alternate.providerId)
        assertEquals(AgnesImages.DEFAULT, alternate.modelId)
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(f.context, alternate))
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(entries[0], agnes), false)
        assertNull(AutoImageSelection.choose(f.context, freeOnly = true, excluded = setOf(agnes.id to model)))

        Mockito.`when`(f.repo.tamaOwnApiKey(agnes)).thenReturn(null)
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertEquals(CustomImageRoute.Problem.NEEDS_KEY, ImageGen.selectionProblem(f.context))
        ImageGen.clear(f.context)
        assertNull(ImageGen.endpoint(f.context)) // Even an unset slot may not go paid when Agnes lacks its key.
        assertNull(AutoImageSelection.choose(f.context))
    }

    @Test fun `legacy custom duplicate uses the unique bundled entry without inheriting its approval`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        val bundled = entries.first()
        val custom = ModelEntry(agnes.id, bundled.model.copy(displayName = "Custom duplicate"), isCustom = true)
        f.config.modelEntries.add(0, custom)
        ImageGen.save(f.context, agnes.id, bundled.model.id)
        assertEquals(AgnesImages.DEFAULT, ImageGen.endpoint(f.context)?.model)
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(custom, agnes), true)
        assertNull(AutoImageSelection.choose(f.context))
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(bundled, agnes), true)
        val chosen = requireNotNull(AutoImageSelection.choose(f.context))
        assertEquals(bundled.id, chosen.entryId)
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(f.context, chosen))

        f.config.modelEntries[f.config.modelEntries.indexOf(bundled)] = bundled.copy(isHidden = true)
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertNull(AutoImageSelection.endpointIfStillAllowed(f.context, chosen))
        assertEquals(CustomImageRoute.Problem.AGNES_UNAVAILABLE, ImageGen.selectionProblem(f.context))

        // Two bundled rows are ambiguous; never take one by position, even with approval.
        f.config.modelEntries.add(bundled.copy(uuid = UUID.randomUUID().toString()))
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
    }

    @Test fun `unavailable saved Agnes choice cannot become a paid route or revive after a new choice`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val f = fixture()
        val (agnes, entries) = f.addAgnes()
        AutoModelStore.setImageApproved(f.context, AutoModelStore.identity(entries.first(), agnes), true)
        ImageGen.save(f.context, agnes.id, AgnesImages.DEFAULT)
        val selected = requireNotNull(AutoImageSelection.choose(f.context))
        ImageGen.save(f.context, f.identity.providerId, f.identity.modelId)
        assertNull(AutoImageSelection.endpointIfStillAllowed(f.context, selected))
        ImageGen.save(f.context, agnes.id, AgnesImages.DEFAULT)
        assertNull(AutoImageSelection.endpointIfStillAllowed(f.context, selected))
        agnes.imageEndpointMode = ImageEndpointMode.chatCompletions
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertEquals(CustomImageRoute.Problem.AGNES_UNAVAILABLE, ImageGen.selectionProblem(f.context))
        agnes.imageEndpointMode = ImageEndpointMode.auto

        // The same ID pasted on another provider is not the Agnes route.
        ImageGen.save(f.context, f.identity.providerId, AgnesImages.DEFAULT)
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertEquals(CustomImageRoute.Problem.AGNES_UNAVAILABLE, ImageGen.selectionProblem(f.context))
        ImageGen.save(f.context, agnes.id, AgnesImages.DEFAULT)
        // A deleted account must not be resolved by the old slot's position or label.
        f.config.instances.remove(agnes)
        Mockito.`when`(f.repo.instance(agnes.id)).thenReturn(null)
        assertNull(ImageGen.endpoint(f.context))
        assertNull(AutoImageSelection.choose(f.context))
        assertEquals(CustomImageRoute.Problem.AGNES_UNAVAILABLE, ImageGen.selectionProblem(f.context))
    }

    @Test fun `parallel selection does not survive consent capability or account ABA`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val (context, identity) = fixture()
        val first = requireNotNull(AutoImageSelection.choose(context))
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, first))
        AutoModelStore.setImageApproved(context, identity, false)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, first))

        val second = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, false)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, second))

        val third = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.accountChanged(context, identity.providerId)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, third))
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, requireNotNull(AutoImageSelection.choose(context))))
    }

    @Test fun `normal selection keeps its previous revalidation behavior`() {
        assumeTrue(BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test")
        val (context, identity) = fixture()
        val selection = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.setImageApproved(context, identity, false)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, selection))
    }
}
