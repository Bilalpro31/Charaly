# CHARALY

**A local-first, deterministic world simulator for Android.**

Charaly is not a chat app with several bots in it. It is a world: characters, places,
relationships, knowledge and memory that keep existing when you are not talking to
anyone. You *enter* a world, you do not submit to a conversation.

The language model writes prose. Charaly's runtime owns the truth.

```
        Android UI  (Jetpack Compose)
              |
        CharalyRuntime            <- orchestration, streaming, cancellation
              |
   +----------+-----------+------------------+----------------+
   |          |           |                  |                |
SceneDirector ContextBuilder  EventEngine   KnowledgeStore   MemoryStore
   |          |           |                  |                |
   +----------+-----------+------------------+----------------+
              |
        InferenceEngine        <- port (interface)
              |
   LocalLlamaInferenceEngine   <- llama.cpp via JNI, in-process
              |
          GGUF on device
```

There is **no server**. Not `localhost:8080`, not FastAPI, not a PC acting as an
inference host, not a cloud API. Inference happens inside the app process, so the
app works in airplane mode after a model has been imported.

---

## The world, not the chat

Three properties define the product, and each is enforced by code rather than by
convention:

**A character exists because the pack says it does.** `CharacterRole` distinguishes
core cast, a meaningful recurring NPC, and background crowd. An NPC - the ice-cream
vendor, the school principal - has a `Routine`, and the engine moves him through his
day when story time passes, whether or not you have ever addressed him. You meet him by
walking into his shop.

**Knowledge is character-scoped.** A secret told to one character is not merely hidden
from another's prompt; it is unreachable by construction. `Memory.visibleTo(subject)`
is a closed, total function, and a memory a character may not see can never be returned
by retrieval regardless of how relevant it scores.

**The world changes only through validated events.** The model proposes; the engine
disposes. A routine relocation is a distinct payload from a movement for a real reason -
`CharacterMoved` is a traversal validated against the location graph, while a schedule
is authoritative world data - and both are logged and replayable.

### Where each guarantee is tested

| Guarantee | Test |
| --- | --- |
| NPCs move through their day | `RoutineTest`, `MiraculousWorldPopulationTest` |
| NPCs exist without interaction | `WorldSimulationTest` |
| Secrets do not leak | `LayeredMemoryTest`, `WorldSimulationTest` |
| Memory does not grow forever | `LayeredMemoryTest`, `MemoryLifecycleTest` |
| Contradictions resolve | `LayeredMemoryTest` |
| The LLM cannot write the world | `WorldSimulationTest` |
| Story Packs do not crash | `NestedScrollGuardTest`, `RoutesAreRenderableTest` |
| Old saves still open | `SchemaMigrationTest` |
| The library tells the truth about models | `ModelEngineCompatibilityTest` |

---

### Model compatibility is derived, never asserted

A Hugging Face identifier is a *publication* fact. Whether a GGUF can be loaded by the
llama.cpp compiled into the APK is an *engine* fact, and the two differ. So
`EngineCapabilities.ARCHITECTURES` mirrors the vendored engine's architecture table, and
every catalog entry's verdict is computed from it. A test reads
`llama-arch.cpp` directly, so bumping llama.cpp without updating the list fails the
build rather than silently mislabelling the library.

This is why **Gemma 4 E2B and E4B are in the catalog but marked "Coming"**. They are
real Google models and their official ids are recorded - but the bundled engine
(commit `5143fa895`, 2025-09-05) registers `gemma`, `gemma2`, `gemma3`, `gemma3n` and
`gemma-embedding`, and **no `gemma4`**. Offering a download for a file that cannot be
loaded is worse than not offering one, so the library says exactly that instead.

### Downloads do not lie

The core build declares no `INTERNET` permission, which is a machine-checkable privacy
guarantee and the reason conversations cannot leave the device. `ModelDownloadManager`
is a port; the offline core wires `UnavailableModelDownloads`, which fails immediately
with the precise reason and emits no progress at all. `DownloadStateMachine` makes it
structurally impossible for a partial file to be presented as installed. A future
network-enabled variant implements the same interface without core inference ever
becoming network-dependent.

### Visuals can never fail to render

