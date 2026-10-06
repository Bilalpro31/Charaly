# CHARALY

**An AI story world for Android.**

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

There is **no inference server**. Not `localhost:8080`, not FastAPI, not llama-server,
not a PC acting as a host, and no cloud API. Inference happens inside the app process, so
the app works in airplane mode after a model has been installed.

---

## What this release changed

The engine was always the interesting part. This release is the one where it reached the
product: the world clock now advances when a reader talks, character cards can actually be
imported, and a model can be measured instead of described.

| Area | Before | After |
|---|---|---|
| **World clock** | Advanced only from a developer panel | Advances on every completed turn, through `EventEngine.advanceTime` |
| **NPC routines** | Never fired during a playthrough | Fire, and relocate NPCs, while you are in the story |
| **Stage header** | "Evening" - true for hours | `18:42 · Paris · Evening · Day 3`, and it visibly moves |
| **Character cards** | A correct JSON parser that nothing called | `.json` and PNG import, previewed, committed explicitly |
| **Model speed** | A type that rejected zero tokens, with no way to produce one | A real runner, real timings, "Not measured" until there is one |
| **Memory retrieval** | Correct, undocumented | The filter-then-rank order is documented, and so is why there are no embeddings |

Two bugs were found while writing this pass's tests, both of which had been shipping:

- **`CharacterRoutineApplied` never recorded which routine entry placed a character.**
  `routineEntryMinute` was carried on the event payload and then dropped by the reducer, so it
  stayed at its `-1` default forever. `PresenceEngine`'s "a routine is a default, not a leash"
  guard compares the resolved entry against that field, so with it permanently unset the guard
  could never suppress a redundant relocation - and no screen could report where anybody had
  been placed from. Fixed in the reducer, where the event is applied.
- **`MockInferenceEngine.stop()` latched.** `respond` calls `stop()` in a `finally` after
  *every* turn, so a stop that latched regardless of whether anything was generating cancelled
  the **second turn of every conversation** - reporting a cancellation nobody asked for. The
  native engine guards this with its own `generating` flag; the mock did not, so the two
  engines disagreed and a multi-turn test could not pass. Fixed to match the real engine, and
  pinned by two tests: one that cancels mid-generation, one that asserts a completed turn does
  not poison the next.

### What the previous release changed

The release before that had a strong engine and an app that looked like a management console.
That one rebuilt the product around it.

| Area | Before | After |
|---|---|---|
| Background | `#0E0D12`, a navy | `#000000`, true black, with documented elevation steps |
| Brand colour | Purple, from a Material seed | Neutral. A pack's accent is the only colour |
| Pack detail | Events, locations, cast, lore, threads | A premise, three hooks, an invitation |
| Model library | Import a GGUF; a DOWNLOAD button wired to nothing | Real Hugging Face search, real download, real install |
| Chat header | Project title, chapter | Who you are with, where, at what hour |
| Speed labels | None | Only ever "Fast on this device" after a real measurement |
| Pack gradients | Authored, then dropped in projection | Authored *and* drawn, on the hero and the chat header |
| People sheet | One flat list of everyone | "Here now" and "Worth knowing about", with how they feel about you |

Six bugs were found while writing the tests for that release, each of which had been shipping:

- `GgufMetadata.estimateParameterCount` **overflowed the stack** on any header without a
  `block_count`, because its prefix-stripping helper recursed on its own lookup.
- `HuggingFaceClient` never parsed a Hub description: it matched the literal string
  `"summary:"` while the Hub's convention is the tag *name* `summary`. Every catalog card
  rendered a blank description and the fixture had been written to match the bug.
- `HuggingFaceFileListing.candidatesFor` sorted **ascending** by quantisation quality
  while its own comment said "best first", so a screen iterating it would have offered
  the repository's worst quantisation first.
- `AccentTokens.gradientOf` **returned `null` unconditionally**, so every pack declared a
  gradient that no screen could draw. The "best fit" file was chosen by a size heuristic
  that ignored the KV cache, so on any real device it would have recommended a file whose
  own row said "manual import only" - a screen contradicting itself one row apart. Both are
  now tested.
- `ResolvedTheme.BRAND` was **purple**, and it is the fallback for any character without an
  explicit accent, for session rows and for the world screen. That is how a product whose
  whole premise is "colour comes from the pack" ended up with violet surfaces on screens
  with no pack on show.
- Two **empty click handlers** shipped in a row: the model library's `DOWNLOAD` button and
  the story wizard's "Open models" link. The second is worse - it appeared at exactly the
  moment a user had just been told their story could not generate.

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
from another's prompt; it is unreachable by construction. `Memory.visibleTo(subject)` is
a closed, total function, and a memory a character may not see can never be returned by
retrieval regardless of how relevant it scores. Facts are not enough on their own, so a
character also has a *mind* - what they witnessed, concluded, cannot dismiss, and are
wrong about. Fifteen of the nineteen characters in the Miraculous pack start with one,
four of them holding something false.

