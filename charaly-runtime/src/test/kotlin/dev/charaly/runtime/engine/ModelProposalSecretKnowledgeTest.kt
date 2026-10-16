package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.InitialKnowledge
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.SecretRevealed
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO MAY HAND A CHARACTER A SECRET.
 *
 * Phase 5 recorded the hole (risk 1): `ActionValidator.DISCOVER` turned any *existing*
 * fact - secrets included - into a `KnowledgeDiscovered` event, and nothing in the
 * authoritative layer asked where that knowledge came from. A model could therefore write
 * `discover fact-<secret>` and gain the very knowledge that `SecretRevealed` guards with a
 * revealer check, bypassing reveal authority entirely.
 *
 * The rule under test, in one sentence: **a secret reaches a character through an
 * authorized event path (pack seeding, an authored engine grant, or a reveal by somebody
 * who knows it) - never through a model proposal.** A model's own claim is a proposal, not
 * an information source. Ordinary, non-secret facts keep working exactly as before, and so
 * does reinforcing what a character already knows.
 *
 * The assertions deliberately name the refusal by its diagnostic wording rather than an
 * enum constant so this file compiles and fails against the un-fixed engine: that red run
 * is the evidence the risk is real (see `PHASE6_PERFORMANCE_REPORT.md`).
 */
class ModelProposalSecretKnowledgeTest {

    private val hall = LocationId("loc-hall")
    private val garden = LocationId("loc-garden")

    private val alice = CharacterId("ch-alice")
    private val bob = CharacterId("ch-bob")
    private val carol = CharacterId("ch-carol")

    private val secretId = FactId("fact-secret")
    private val publicId = FactId("fact-poster")
    private val missingId = FactId("fact-not-registered")

    private val secret = Fact(
        id = secretId,
        description = "Mektup portrenin arkasina saklanmis.",
        secret = true,
    )
    private val publicFact = Fact(
        id = publicId,
        description = "Panoda bu aksam kütüphanenin geç açık kalacagi yaziyor.",
        secret = false,
    )

    private fun pack(seededKnower: CharacterId? = null): StoryPack = StoryPack(
        id = StoryPackId("pack.discover-authority"),
        title = "Discover Authority",
        characters = listOf(
            CharacterDefinition(id = alice, name = "Alice"),
            CharacterDefinition(id = bob, name = "Bob"),
            CharacterDefinition(id = carol, name = "Carol"),
        ),
        locations = listOf(
            Location(id = hall, name = "Salon", connections = listOf(garden)),
            Location(id = garden, name = "Bahce", connections = listOf(hall)),
        ),
        initialWorldState = InitialWorldState(
            startLocations = mapOf(alice to hall, bob to garden, carol to hall),
        ),
        initialKnowledge = InitialKnowledge(
            facts = listOf(secret, publicFact),
            characterKnowledge = buildMap {
                seededKnower?.let { put(it, listOf(secretId.value)) }
            },
        ),
    )

    private fun engine(of: StoryPack): EventEngine =
        EventEngine(dev.charaly.runtime.domain.WorldDefinition(of.characters, of.locations))