"No broken image, no empty remote URL, no failed-to-load box" is only enforceable if
resolution happens *before* the view is involved. `VisualResolver` turns any visual
entity into a `ResolvedVisual` that is always drawable: a bundled licensed asset, then
the user's own image, then a generated composition from a stable seed. A screen consumes
that and has no fallback branch of its own to get wrong.

`AssetSource` is the honest half. `BUNDLED_LICENSED` and `GENERATED_ORIGINAL` may ship in
the repository; `USER_PROVIDED` and `REMOTE_OPTIONAL` may not, and the type says so.
An optional remote asset is never *selected* in the offline core - a real picture always
beats a promise the app cannot keep.

Artwork is presentation metadata and is structurally incapable of being world state.

### Settings do not lie either

The Settings screen offers dark theme and reduce motion. Dark theme is wired. Reduce
motion **was not** - it saved a preference that nothing read, so a user who asked for
less movement got the same animations while believing otherwise. `MotionPolicy` now
resolves both the in-app switch and Android's own "Remove animations" setting, and every
animated affordance takes its duration from `motionDuration()` rather than hard-coding a
literal. The system setting wins, because a user who has told the OS to stop animations
should not have to tell every app again.

Progress indicators are deliberately exempt: a spinner that does not spin reads as
"stuck", not as "quiet".

---

## Why the architecture looks like this

The single most important rule in this codebase:

> **The LLM is a language generator, not the world simulator.**

If the model is allowed to own world state, then a hallucinated sentence becomes a
fact and the story silently rots. So model output can only ever become world state
through one narrow, explicit, validated path:

```
LLM output
  -> <charaly:action .../>     (an explicit machine-readable tag, NOT prose)
  -> ProposedActionParser
  -> ActionValidator
  -> WorldEvent                 (typed, not a string)
  -> EventEngine                (validates, then applies)
  -> WorldState
```

If the model writes "Alice walks into the library" as ordinary text, that stays
text. `StoryPipelineIntegrationTest.plain narration never changes the world`
exists precisely to hold that line.

The same rule applies to *authored* content. A Story Pack declares events as
structured effects (`MoveCharacter`, `GrantKnowledge`, `AdvanceThread`, ...), and
`EventProgram` compiles them into typed payloads. There is no path by which a pack
author - or a prompt - can smuggle an arbitrary state change past the engine.

---

## Layer by layer

| Layer | Class | Responsibility |
|---|---|---|
| Static definition | `StoryPack` | Immutable authoring data: characters, places, events, lore, openings, roles |
| Running story | `StoryInstance` | One playthrough: world state + knowledge + memories + queue + transcript + chapters + model binding |
| World truth | `WorldState` | Clock, locations, variables, character runtimes, relationships, threads, scenes |
| Story time | `WorldClock` / `StoryTime` | A *logical* clock. Time moves only when the runtime says so |
| Authoritative change | `WorldEvent` | Typed payloads: `CharacterMoved`, `KnowledgeDiscovered`, ... |
| State transitions | `EventEngine` | Pure reducer. Validates first, then applies. Replayable |
| Who knows what | `KnowledgeStore` | World truth **separate** from per-character knowledge |
| Who is where | `Routine` / `PresenceEngine` | An NPC's day, resolved into relocations when story time moves |
| Persistent memory | `MemoryStore` / `MemoryConsolidator` | Layered memories with visibility, scored deterministically, merged and de-contradicted |
| Current moment | `Scene` / `SceneDirector` | Who is present, where, and which threads matter. Not prose |
| The world as a place | `WorldPresenter` | Places and people, straight from authoritative state |
| Prompt selection | `ContextBuilder` | The **only** class that turns world state into text |
| Narrative | `InferenceEngine` | Replaceable port: load / generate / stream / stop |
| Model configuration | `ModelProfile` / `ModelBinding` | How to talk to one GGUF file, resolved once per story |
| Model registry | `ModelRegistry` | One list of models on this device, imported or downloaded |
| Authored events | `PackEventDefinition` / `EventProgram` | Triggers, conditions and structured effects, compiled to payloads |
| Storage | `CharalyRepository` | JSON documents in app-private storage |
| Presentation | `dev.charaly.runtime.presentation.*` | Pure read models every screen renders. No Android types |

### Ownership