### Retrieval, in order

`MemoryStore.retrieve` is three steps and the order is the guarantee:

```
   1. FILTER   visibleTo(subject)   <- a gate, not a ranking
   2. FILTER   location / characters / threads / tiers
   3. RANK     MemoryScore.of(...)  <- importance, recency, overlap
```

Visibility is applied **first**, before anything is scored. A memory the subject may not see is
not a candidate that scored low; it is not a candidate at all, so no later change to the
scoring function can promote one into a prompt. Ranking may only reorder what the gate already
admitted.

**No embeddings, on purpose.** Three properties depend on the absence, and only the third is
about convenience:

 - **Visibility stays provable.** An embedding index would have to filter before ranking for the
   guarantee to survive, which means one index *per subject* over only what that subject may
   see. A shared index would leak by construction - which is the failure this whole subsystem
   exists to make impossible.
 - **Retrieval stays deterministic.** The same story, replayed on the same build, prompts with
   the same memories in the same order. A numerical similarity that can shift with a library
   version or a quantisation would make a replayed story diverge for reasons nobody could
   audit - and replayability is what the event log is for.
 - **Prompt caching keeps working.** `ContextCache` fingerprints the memories a prompt's memory
   section is built from. A retrieval step whose *selection* can change would invalidate that
   cache on nearly every turn, and llama.cpp's prefix cache - which is what makes a long
   context affordable on a phone at all - would stop paying for itself.

If embeddings are ever added they belong behind `MemoryStore`, and they may change only the
*ordering* of memories that are already visible: never which ones are visible, never whether a
retrieval is reproducible, and never whether the cache fingerprint still describes the section
that was actually sent.

**The world changes only through validated events.** The model proposes; the engine
disposes. A routine relocation is a distinct payload from a movement for a real reason -
`CharacterMoved` is a traversal validated against the location graph, while a schedule
is authoritative world data - and both are logged and replayable.

**The world knows why.** An event log answers "in what order". It cannot answer "why",
which is the only question a story is made of - so every applied event may carry a typed
causal link, and `WorldState.causality` is a graph that can be walked in both directions.
The reason is a closed enum rather than prose, because "what did my choices actually do"
is not answerable on a graph of free text.

**Promises outlive being forgotten.** A promise, a goal and an armed consequence are
world state, not memories, because all three have to be *checkable*. A character who has
forgotten their promise still owes it; the story does not forget on their behalf.

### Where each guarantee is tested

| Guarantee | Test |
| --- | --- |
| NPCs move through their day | `RoutineTest`, `MiraculousWorldPopulationTest` |
| NPCs exist without interaction | `WorldSimulationTest` |
| Secrets do not leak | `LayeredMemoryTest`, `WorldSimulationTest`, `StoryEndToEndTest` |
| Prose never becomes world state | `StoryEndToEndTest`, `WorldSimulationTest` |
| A pack's events stay in its pack | `PackEventIsolationTest`, `StoryIsolationTest` |
| A story survives a restart intact | `StoryEndToEndTest` |
| A partial download is never installed | `DownloadStateMachineTest` |
| No speed claim without a measurement | `DeviceRecommendationTest` |
| No scraped art ships | `VisualProvenanceTest` |
| Old saves still open | `SchemaMigrationTest` |
| The library tells the truth about models | `ModelEngineCompatibilityTest` |

---

### The UI shows a story, not a database

A Story Pack contains a `CanonBible`, world rules, characters, locations, event programs,
NPC schedules, secrets, relationships, story threads and visual assets. The pack detail
screen shows **none of that**:

```
   cover artwork
   title
   universe / era
   premise          <- the one substantial paragraph
   hooks            <- 2-3 atmospheric lines
   tone, atmosphere
   [ Enter Story ]
```

`PackShowcase` is a separate type with no field for a character, location or event, so
the detail screen cannot render a pack's contents even by accident. The data is not lost -
it is discovered: a location name appears in the chat header when you walk in, a
character appears when the story needs one, a thread surfaces hours later.

The same rule governs the four contextual sheets available mid-story. They are organised
by question, not by engine subsystem:

```
   WORLD       where am I, and when is it?
   MEMORY      what does this person remember about me?
   PEOPLE      who is here, and how do they feel about me?
   STORY       what am I supposed to be doing?
```

Nothing in those four projections contains an id, a score, a tier name or an event type.
`ProductPresentationTest` asserts it by searching the strings, because a compiler cannot
check a claim about what a user reads.

### Model compatibility is derived, never asserted

A Hugging Face or Google model identifier is a *publication* fact. Whether a GGUF can be
loaded by the llama.cpp compiled into the APK is an *engine* fact, and the two differ. So
`EngineCapabilities.ARCHITECTURES` mirrors the vendored engine's architecture table, and
every catalog entry's verdict is computed from it. A test reads `llama-arch.cpp` directly,
so bumping llama.cpp without updating the list fails the build rather than silently
mislabelling the library.

