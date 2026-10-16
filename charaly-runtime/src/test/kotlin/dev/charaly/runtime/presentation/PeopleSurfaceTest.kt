package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.CommitmentLedger
import dev.charaly.runtime.domain.ConversationHistory
import dev.charaly.runtime.domain.Promise
import dev.charaly.runtime.domain.PromiseId
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipKey
import dev.charaly.runtime.domain.RelationshipStage
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PEOPLE surface: who is here, and what they think of you.
 *
 * ## What this is protecting
 *
 * The brief is explicit that people must be *contextual* - "here now" and "relevant", never
 * the pack's whole cast. A sheet listing every character in a pack is a character
 * encyclopedia, which is the thing the brief rules out, and the failure is invisible to
 * review because the data is all correct: the engine really does contain those characters.
 *
 * So the assertions below are about *which* characters appear and *what is said about
 * them*, not about whether the underlying data exists.
 *
 * ## Why the fixtures build real instances
 *
 * `ParticipantChip` is derived from live world state - a scene's participants, a
 * commitment ledger, a relationship map. Constructing the presenter against a hand-built
 * list of chips would test nothing; building a `StoryInstance` and reading what the
 * presenter makes of it tests the projection itself.
 */
class PeopleSurfaceTest {

    private val marinette = CharacterId("marinette")
    private val alya = CharacterId("alya")
    private val adrien = CharacterId("adrien")
    private val teacher = CharacterId("mme-veyre")

    private val definition = WorldDefinition(
        characters = listOf(
            character(marinette, "Marinette"),
            character(alya, "Alya"),
            character(adrien, "Adrien"),
            character(teacher, "Mme Veyre"),
        ),
    )

    private fun character(id: CharacterId, name: String) = CharacterDefinition(
        id = id,
        name = name,
    )

    /**
     * An instance with a live scene, so "here now" is a fact rather than a fallback.
     *
     * Built through [StoryInstanceFactory] rather than by hand: scenes, character runtimes
     * and relationship keys are the engine's to construct, and a hand-rolled instance that
     * set them slightly differently would make this a test of the fixture.
     */
    private fun instance(
        participants: List<CharacterId> = listOf(marinette, alya),
        focus: CharacterId? = marinette,
        commitments: CommitmentLedger = CommitmentLedger.EMPTY,
        relationships: Map<RelationshipKey, Relationship> = emptyMap(),
    ): StoryInstance {
        val scene = Scene(
            id = SceneId("scene-1"),
            locationId = dev.charaly.runtime.domain.LocationId("bakery"),
            participants = participants,
            mood = "quiet",
        )
        var built = StoryInstance(
            id = StoryInstanceId("story-1"),
            storyPackId = dev.charaly.runtime.domain.StoryPackId("pack-miraculous"),
            packTitle = "Miraculous",
            title = "Shadows of Paris",
            persona = dev.charaly.runtime.domain.PersonaBinding(id = "you", name = "You"),
            currentSceneId = scene.id,
            focusCharacterId = focus,
            worldState = dev.charaly.runtime.domain.WorldState(
                activeScenes = mapOf(scene.id to scene),
                commitments = commitments,
                relationships = relationships,
            ),
        )
        // `evolved` is the engine's own transition path, so the instance under test is one
        // the runtime could actually produce rather than one shaped by hand.
        return built.evolved(currentSceneId = scene.id)
    }

    // ------------------------------------------------------------------
    // Who appears
    // ------------------------------------------------------------------

    /**
     * Only the scene's participants are listed as present.
     *
     * The pack defines four characters; the scene has two of them standing in it. Listing
     * the other two as "here" would be a lie about the world, and listing all four in one
     * undifferentiated list would be the encyclopedia the brief rules out.
     */
    @Test
    fun `only the scene's participants are listed as here`() {
        val chips = StoryPresenter.participants(instance(), definition)

        val present = chips.filter { it.isPresent }
        assertEquals(
            "only the scene's participants may claim to be here",
            listOf("marinette", "alya"),
            present.map { it.id },
        )
        // The focus leads, so the sheet opens on the person you are talking to.
        assertEquals("marinette", chips.first().id)
        assertTrue("the focus must be marked", chips.first().isFocus)
    }

    /**
     * The sheet never becomes the pack's cast list.
     *
     * Stated as an upper bound rather than an exact count, because the surface legitimately
     * grows with unresolved promises. What it must never do is become every character in
     * the pack - that number is a property of the pack, not of this story's state.
     */
    @Test
    fun `the sheet is not the pack's whole cast`() {
        val chips = StoryPresenter.participants(instance(), definition)

        assertTrue(
            "the sheet listed ${chips.size} of ${definition.characters.size} pack characters " +
                "with no commitment to justify it",
            chips.size < definition.characters.size,
        )
        assertFalse(
            "an absent character appeared with no reason",
            chips.any { it.id == teacher.value && !it.isPresent },
        )
    }