```
StoryInstance          owns world state, knowledge, memories, queue, transcript
  └ WorldState         owns clock, locations, variables, character runtimes,
                       relationships, threads, scenes
  ├ KnowledgeStore     owns world truth + per-character knowledge
  ├ MemoryStore        owns persistent memories
  ├ EventQueue         owns pending events
  └ Conversation       owns the transcript (NOT world truth)

The UI owns NONE of this. It renders projections the runtime hands back.
```

### The UI never mutates the world

```
UI  ->  use case (CharalyRuntime)  ->  domain/runtime  ->  persistence
```

Screens are pure functions of a snapshot: `HomePresenter`, `LibraryPresenter`,
`PackDetailPresenter`, `StoryPresenter`, `MemoryPanelPresenter`, `WorldPresenter`,
`HeroPresenter`, `ModelLibraryPresenter`, `SessionsPresenter` and `NewStoryPresenter` all
live in the **runtime** module, take real domain objects and return read models. That is why the
entire UI can be tested on a plain JVM, and why a button cannot corrupt a world.

---

## What ships in the box

### Three demo worlds

They are ordinary `StoryPack` objects loaded through the ordinary
`CharalyRuntime.createPack` path - no privileged demo code, no bundled JSON, no
network fetch.

| Pack | Characters | Of those, NPCs | Locations | Events | Openings |
|---|---|---|---|---|---|
| Miraculous: Shadows of Paris | 19 | 12 | 21 | 9 | 3 |
| Neon District: Afterlight | 5 | 0 | 6 | 9 | 3 |
| The Last Kingdom | 5 | 0 | 6 | 9 | 3 |

The Miraculous pack is the one that has to demonstrate "a world, not a chat": the seven
core characters, plus a school principal, two teachers, a receptionist, a caretaker, two
classmates, a café owner, a bakery assistant, a museum guide and a beat officer - each
with a routine, a personality, knowledge boundaries and their own place to be found.
`MiraculousWorldPopulationTest` asserts the cast size, that every scheduled character has
a valid placement at every hour, and that walking into the ice-cream shop finds the
vendor there without anyone summoning him.

Isolation is structural, not conventional: each pack owns its own ids, world state,
relationships, knowledge, memories, events, threads and sessions. A `StoryInstance`
only ever resolves ids against its own pack, so a Neon District character cannot
exist in a Miraculous story even by accident. `PackInventoryTest` and
`DemoPackBehaviourTest` assert exactly that.

The Miraculous pack is fan-made demonstration content: original text, generated
artwork, and a notice on the pack detail screen.

### Artwork

Every visual is generated locally from a seed (`ui/art/PackArt.kt`): a hashed number
decides the palette blend, the horizon, the skyline or the light source. There are no
remote images, no bundled copyrighted assets, and no grey placeholder boxes - a pack
without artwork still gets a designed, deterministic composition.

### The model library

One registry. An imported GGUF and a catalog model are both `InstalledModel` entries,
because two parallel systems ("imported" versus "downloaded") is exactly what makes a
model library feel broken.

Model *profiles* (sampler, context policy, narrative style, thinking behaviour) are
deliberately not story data: a profile knows how one GGUF file likes to be prompted
and nothing about any world. A Story Pack points at a default profile; the resolved
values are copied into the `StoryInstance`, so changing a global default later cannot
retroactively change an existing story.

This build declares no `INTERNET` permission, so downloads are honestly unavailable:
the library offers `Import GGUF` and states precisely why. No fake progress bars exist
anywhere in the app, and `DownloadProgress.fraction` returns `null` rather than
inventing a percentage when the total size is genuinely unknown.

---

## Project layout

```
charaly-runtime/    pure Kotlin/JVM: all deterministic logic, the authoring model,
                    the presentation layer and the inference port. No Android
                    dependency, so the whole engine is testable on a plain JVM.
app/                Android application: Compose UI, llama.cpp JNI, model import.
scripts/            setup-llama.sh
```

---

## Building

```bash
# 1. Fetch llama.cpp (MIT). Charaly compiles it into its own .so.
scripts/setup-llama.sh

# 2. Verify the deterministic runtime (no device needed).
./gradlew verify

# 3. Build the APK.
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (`compileSdk 35`). If `llama.cpp` is missing,
`scripts/setup-llama.sh` tells you so and the app falls back to a clearly
labelled offline echo engine - never to a remote service.

### Building on an ARM64 host

The Android SDK and NDK ship **x86_64-only** `aapt2` and `clang++`, so an ARM64
machine cannot run them directly. That is a host limitation, not a Charaly one,
and is worked around with local shims (see the comments at the bottom of
`gradle.properties`). Nothing in those shims ships inside the APK: the real
tools still do the work.

```bash
# only cross-compile arm64 (a 32-bit shim cannot be used from ARM64)
./gradlew :app:assembleDebug -PcharalyAbis=arm64-v8a