This is why **Gemma 4 E2B and E4B are in the catalog but marked "Coming"**. They are real
Google models and their official ids are recorded - but the bundled engine is pinned at commit
`5143fa895` (2025-09-05), and that commit's `src/llama-arch.cpp` registers `gemma`, `gemma2`,
`gemma3`, `gemma3n` and `gemma-embedding`, and **no `gemma4`**. Offering a download for a file
this build cannot load is worse than not offering one, so the library says exactly that
instead.

Upstream llama.cpp has since added `gemma4`, so this is a *stale* refusal rather than a
permanent one - and a stale refusal that is honest beats an optimistic label that fails at
load time. `ModelEngineCompatibilityTest` reads `llama-arch.cpp` directly, so it fails the
build the moment the vendored engine and `EngineCapabilities.ARCHITECTURES` disagree; the next
engine bump therefore turns these entries live on its own, with no edit to the catalog. That
upgrade is deliberately **not** in this release: it is a separate change with its own risk, and
its own task.

### Downloads are real, and they are honest

`INTERNET` is declared for exactly one feature: fetching a GGUF when the user asks for
one. The pipeline is real rather than a progress bar with a timer behind it - every number
it emits is derived from bytes actually written to disk:

```
   discovered -> queued -> downloading <-> paused
                                  |
                                  v
                              verifying     sha256 over the real file, if published
                                  |
                                  v
                              installing    GGUF header parse + compatibility + move
                                  |
                                  v
                                 ready       registered, profile attached

   any state -> failed | cancelled | unsupported
```

Three invariants make it trustworthy:

1. **Nothing partial is ever presented as installed.** Bytes land in `<name>.part`. The
   registry entry is written only after verification *and* an atomic rename.
2. **A file that fails verification is deleted, not quarantined.** A corrupt GGUF cannot
   become loadable later, and leaving it consumes the storage the retry needs.
3. **A paused download resumes from the byte it reached**, because the offset is read
   back from the file on disk rather than remembered in memory. A process death costs
   nothing.

A model's compatibility is decided **before** the download, from a ranged read of the
first 512 KiB - the GGUF metadata block. That turns a four-gigabyte guess into a few
hundred kilobytes of fact. And a model the engine cannot load is never fetched at all.

### The Model Hub is the library

Discovery is a real feature, not a bundled list filtered locally. Typing in the library
searches the Hub's GGUF index over HTTPS; tapping a result lists that repository's actual
files with the Hub's own sizes and LFS hashes; and a file's compatibility verdict is
computed from its own header before anything is committed to.

The join between discovery and installation is `ModelHubPresenter.toCatalogItem`: a
repository is not a `ModelCatalogItem`, and that function is what turns one into the other
with the URL, size, checksum and observed architecture attached. Because the architecture
comes from the *header*, the pipeline's own pre-flight refusal is computed from the same
fact the screen displayed - a repository named `gemma-4` whose file declares `gemma3` runs,
and one that declares `gemma4` says "needs a newer engine" before four gigabytes are spent.

The screen order follows what the user actually has and wants:

```
  title + connectivity
  the active model
  live download progress          only while one is in flight
  Hub search + filters            the real, queried source
  Hub results
  the open repository's files     with per-file compatibility
  models on this device
  Charaly knows about             reference entries, no download offered
  import a GGUF you have
```

The last two sections deliberately carry **no** Download control. They are descriptions of
models, with no file behind them and no size that was measured, so offering one would be the
same defect as a button wired to nothing. Two empty-click handlers were found and removed
while building this, one of them in the story wizard's "Open models" link.

A finished download registers the model, refreshes the registry and activates it in one
step. A model that installs successfully but then needs a second tap is a broken install
from the user's point of view, since the flow reads DOWNLOAD → INSTALLING → READY → USE.

### Speed labels come from measurements, or not at all

The brief asks for "Fast", "Balanced" and "Quality", and forbids inventing them. So
`ModelRecommendation` has no way to say a speed word without a `ModelBenchmark` in hand:

```
   measured  -> "Fast on this device"  + the tok/s it was measured at
   RAM only  -> "Fits your device"
   nothing   -> no claim at all
```

There is no `estimatedTokensPerSecond` field anywhere in the codebase, because an
estimate would be indistinguishable from a measurement once stored, and the whole value of
the label is that it is not one.

### Visuals can never fail to render

"No broken image, no empty remote URL, no failed-to-load box" is only enforceable if
resolution happens *before* the view is involved. `VisualResolver` turns any visual
entity into a `ResolvedVisual` that is always drawable: a bundled licensed asset, then the
user's own image, then a generated composition from a stable seed. A screen consumes that
and has no fallback branch of its own to get wrong.

`AssetSource` is the honest half. `BUNDLED_LICENSED` and `GENERATED_ORIGINAL` may ship in
the repository; `USER_PROVIDED` and `REMOTE_OPTIONAL` may not, and the type says so. An
optional remote asset is never *selected* - a real picture always beats a promise the app
cannot keep. `VisualProvenanceTest` walks every asset in every shipped pack and asserts
none is remote and no bundled reference looks like a URL.