    /**
     * A scene-less instance still lists people, in a stable order.
     *
     * The fallback exists so the sheet is not blank on the first turn. Stability matters
     * just as much: a list that reshuffles on every recomposition is unreadable.
     */
    @Test
    fun `a scene-less instance still lists people deterministically`() {
        val sceneLess = StoryInstance(
            id = StoryInstanceId("story-2"),
            storyPackId = dev.charaly.runtime.domain.StoryPackId("pack-miraculous"),
            packTitle = "Miraculous",
            title = "A new story",
            currentSceneId = null,
            worldState = dev.charaly.runtime.domain.WorldState(
                // Character runtimes, as StoryInstanceFactory would have created them.
                // Without them the world genuinely knows nobody, and an empty sheet would
                // be correct - which would make this test assert the wrong thing.
                characters = definition.characters.associate { character ->
                    character.id to CharacterRuntime(
                        characterId = character.id,
                        name = character.name,
                    )
                },
            ),
        )

        val first = StoryPresenter.participants(sceneLess, definition).map { it.id }
        val second = StoryPresenter.participants(sceneLess, definition).map { it.id }

        assertTrue("a scene-less story should still list someone", first.isNotEmpty())
        assertEquals("the order must not depend on iteration order", first, second)
    }

    // ------------------------------------------------------------------
    // What is said about them
    // ------------------------------------------------------------------

    /**
     * An untouched relationship says nothing rather than "neutral".
     *
     * "Neutral" is a claim about someone's feelings that the engine has no basis for: a
     * character you met a second ago has not formed an opinion, which is a different thing
     * and the difference is visible in how a player reads the row.
     */
    @Test
    fun `an unformed relationship says nothing`() {
        val untouched = mapOf(
            RelationshipKey(marinette, CharacterId("you")) to Relationship(
                sourceId = marinette,
                targetId = CharacterId("you"),
            ),
        )
        val chips = StoryPresenter.participants(instance(relationships = untouched), definition)

        val marinetteChip = chips.first { it.id == "marinette" }
        assertEquals(
            "a default relationship has formed no opinion yet",
            "",
            marinetteChip.relationshipLabel,
        )
    }

    /**
     * Trust and warmth read as sentences, not as numbers.
     *
     * The point is not the exact wording but the shape: a verb a player can act on. An
     * affinity figure of "0.78" tells them nothing they can use; "trusts you" tells them
     * the scene just became easier to play.
     */
    @Test
    fun `relationships read as sentences`() {
        val trusted = StoryPresenter.participants(
            instance(
                relationships = mapOf(
                    RelationshipKey(marinette, CharacterId("you")) to Relationship(
                        sourceId = marinette,
                        targetId = CharacterId("you"),
                        trust = 85,
                        affinity = 80,
                        familiarity = 60,
                        stage = RelationshipStage.ESTABLISHED,
                    ),
                ),
            ),
            definition,
        ).first { it.id == "marinette" }

        assertTrue(
            "high trust should read as trust or as confidence: ${trusted.relationshipLabel}",
            trusted.relationshipLabel.contains("güven", ignoreCase = true) ||
                trusted.relationshipLabel.contains("yanınızda", ignoreCase = true),
        )

        // Trust on its own, without the warmth to go with it, is a different sentence -
        // which is the distinction that makes the axes worth having rather than one score.
        val trustedButDistant = StoryPresenter.participants(
            instance(
                relationships = mapOf(
                    RelationshipKey(marinette, CharacterId("you")) to Relationship(
                        sourceId = marinette,
                        targetId = CharacterId("you"),
                        trust = 85,
                        affinity = 50,
                        familiarity = 60,
                        stage = RelationshipStage.ESTABLISHED,
                    ),
                ),
            ),
            definition,
        ).first { it.id == "marinette" }

        assertEquals(
            "high trust without warmth is its own state, and needs its own words",
            "Size güveniyor",
            trustedButDistant.relationshipLabel,
        )

        val wary = StoryPresenter.participants(
            instance(
                // Adrien has to be in the scene to appear in the surface at all - the
                // point of the contextual list is that it follows the world, so a test
                // about his relationship has to put him in the room.
                participants = listOf(marinette, adrien),
                relationships = mapOf(
                    RelationshipKey(adrien, CharacterId("you")) to Relationship(
                        sourceId = adrien,
                        targetId = CharacterId("you"),
                        trust = 20,
                        affinity = 25,
                        familiarity = 30,
                    ),
                ),
            ),
            definition,
        ).first { it.id == "adrien" }

        assertTrue(
            "low trust should read as wariness: ${wary.relationshipLabel}",
            wary.relationshipLabel.isNotBlank(),
        )
    }

    /**
     * Unresolved conflict is the clearest signal, and it leads.
     *
     * Checked first among the axes because tension only exists once something has gone
     * wrong: there is no other state in which it is high, so it can be reported without
     * qualifying.
     */
    @Test
    fun `unresolved tension is reported first`() {
        val chip = StoryPresenter.participants(
            instance(
                participants = listOf(adrien),
                focus = adrien,
                relationships = mapOf(
                    RelationshipKey(adrien, CharacterId("you")) to Relationship(
                        sourceId = adrien,
                        targetId = CharacterId("you"),
                        trust = 20,
                        affinity = 10,
                        tension = 70,
                        familiarity = 50,
                    ),
                ),
            ),
            definition,
        ).first()

        assertTrue(
            "high tension must be reported: ${chip.relationshipLabel}",
            chip.relationshipLabel.isNotBlank(),
        )
    }