# compile and link the native library without Gradle
scripts/verify-native-arm64.sh
```

---

## Using it

1. Launch Charaly. Three onboarding sentences, then Home.
2. **Models** -> **Import GGUF**. Pick a `.gguf` file with the system file picker.
   It is copied into app storage and registered; no broad storage permission is
   needed and no network is used.
3. **Story Packs** -> open a world -> **New Story** -> choose an opening, a role and
   a cast -> **Enter Story**.
4. Talk. Tokens stream in as they are produced; **Stop** aborts native generation
   immediately; Regenerate and Continue are one tap away.
5. The top-right menu opens the world state, memory, model, scene and story sheets.
   Advancing the story clock is deterministic and fires due events.
6. **Sessions** keeps every playthrough, with rename, branch and delete.

---

## Privacy

`AndroidManifest.xml` declares **no** `INTERNET` permission. Without it the OS
refuses to let the app open a socket, which makes "your conversations never leave
the device" a machine-checkable property rather than a promise. You can verify it
on a built APK:

```bash
$ aapt2 dump permissions app-debug.apk
package: dev.charaly.app
permission: dev.charaly.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
# no android.permission.INTERNET
```

No telemetry, no analytics, no cloud account, no API key. Stories and memories are
included in device backup; imported `.gguf` models are excluded.

---

## Testing

552 JVM tests, no device, no emulator, no model download.

```
Domain          RoutineTest                  LayeredMemoryTest
                CharacterRuntimeTest         RelationshipTest
                StoryInstanceTest            WorldStateTest
                WorldClockTest               StoryThreadTest
                KnowledgeTest                MemoryTest
Engine          EventEngineTest              MemoryLifecycleTest
                WorldSimulationTest
Content         StoryPackTest                PackInventoryTest
                PackSmokeTest                MiraculousWorldPopulationTest
                DemoPackBehaviourTest        LastKingdomPackSmokeTest
                NeonDistrictPackSmokeTest
Presentation    ContextBuilderTest           SceneDirectorTest
                StoryPipelineIntegrationTest LibraryPresentersTest
                WorldPresenterTest           MemoryPanelPresenterTest
                HeroPresenterTest            LayoutPolicyTest
                MotionPolicyTest              VisualResolverTest
                ModelLibraryPresenterTest
Persistence     PersistenceTest             SchemaMigrationTest
Models          ModelEngineCompatibilityTest
Compatibility   CompatibilityTest
App             CharalyApplicationTest       LocalLlamaInferenceEngineTest
                CharalyNavigatorTest         WorldNavigationTest
                RoutesAreRenderableTest      NestedScrollGuardTest
                DeveloperGateTest
                PresentationTest
```

The integration suite walks the whole pipeline end to end: pack -> instance ->
schedule -> advance clock -> process event -> verify world state -> build scene ->
build character-specific context -> mock engine -> streamed response -> persist ->
restart -> continue. `WorldSimulationTest` runs the product acceptance walkthrough: walk
into the ice-cream shop and find the vendor, advance time and watch the city move on,
tell one character a secret and prove another cannot know it, restart and find the world
unchanged.

`NestedScrollGuardTest` deserves a note. The "Story Packs" tab used to crash **on
tablets only**: a `LazyVerticalGrid` was nested inside a `LazyColumn` item, which Compose
measures with an infinite max height and refuses. Because it was width-dependent, it
looked like a tablet bug rather than a code bug. The guard now scans the Kotlin sources
for that exact shape, so the next person to add a nested scroller fails a test instead
of shipping a crash.

---

## References

Architecture ideas were informed by ChatterUI, CloverPal, HearthSocial,
LettuceAI, RPClient and SillyTavern. Charaly is not a clone of any of them. Its own
layer is the deterministic living-world runtime: world clock, event engine, explicit
knowledge, authored structured events, and a scene director that selects context
instead of guessing.