Artwork is presentation metadata and is structurally incapable of being world state.

---

## Why the architecture looks like this

The single most important rule in this codebase:

> **The LLM is a language generator, not the world simulator.**

If the model is allowed to own world state, then a hallucinated sentence becomes a fact
and the story silently rots. So model output can only ever become world state through one
narrow, explicit, validated path:

```
LLM output
  -> <charaly:action .../>     (an explicit machine-readable tag, NOT prose)
  -> ProposedActionParser
  -> ActionValidator
  -> WorldEvent                 (typed, not a string)
  -> EventEngine                (validates, then applies)
  -> WorldState
```

If the model writes "Alice walks into the library" as ordinary text, that stays text.
`StoryEndToEndTest.plain narration never changes the world` exists precisely to hold
that line.

The same rule applies to *authored* content. A Story Pack declares events as structured
effects (`MoveCharacter`, `GrantKnowledge`, `AdvanceThread`, ...), and `EventProgram`
compiles them into typed payloads. There is no path by which a pack author - or a prompt -
can smuggle an arbitrary state change past the engine.

---

## Layer by layer

| Layer | Class | Responsibility |
|---|---|---|
| Static definition | `StoryPack` | Immutable authoring data: characters, places, events, lore, openings, roles |
| What is true regardless | `CanonBible` | The setting's assertions. Never edited by playing |
| Running story | `StoryInstance` | One playthrough: world state + knowledge + memories + queue + transcript |
| World truth | `WorldState` | Clock, locations, variables, character runtimes, relationships, threads, scenes |
| Authoritative change | `WorldEvent` | Typed payloads: `CharacterMoved`, `KnowledgeDiscovered`, ... |
| State transitions | `EventEngine` | Pure reducer. Validates first, then applies. Replayable |
| Who knows what | `KnowledgeStore` | World truth **separate** from per-character knowledge |
| Who is where | `Routine` / `PresenceEngine` | An NPC's day, resolved into relocations when story time moves |
| Persistent memory | `MemoryStore` / `MemoryConsolidator` | Layered memories, filtered by visibility then scored deterministically |
| Writing memory | `MemoryWriter` | The gated pipeline: candidates, importance, visibility, dedup, canon |
| What a character knows | `KnowledgeStore` / `CharacterMind` | Observation, belief, suspicion, error |
| Commitments | `CommitmentLedger` | Promises, goals and armed consequences, kept out of memory |
| Why things happened | `CausalLink` / `WorldState.causality` | A typed cause graph, walkable in both directions |
| Consistency | `StoryHealthAnalyzer` | Sixteen read-only checks. Never shown outside developer mode |
| Current moment | `Scene` / `SceneDirector` | Who is present, where, which threads are live. Not prose |
| Prompt selection | `ContextBuilder` | The **only** class that turns world state into text |
| Prompt caching | `ContextCache` / `ContextEpoch` | Fingerprint-keyed reuse of the static half of a prompt |
| Narrative | `InferenceEngine` | Replaceable port: load / generate / stream / stop |
| Model configuration | `CharalyRuntimeProfile` | Derived from a GGUF's own header. Never hand-written per model |
| Model registry | `ModelRegistry` | One list of models on this device, imported or downloaded |
| Model discovery | `HuggingFaceClient` | GGUF-filtered search, real file sizes, ranged header reads |
| Model download | `HuggingFaceModelDownloads` | The real transfer, verify and install pipeline |
| Device fit | `DeviceCapability` / `ModelRecommender` | Recommendations, and the measurement they must cite |
| Authored events | `PackEventDefinition` / `EventProgram` | Triggers, conditions and structured effects, compiled to payloads |
| Storage | `CharalyRepository` | JSON documents in app-private storage |
| Presentation | `dev.charaly.runtime.presentation.*` | Pure read models every screen renders. No Android types |
| Design system | `CharalySurface` / `CharalyAccent` / `PackTheme` | True black, neutral default, per-pack identity |

### Ownership