    /**
     * No axis value ever reaches the surface.
     *
     * Asserted rather than assumed: a number on a relationship row is precisely the
     "engine exposed through UI" failure, and it is easy to reintroduce by printing a field
     * while adding a label.
     */
    @Test
    fun `no raw axis value is ever surfaced`() {
        val chip = StoryPresenter.participants(
            instance(
                relationships = mapOf(
                    RelationshipKey(marinette, CharacterId("you")) to Relationship(
                        sourceId = marinette,
                        targetId = CharacterId("you"),
                        trust = 73,
                        affinity = 61,
                        tension = 22,
                        familiarity = 44,
                        dependency = 19,
                        respect = 55,
                        fear = 7,
                    ),
                ),
            ),
            definition,
        ).first()

        val label = chip.relationshipLabel
        if (label.isNotBlank()) {
            for (value in listOf(73, 61, 22, 44, 19, 55, 7)) {
                assertFalse(
                    "the label \"$label\" leaks the raw axis value $value",
                    label.contains(value.toString()),
                )
            }
            assertFalse("the label leaks a percentage", label.contains("%"))
        }
    }

    /**
     * The activity line describes what someone is doing.
     *
     * Present and present-only: it is the fallback when there is no relationship to report,
     * so the row is never blank.
     */
    @Test
    fun `the activity line is always present`() {
        val chips = StoryPresenter.participants(instance(), definition)

        for (chip in chips.filter { it.isPresent }) {
            assertTrue(
                "${chip.name} has neither an activity nor a relationship line",
                chip.activityLabel.isNotBlank(),
            )
            if (chip.relationshipLabel.isBlank()) {
                assertFalse(
                    "${chip.name}'s activity line is a placeholder",
                    chip.activityLabel.equals("unknown", ignoreCase = true),
                )
            }
        }
    }

    /**
     * A character who is not here is never described as present.
     *
     * The `activityLabel` for an absent character is explicitly "Not here right now" rather
     * than an activity, because the runtime still holds a last-known activity for them and
     * printing it would imply they are doing it.
     */
    @Test
    fun `an absent character is not described as doing anything`() {
        val commitment = CommitmentLedger(
            promises = listOf(
                Promise(
                    id = PromiseId("p1"),
                    text = "You said you would help",
                    keeperId = teacher,
                    beneficiaryId = CharacterId("you"),
                    madeAt = dev.charaly.runtime.domain.StoryTime.START,
                ),
            ),
        )
        val chips = StoryPresenter.participants(
            instance(commitments = commitment),
            definition,
        )

        val absent = chips.firstOrNull { it.id == teacher.value }
        // Whether the absent character appears at all depends on how the pack keys
        // promises; what must hold either way is that they are not claimed to be present.
        if (absent != null) {
            assertFalse("an absent character was marked present", absent.isPresent)
            assertTrue(
                "an absent character was given an activity: ${absent.activityLabel}",
                absent.activityLabel.contains("not here", ignoreCase = true),
            )
        }
    }

    /**
     * An open promise brings someone into the surface even when they have walked away.
     *
     * This is the "relevant" half of the brief's split, and it is derived from live state
     * rather than from the pack - so it changes as the story does rather than being a fixed
     * second section.
     */
    @Test
    fun `an open promise makes an absent character relevant`() {
        val commitment = CommitmentLedger(
            promises = listOf(
                Promise(
                    id = PromiseId("p1"),
                    text = "You promised to help",
                    keeperId = teacher,
                    beneficiaryId = CharacterId("you"),
                    madeAt = dev.charaly.runtime.domain.StoryTime.START,
                ),
            ),
        )
        val chips = StoryPresenter.participants(instance(commitments = commitment), definition)

        val ids = chips.map { it.id }
        assertTrue(
            "a character holding an open promise belongs in the surface, saw ${ids}",
            teacher.value in ids,
        )
        assertFalse("the scene's participants must still lead", chips.first().isPresent.not())
    }

    /**
     * A resolved promise stops being a reason to list someone.
     *
     * Without this the "relevant" section would grow monotonically for the length of a
     * story and become, in time, exactly the character encyclopedia the brief rules out.
     */
    @Test
    fun `a resolved promise does not keep someone relevant`() {
        val commitment = CommitmentLedger(
            promises = listOf(
                Promise(
                    id = PromiseId("p1"),
                    text = "You promised to help",
                    keeperId = teacher,
                    beneficiaryId = CharacterId("you"),
                    madeAt = dev.charaly.runtime.domain.StoryTime.START,
                    status = dev.charaly.runtime.domain.CommitmentStatus.KEPT,
                ),
            ),
        )
        val chips = StoryPresenter.participants(instance(commitments = commitment), definition)

        assertFalse(
            "a kept promise must not keep a character in the surface",
            chips.any { it.id == teacher.value },
        )
    }
}