    private class Scripted(private val replies: List<String>) : InferenceEngine {
        private var index = 0
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome =
            LoadOutcome.Loaded(ModelInfo(request.installedModelId, request.path, "scripted"))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult(next())
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = next()))
            emit(StreamChunk("", done = true))
        }

        private fun next(): String = replies.getOrElse(index++) { replies.lastOrNull().orEmpty() }

        override fun stop() = Unit
        override fun isLoaded() = true
        override fun modelInfo() = ModelInfo("scripted", "/m.gguf", "scripted")
    }

    private suspend fun started(
        of: StoryPack,
        storage: InMemoryCharalyStorage = InMemoryCharalyStorage(),
    ): Triple<CharalyRuntime, StoryInstance, JsonCharalyRepository> {
        val repository = JsonCharalyRepository(storage)
        val runtime = CharalyRuntime(repository = repository, engine = MockInferenceEngine())
        runtime.createPack(of)
        val created = runtime.startStory(
            of,
            StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-discover-authority"),
                focusCharacterId = alice,
            ),
        )
        return Triple(runtime, created, repository)
    }

    private fun EventApplication.refusedAs(word: String): EventApplication.Rejected {
        assertTrue("expected a refusal, got $this", this is EventApplication.Rejected)
        val rejected = this as EventApplication.Rejected
        assertTrue(
            "expected a \"$word\" refusal, got ${rejected.validation.describe()}",
            rejected.validation.describe().contains(word),
        )
        return rejected
    }

    // ------------------------------------------------------------------
    // The rule
    // ------------------------------------------------------------------

    @Test
    fun `a model proposal cannot teach a character a secret fact`() = runTest {
        val of = pack()
        val (_, instance, _) = started(of)

        val attempted = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = alice, factId = secretId, via = "kendim buldum"),
            origin = EventOrigin.MODEL_PROPOSAL,
        )
        attempted.refusedAs("secret")

        assertFalse(
            "a refused disclosure must leave no knowledge behind",
            instance.knowledge.knows(alice, secretId),
        )
    }

    @Test
    fun `ordinary non-secret facts are still discovered by a model proposal`() = runTest {
        val of = pack()
        val (_, instance, _) = started(of)

        val applied = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = alice, factId = publicId, via = "panodu okudu"),
            origin = EventOrigin.MODEL_PROPOSAL,
        )
        assertTrue("a public fact must still be discoverable, got $applied", applied is EventApplication.Applied)
        val after = (applied as EventApplication.Applied).instance
        assertTrue("and it must be recorded", after.knowledge.knows(alice, publicId))
    }

    @Test
    fun `an unregistered fact id is refused as unknown, not as a secret problem`() = runTest {
        val of = pack()
        val (_, instance, _) = started(of)

        val attempted = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = alice, factId = missingId, via = "uydurma"),
            origin = EventOrigin.MODEL_PROPOSAL,
        )
        val rejected = attempted.refusedAs("unknown fact")
        assertFalse(
            "an unknown fact must not be reported as a secret violation",
            rejected.validation.describe().contains("secret"),
        )
    }

    @Test
    fun `reinforcing a secret the character already knows is not a violation`() = runTest {
        val of = pack(seededKnower = bob)
        val (_, instance, _) = started(of)

        val reinforced = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = bob, factId = secretId, via = "teyit ettim"),
            origin = EventOrigin.MODEL_PROPOSAL,
        )
        assertTrue(
            "telling a knower what they already know teaches nothing new and must not be refused, got $reinforced",
            reinforced is EventApplication.Applied,
        )
    }

    // ------------------------------------------------------------------
    // The authorized paths stay authorized
    // ------------------------------------------------------------------

    @Test
    fun `story pack seeding still hands a character its starting secrets`() = runTest {
        val (_, instance, _) = started(pack(seededKnower = bob))
        assertTrue("pack seeding is an authorized source", instance.knowledge.knows(bob, secretId))
    }

    @Test
    fun `an authored engine grant still teaches a secret`() = runTest {
        val of = pack()
        val (_, instance, _) = started(of)

        val granted = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = carol, factId = secretId, via = "itiraf"),
            origin = EventOrigin.ENGINE,
        )
        assertTrue("an authored engine event is an authorized source, got $granted", granted is EventApplication.Applied)
    }

    @Test
    fun `a reveal by a knower still spreads the secret and is still the way it spreads`() = runTest {
        val of = pack(seededKnower = bob)
        val (_, instance, _) = started(of)

        val revealed = engine(of).applyImmediately(
            instance,
            SecretRevealed(
                revealedBy = bob,
                factId = secretId,
                recipients = listOf(alice),
            ),
            origin = EventOrigin.ENGINE,
        )
        assertTrue("a knower may still reveal, got $revealed", revealed is EventApplication.Applied)
        assertTrue(
            "the recipient learns it from the reveal",
            (revealed as EventApplication.Applied).instance.knowledge.knows(alice, secretId),
        )
    }

    @Test
    fun `a reveal by a non-knower is still refused`() = runTest {
        val of = pack(seededKnower = bob)
        val (_, instance, _) = started(of)

        engine(of).applyImmediately(
            instance,
            SecretRevealed(revealedBy = carol, factId = secretId, recipients = listOf(alice)),
            origin = EventOrigin.ENGINE,
        ).refusedAs("revealer does not know")
    }

    // ------------------------------------------------------------------
    // Refusal hygiene and persistence
    // ------------------------------------------------------------------

    @Test
    fun `a refused disclosure leaves no residue and the rule survives a reload`() = runTest {
        val of = pack(seededKnower = bob)
        val storage = InMemoryCharalyStorage()
        val (_, instance, repository) = started(of, storage)

        val attempted = engine(of).applyImmediately(
            instance,
            KnowledgeDiscovered(characterId = alice, factId = secretId, via = "okudum"),
            origin = EventOrigin.MODEL_PROPOSAL,
        )
        attempted.refusedAs("secret")

        // Persist the untouched instance and read it back with a brand-new repository.
        repository.saveInstance(instance)
        val reloaded = JsonCharalyRepository(storage).getInstance(instance.id)!!

        assertEquals("the refused disclosure must not reach disk", instance.knowledge, reloaded.knowledge)
        assertFalse("and nobody learned the secret", reloaded.knowledge.knows(alice, secretId))

        engine(of).applyImmediately(
            reloaded,
            KnowledgeDiscovered(characterId = carol, factId = secretId, via = "okudum"),
            origin = EventOrigin.MODEL_PROPOSAL,
        ).refusedAs("secret")
    }

    // ------------------------------------------------------------------
    // The real story pipeline (ContextBuilder -> parser -> validator -> EventEngine)
    // ------------------------------------------------------------------

    @Test
    fun `the story pipeline refuses a model discover of a secret and applies one of a public fact`() = runTest {
        val base = dev.charaly.runtime.pack.MiraculousPack.pack
        val marinette = CharacterId(dev.charaly.runtime.pack.MiraculousPack.MARINETTE)
        val secretFactId = base.initialKnowledge.facts.first { it.secret }.id
        val publicFactId = FactId("fact-ilan")
        val of = base.copy(
            initialKnowledge = base.initialKnowledge.copy(
                facts = base.initialKnowledge.facts + Fact(
                    id = publicFactId,
                    subject = "ilan",
                    predicate = "panoda",
                    description = "Belediye panosunda bu aksam kulenin saat 23:00'e kadar açik kalacagi yaziyor.",
                ),
            ),
        )

        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(
            repository = repository,
            engine = Scripted(
                listOf(
                    "«Su ortada.» <charaly:action type=\"discover\" character=\"${marinette.value}\" " +
                        "target=\"${secretFactId.value}\" reason=\"kendim buldum\"/>",
                    "«İlanı okudum.» <charaly:action type=\"discover\" character=\"${marinette.value}\" " +
                        "target=\"${publicFactId.value}\" reason=\"panoyu kendim okudum\"/>",
                ),
            ),
        )
        runtime.createPack(of)
        var instance = runtime.startStory(
            of,
            StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-discover-pipeline"),
                title = "Yetki",
                scenario = of.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(
                    installedModelId = "scripted",
                    modelDisplayName = "Scripted",
                    profile = ModelProfileLibrary.all.first(),
                ),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        // 1. The model asks for a secret it was never given.
        val secretUpdates = mutableListOf<GenerationUpdate>()
        runtime.respond(
            instance,
            "Bu bilginin kaynağı ne?",
            marinette,
        ).collect { update ->
            secretUpdates += update
            (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
        }
        assertTrue(
            "the secret disclosure must be refused visibly, not silently ignored",
            secretUpdates.any { it is GenerationUpdate.RejectedAction },
        )
        assertFalse(
            "and the character must not end up knowing the secret",
            instance.knowledge.knows(marinette, secretFactId),
        )

        // 2. The same pipeline applies the discovery of an ordinary fact.
        val publicUpdates = mutableListOf<GenerationUpdate>()
        runtime.respond(
            instance,
            "Panoda ne yazıyor?",
            marinette,
        ).collect { update ->
            publicUpdates += update
            (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
        }
        assertTrue(
            "an ordinary fact discovery must still be applied",
            publicUpdates.any { it is GenerationUpdate.AppliedAction },
        )
        assertTrue(
            "and it must reach knowledge",
            instance.knowledge.knows(marinette, publicFactId),
        )
    }
}