```
StoryInstance          owns world state, knowledge, memories, queue, transcript
  └ WorldState         owns clock, locations, variables, character runtimes,
                       relationships, threads, scenes, commitments, causality
  ├ KnowledgeStore     owns world truth + per-character knowledge + minds
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
`PackShowcaseBuilder`, `StoryPresenter`, `StoryContextPresenter`, `StoryFeed`,
`MemoryPanelPresenter`, `WorldPresenter`, `ModelLibraryPresenter` and the rest all live
in the **runtime** module, take real domain objects and return read models. That is why
the entire UI can be tested on a plain JVM, and why a button cannot corrupt a world.

---

## What ships in the box

### Three demo worlds

They are ordinary `StoryPack` objects loaded through the ordinary `CharalyRuntime.createPack`
path - no privileged demo code, no bundled JSON, no network fetch.

| Pack | Characters | Of those, NPCs | Locations | Events | Openings | Identity |
|---|---|---|---|---|---|---|
| Miraculous: Shadows of Paris | 19 | 12 | 26 | 9 | 3 | red / black / white |
| Neon District: Afterlight | 5 | 0 | 6 | 9 | 3 | cyan / magenta |
| The Last Kingdom | 5 | 0 | 6 | 9 | 3 | gold / deep blue |

The Miraculous pack is the one that has to demonstrate "a world, not a chat": the seven
core characters, plus a school principal, two teachers, a receptionist, a caretaker, two
classmates, a café owner, a bakery assistant, a museum guide and a beat officer - each
with a routine, a personality, knowledge boundaries and their own place to be found.
`MiraculousWorldPopulationTest` asserts the cast size, that every scheduled character has
a valid placement at every hour, and that walking into the ice-cream shop finds the
vendor there without anyone summoning him.

Fifteen of the nineteen characters start with a written **mind** - what they witnessed,
what they concluded, what they suspect, and in four cases what they have simply got
wrong, with the truth recorded alongside. Marinette is certain the masked hero who fights
beside her is "a friend from another school". She is not, and the story's central reveal
is the engine retiring that belief rather than the model happening to notice.

Isolation is structural, not conventional: each pack owns its own ids, world state,
relationships, knowledge, memories, events, threads and sessions. `PackEventIsolationTest`
walks every authored effect of every event in every pack and resolves each referenced id
against that same pack's content; `StoryIsolationTest` plays one pack and then checks a
second pack's story for every trace of it.

The Miraculous pack is fan-made demonstration content: original text, generated artwork,
and a notice on the pack detail screen.

### Artwork

Every visual is generated locally from a seed (`ui/art/PackArt.kt`): a hashed number
decides the palette blend, the horizon, the skyline or the light source. There are no
remote images, no bundled copyrighted assets, and no grey placeholder boxes - a pack
without artwork still gets a designed, deterministic composition.

---

## Project layout

```
charaly-runtime/    pure Kotlin/JVM: all deterministic logic, the authoring model,
                    the presentation layer and the inference port. No Android
                    dependency, so the whole engine is testable on a plain JVM.
app/                Android application: Compose UI, llama.cpp JNI, HTTPS transport.
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
`scripts/setup-llama.sh` tells you so and the app falls back to a clearly labelled
offline echo engine - never to a remote service.

### Building on an ARM64 host

The Android SDK and NDK ship **x86_64-only** `aapt2` and `clang++`, so an ARM64 machine
cannot run them directly. That is a host limitation, not a Charaly one, and is worked
around with local shims (see the comments at the bottom of `gradle.properties`). Nothing
in those shims ships inside the APK: the real tools still do the work.

```bash
# only cross-compile arm64 (a 32-bit shim cannot be used from ARM64)
./gradlew :app:assembleDebug -PcharalyAbis=arm64-v8a

# compile and link the native library without Gradle
scripts/verify-native-arm64.sh
```

---

## Using it

1. Launch Charaly. Three onboarding sentences, then Home.
2. **Models** -> search the Hugging Face Hub or **Import GGUF**. A download is verified
   before it is installed, and Charaly configures it automatically - there is no setup
   screen to fill in.
3. Tap **Measure** on a model to find out how fast it runs *on this phone*. Until you do,
   the library says "Not measured" rather than guessing.
4. **Story Packs** -> open a world -> **Enter Story** -> choose an opening, a role and
   a cast. **Import Character** adds somebody of your own from a SillyTavern card, and you
   will see the whole card before anything is saved.
5. Talk. Tokens stream in as they are produced; **Stop** aborts native generation
   immediately; Regenerate and Continue are one tap away.
6. Watch the clock in the header. It moves a couple of minutes per turn, and when it crosses
   somebody's hour they are somewhere different - whether or not you have ever spoken to them.
7. The header's four entries open World, Memory, People and Story. The World one carries the
   time and what is happening right now.
8. **Sessions** keeps every playthrough.

---

## Privacy

`AndroidManifest.xml` declares `INTERNET`, and it is used for one thing: fetching a model
file the user explicitly asked for, over HTTPS, with the sha256 the Hub publishes checked
before install.

Everything that constitutes a *story* never touches the network. Conversations, memories,
character knowledge, world state, the event log, world simulation, event evaluation and
model inference are all local, and the wiring reflects that: `HuggingFaceServices` is the
only object in the app that holds a transport, and nothing in the story engine references
it. With no connection at all, the app is fully functional.

`ACCESS_NETWORK_STATE` exists for the offline indicator, which is shown on the Model Library
and nowhere else — because that is the only screen with a network feature, and a global
status bar reading "offline" would be a lie about the eight screens that work without one.
Going offline never disables the library: installed models and every existing story are
unaffected, and only browsing and downloading are lost.

No telemetry, no analytics, no cloud account, no API key. `usesCleartextTraffic` is false
and the transport rejects non-HTTPS URLs at runtime, so a model's weights cannot be
swapped in transit by anyone on the network.

