package dev.charaly.runtime.pack

/**
 * The demo worlds Charaly ships with.
 *
 * They are ordinary [StoryPack] objects loaded through the ordinary
 * `CharalyRuntime.createPack` path - there is no privileged "demo" code path, no
 * bundled JSON blob, and no network fetch. Whatever a demo pack can do, a user's
 * own pack can do.
 *
 * Isolation is structural: every pack owns its own ids, world state, relationships,
 * knowledge, memories, events, threads and sessions. There is no shared mutable
 * state between them, and nothing in the runtime can address a character in another
 * pack, because a StoryInstance only ever resolves ids against *its own* pack.
 */
object DemoStoryPacks {

    /** The three showcase packs, in the order the library features them. */
    val all: List<dev.charaly.runtime.domain.StoryPack> = listOf(
        MiraculousPack.pack,
        NeonDistrictPack.pack,
        LastKingdomPack.pack,
    )

    fun byId(id: String): dev.charaly.runtime.domain.StoryPack? =
        all.firstOrNull { it.id.value == id }

    /**
     * The small canonical test world ("The Lamplighter's Ledger").
     *
     * Kept because the runtime test-suite uses it, and because it is a genuinely
     * good example of knowledge separation: the hidden ledger exists in world
     * truth, exactly one character knows where it is, and nothing else does.
     */
    val lamplighter: dev.charaly.runtime.domain.StoryPack by lazy {
        dev.charaly.runtime.engine.SampleWorlds.libraryPack(
            dev.charaly.runtime.domain.StoryPackId("pack-library"),
        )
    }

    /** Every pack that should exist on a fresh install. */
    fun initialLibrary(): List<dev.charaly.runtime.domain.StoryPack> = all + lamplighter
}