---

## The clock is the world's, and it moves

A story is not a chat log with timestamps. It is a place that keeps happening whether or not
anyone is talking to it, and the difference is entirely in whether time advances on its own.

**Before this release, it did not.** `CharalyRuntime.advance` existed, `advanceClock` existed
on the view model, `Routine` and `PresenceEngine` and the conditional-event machinery all
existed and were all tested - and nothing in the product ever called them, because the clock
only moved when a developer opened the panel. So a reader could talk to a character for an
hour of real time and the ice-cream vendor would never have closed the shop.

Now a completed turn advances the clock, through `EventEngine.advanceTime`:

```
user input
  -> SceneDirector         what scene are we in?                (no LLM)
  -> ContextBuilder        what may this character know?         (no LLM)
  -> InferenceEngine       streamed, cancellable tokens
  -> ProposedActionParser  explicit tags only, never prose
  -> ActionValidator
  -> EventEngine           authoritative world change
  -> EventEngine.advanceTime   the clock, routines, scheduled events   <-- new
  -> ChapterPlanner        chapters, derived from what actually happened
  -> repository            persist
```

`EventEngine.advanceTime` is the same entry point a debug "advance 30 minutes" uses, which is
the whole point: routines fire, scheduled events come due, NPCs are relocated by validated
events, and consequences in the ledger mature. The clock does not merely *read* later.

### The model does not own the clock

A turn's cost is computed by `TurnClockPolicy` from engine-side facts - how many characters
the player typed, and how many validated actions actually committed:

| Situation | Cost |
|---|---|
| A short exchange | 2 minutes |
| A long player line | 3 minutes |
| Each validated action | +2 minutes, counted up to four |
| Anything | capped at 15 minutes |

The model's reply is **not** an input. Its length, its content and its claims about elapsed
time are all model output, and a turn whose prose says "an hour passes" still costs two
minutes. If the model could write the clock, then every guarantee this codebase makes about
world state would be one hallucinated sentence away from being false.

The ceiling matters as much as the base: an unbounded clock would let one verbose turn skip
past every routine boundary in a pack at once, which is the "the world jumped" feeling the
policy exists to prevent.

`RuntimeClockProgressionTest` walks this through the real `respond()` path - six player turns,
no `advance()` call anywhere in the test - and asserts that the clock moved, that the amount
it moved equals the amount the runtime said it would charge, that an NPC ended up standing
where his own routine says, and that a scheduled consequence fired. The other time tests in
the suite all call `advance` directly; that is why they all passed while the product was
broken.

### Where time is visible

The stage header carries the exact reading - `18:42 · Paris · Evening · Day 3` - because it is
the one header fact that changes while a story is being read, and a header that only showed
"Evening" could not demonstrate that the world is running. The World sheet carries the same
clock plus the live scene, read from the current `Scene` rather than from the pack's static
description.

A Developer Mode panel can step the clock forward, and it goes through the same `advance` a
real turn takes - it is a debug control for reaching a routine boundary without holding a
conversation long enough to get there, not a way to set a field.

---

## Importing a character card

SillyTavern character cards - a `.json` file, or a `.png` with the card as base64 JSON in a
text chunk - are read, previewed, and only then written:

```
bytes -> decode -> validate -> preview -> user confirms -> stored
```

Nothing is written before the confirmation, so a cancelled import leaves the library
byte-for-byte as it was. The preview is a full screen rather than a dialog because the decision
is not small: a card carries a description, a personality, a scenario, a greeting, example
dialogue and lore entries, and a user consenting to all of that while shown only a name is
consent without disclosure. Fields the card did not carry are marked as missing rather than
hidden, because a blank space and an absent value look identical.

The card is stored as a **library entry**, not as a world. Turning an import into a
`StoryPack` on the spot would be the simplest thing to write and the wrong one: the user
asked for a character, not for a world containing that character in a place that does not
exist.

Adding one to a story goes through `CharalyRuntime.startStoryWithImported`, which merges the
cast into a **copy** of the pack under its own pack id. The shipped pack is never edited, so a
second playthrough of the same world starts clean, and `PackEventIsolationTest` and
`StoryIsolationTest` keep passing. An imported character is also given a place to be found -
a room attached to the opening location, named after them - because a character the engine
cannot place is a character the player can never meet.

A card can never carry world state. `CharacterCardPreview` has no field for a location, a
routine, a relationship, a thread or a clock, so there is nothing for one to become.

---

## Measuring a model, honestly

`ModelBenchmark` refuses to exist without a measurement: its `init` block rejects a record with
zero tokens, and there is no `estimatedTokensPerSecond` field anywhere in this codebase.

```
  MEASURED   "18.7 tok/s, measured on 4 threads · CPU" + latency + peak memory
  UNMEASURED "Not measured"                              + a Measure control
  RUNNING    the loader's own percentage, then the real decode
```

The timings come from `std::chrono::steady_clock` inside `charaly_jni.cpp`, around the same
tokenize / chunked-eval / greedy-decode path a story turn uses, and a run that produced no
tokens returns nothing at all. Peak memory is `VmHWM` from `/proc/self/status` - the kernel's
own high-water mark for this process, which covers the native allocations a GGUF mmap makes
and cannot miss a transient spike the way a sampled reading would.

A benchmark cannot touch a story: the runner receives a model and a store, and has no
parameter or field through which a `StoryInstance` could reach it. There is no world state,
no memory, no event log, no transcript and no story context. The resident model is restored
afterwards, so measuring never costs a story its model.

The backend a figure was measured under is shown next to it, and it comes from ggml's own
device registry rather than from a constant - which is what makes it impossible for this
build to display "GPU accelerated" while compiling CPU backends only.

### CPU today, and what the baseline is for

This build compiles **CPU only**, and that is now measurable rather than merely asserted:

```
   n_gpu_layers    wired from EngineConfig through JNI, and always 0
   n_threads       0 - llama.cpp chooses. It used to be hardcoded to 4, which was a
                   guess presented as a default; with no way to measure, there was no
                   basis for any particular number
   backends        read from ggml's device registry at runtime
   recorded with   thread count, offload count, context size, device list
   each benchmark
```

Two things follow. A CPU-only build cannot claim an accelerator, because the UI only renders
names that the device registry actually returned. And a future Vulkan or OpenCL build can be
compared against the CPU baseline this pass produces without changing anything but
`EngineConfig.gpuLayers` - same prompt, same sampler, same recorded conditions.

Accelerators are deliberately **not** enabled here. The honest order is: measure the CPU
baseline first, then decide, rather than shipping a backend on the strength of someone else's
benchmark. `GGML_BACKEND_DL=ON` is worth noting for whoever does it - without it a backend can
fail to `dlopen` silently and the APK appears to have a GPU path it does not have.

---

## Testing

1263 JVM tests across 78 classes, no device, no emulator, no model download.

```
Design        PackIdentityDesignTest
Engine        EventEngineTest              MemoryLifecycleTest
              WorldSimulationTest          StoryHealthAnalyzerTest
Context       ContextBuilderTest           ContextBudgetInspectorTest
              CommitmentContextTest        SmartContextCacheTest
Content       StoryPackTest                PackInventoryTest
              PackSmokeTest                MiraculousWorldPopulationTest
              DemoPackBehaviourTest        LastKingdomPackSmokeTest
              NeonDistrictPackSmokeTest
Models        ModelEngineCompatibilityTest ModelLibraryPresenterTest
              DeviceRecommendationTest     DownloadStateMachineTest
              CharalyRuntimeProfileTest
Network       HuggingFaceClientTest
Model Hub     ModelHubPresenterTest
Media         VisualResolverTest           VisualProvenanceTest
              DemoPackVisualIdentityTest
Presentation  ContextBuilderTest           SceneDirectorContextTest
              SceneTempoTest               SceneDirectorTest
              StoryPipelineIntegrationTest ProductPresentationTest
              HeroPresenterTest            LayoutPolicyTest
              MotionPolicyTest             VisualResolverTest
              WorldPresenterTest            MemoryPanelPresenterTest
              PeopleSurfaceTest
Isolation     StoryIsolationTest           PackEventIsolationTest
Session       MemoryLifecycleTest          MemoryWritePipelineTest
Clock         RuntimeClockProgressionTest
Characters    CharacterImportFlowTest
End to end    StoryEndToEndTest
Persistence   PersistenceTest              SchemaMigrationTest
Benchmark     ModelBenchmarkRunnerTest     BenchmarkSpeedVerdictTest
              BenchmarkPersistenceTest
App           CharalyApplicationTest       LocalLlamaInferenceEngineTest
              CharalyNavigatorTest         WorldNavigationTest
              RoutesAreRenderableTest      NestedScrollGuardTest
              DeveloperGateTest            PresentationTest
              NavigationContractTest       ModelHubWiringTest
              WorldFlowWiringTest
```

### `RuntimeClockProgressionTest`, and why it does not call `advance`

Every other time test in this suite calls `runtime.advance(...)`. That proved the event engine
moves the world when asked - and the product was still a static world, because nothing in the
product ever asked. `advanceClock` existed on the view model for the whole life of the app and
no screen called it, so the pack's routines could never fire during a real playthrough.

So this suite sends player lines through `CharalyRuntime.respond()` - the exact call the
composer makes - and asserts the world changed as a consequence: the clock advanced, by
exactly the amount the runtime said it would charge; an NPC ended up standing where his own
routine says; a scheduled consequence fired; and every one of those changes is in the event
log rather than written into state. A separate test proves a reply *claiming* an hour passed
moves the clock by two minutes.

### `CharacterImportFlowTest` reads real PNGs

`CompatibilityTest` covered the JSON parser, and that parser was correct the whole time while
being entirely unreachable - no PNG was ever read and nothing called it. A feature can be
fully implemented and completely absent from the product.

So this suite walks the flow a person walks: pick a file, read it, look at it, confirm, start a
story. The PNG fixtures are written byte for byte - real signature, real IHDR, real `tEXt`
chunk, real CRC - by a shared probe in the runtime module, because a fixture that mocked the
chunk structure would test the parser against the test's own idea of it. That is exactly the
bug class that produced blank card descriptions once already, and the first version of this
suite reproduced it: the reader truncated the payload at the first newline, which every real
card contains because exporters MIME-wrap their base64 at 76 characters.

### `WorldFlowWiringTest` asserts the wiring Compose cannot

Compose cannot be rendered or clicked from a JVM test, so the claims that only a composable can
make are checked by reading the app's sources. That is weaker than a rendered check and the
test says so. Everything that can be exercised for real is: a real runtime, a real
`CharalyRuntime.respond`, a real benchmark runner with a failing engine, and a real character
import round-trip.

The source checks are here because the specific defect this codebase has shipped twice is a
control that looks finished and does nothing.

### What `ModelHubPresenterTest` asserts, and why with real bytes

The library's central claim is "this file will run on your device", and it derives that from
a file's own GGUF header. A test that fabricated a metadata object would be testing the
classifier against itself - it would pass while the real reader was broken, which is exactly
the bug class that produced a catalog of blank descriptions and an ascending "best first"
sort. So the fixtures write genuine GGUF v3 headers, byte for byte, and let `GgufReader`
parse them.

`DownloadStateMachineTest` goes further and runs the real pipeline against a transport
serving real bytes from memory: real byte counts, real resume offsets, a real sha256 over a
real file. Nothing is simulated except the socket.

`ModelHubWiringTest` reads the app's own sources, because Compose cannot be rendered or
clicked from a JVM test. Its most important assertion is that **no control anywhere in the UI
layer has an empty click handler** - the defect that shipped twice here.

`StoryEndToEndTest` walks the whole product loop - start pack, enter story, scene, model,
dialogue, proposed action, validator, event, world state, memory, relationship, thread,
save, restart, continue - with a scripted inference engine and no model file. It is the
only test that proves a story survives a process restart *with its consequences intact*.

### Tests that exist because a bug got through

A test suite is mostly evidence that the obvious things work. These are evidence about
this codebase specifically, and each one is here because the thing it guards was *wrong at
some point*.

**`StoryEndToEndTest.the prompt must not contain the raw protocol`.** The runtime stored
the model's reply verbatim, so a model that proposed an action left
`<charaly:action type="move" .../>` sitting in the middle of a character's dialogue. The
proposal is machinery; the player reads the words around it.

**`DownloadStateMachineTest.a completed download is verified and registered`.** The happy
path could not be tested without a genuinely valid GGUF header, because the installer
reads the file's own header and refuses what it cannot classify. Writing the fixture
big-endian instead of little-endian produced "version 50331648" and then "a billion
metadata entries" - failures three layers from the cause.

**`PackEventIsolationTest.no event references an entity from another pack`.** Two earlier
versions scanned a payload's `toString()` for kebab-case tokens, and both reported valid
content as a cross-pack leak: an effect's `note` of "the third-floor seal is up for
renewal" contains `third-floor`, which is indistinguishable from a location id by shape.
Reading the sealed hierarchy removes the ambiguity - an id is a field whose type is an id,
and prose is a `String`.

**`StoryIsolationTest` compares `firedEvents`, not `eventLog`.** Event ids are `evt-N`
from a per-story counter, so two independent stories both legitimately contain `evt-1`.
An earlier draft compared the two logs and would have passed even if the worlds were
deeply entangled. `firedEvents` is keyed by the *pack* event id, which is a real
cross-story identity.

**`MiraculousWorldPopulationTest.every npc has their own home, not a shared one`.**
Every NPC routine pointed "18:00 - at home" at Andre's flat. The world looked correct all
day and then, at six in the evening, seven strangers were standing in a two-room flat.
Nothing crashed.

**`MemoryWriterTest.greetings and weather are small talk`.** The importance gate consulted
the tier floor, so "hi" scored 2 and was stored. Every greeting in every conversation
became a permanent retrieval slot.

**`CharacterMindTest.the prompt tells the model not to correct its own beliefs`.** The
misconception machinery worked perfectly and produced nothing, because a model given
"believes (wrongly) that the new student is nobody" helpfully corrects itself mid-answer.

`NestedScrollGuardTest` deserves a note too. The "Story Packs" tab used to crash **on
tablets only**: a `LazyVerticalGrid` nested inside a `LazyColumn` item, which Compose
measures with an infinite max height and refuses. The guard now scans the Kotlin sources
for that exact shape.

---

## References

Architecture ideas were informed by ChatterUI, CloverPal, HearthSocial, LettuceAI,
RPClient and SillyTavern. Charaly is not a clone of any of them. Its own layer is the
deterministic living-world runtime: world clock, event engine, explicit knowledge,
authored structured events, and a scene director that selects context instead of guessing.
