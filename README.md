# CHARALY

**A local-first, deterministic story runtime for Android.**

Charaly is a roleplay app where the world is not a hallucination. The language
model writes prose; Charaly's runtime owns the truth.

```
        Android UI  (Compose)
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

## Why the architecture looks like this

The single most important rule in this codebase:

> **The LLM is a language generator, not the world simulator.**

If the model is allowed to own world state, then a hallucinated sentence becomes
a fact and the story silently rots. So model output can only ever become world
state through one narrow, explicit, validated path:

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

---

## Layer by layer

| Layer | Class | Responsibility |
|---|---|---|
| Static definition | `StoryPack` | Immutable authoring data: characters, places, start state |
| Running story | `StoryInstance` | One playthrough: world state + knowledge + memories + queue + transcript |
| World truth | `WorldState` | Clock, locations, variables, character runtimes, relationships, threads, scenes |
| Story time | `WorldClock` / `StoryTime` | A *logical* clock. Time moves only when the runtime says so |
| Authoritative change | `WorldEvent` | Typed payloads: `CharacterMoved`, `KnowledgeDiscovered`, ... |
| State transitions | `EventEngine` | Pure reducer. Validates first, then applies. Replayable |
| Who knows what | `KnowledgeStore` | World truth **separate** from per-character knowledge |
| Persistent memory | `MemoryStore` | Structured memories, retrieved by importance + recency |
| Current moment | `Scene` / `SceneDirector` | Who is present, where, and which threads matter. Not prose |
| Prompt selection | `ContextBuilder` | The **only** class that turns world state into text |
| Narrative | `InferenceEngine` | Replaceable port: load / generate / stream / stop |
| Storage | `CharalyRepository` | JSON documents in app-private storage |

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

---

## Project layout

```
charaly-runtime/    pure Kotlin/JVM: all deterministic logic + the inference port.
                    No Android dependency, so the whole engine is testable on a
                    plain JVM with no device and no emulator.
app/                Android application: Compose UI, llama.cpp JNI, model import.
scripts/            setup-llama.sh
```

The split is deliberate. Every rule in this README is enforced by a test that
runs in seconds on a laptop.

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
and it is worked around with local shims (see the comments at the bottom of
`gradle.properties`). Nothing in those shims ships inside the APK: the real
tools still do the work.

Two properties help on such hosts:

```bash
# only cross-compile arm64 (a 32-bit shim cannot be used from ARM64)
./gradlew :app:assembleDebug -PcharalyAbis=arm64-v8a

# compile and link the native library without Gradle
scripts/verify-native-arm64.sh
```

---

## Using it

1. Launch Charaly. It restores local stories and seeds a demo pack. No network.
2. **Models** tab -> **Import GGUF**. Pick a `.gguf` file with the system file
   picker. It is copied into app storage; no broad storage permission is needed.
3. Select the model. Loading progress is shown; failures name the cause.
4. **Stories** tab -> start or continue a story.
5. **Scene** tab -> talk. Tokens stream in as they are produced; **Stop** aborts
   native generation immediately.
6. **Inspector** tab -> see the clock, the scene, character state, relationships,
   who knows what, pending events, and threads. Advancing the clock here is
   deterministic and fires due events.

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

263 JVM tests, no device, no emulator, no model download.

```
StoryPackTest              CharacterRuntimeTest      ContextBuilderTest
StoryInstanceTest          RelationshipTest          SceneDirectorTest
WorldStateTest             KnowledgeTest             EventEngineTest
WorldClockTest             MemoryTest                PersistenceTest
StoryThreadTest            CompatibilityTest         StoryPipelineIntegrationTest
CharalyApplicationTest     LocalLlamaInferenceEngineTest
```

The integration suite walks the whole pipeline end to end: pack -> instance ->
schedule -> advance clock -> process event -> verify world state -> build scene ->
build character-specific context -> mock engine -> streamed response -> persist ->
restart -> continue. It also proves the action boundary from both sides: prose
changes nothing, an explicit tag does.

---

## References

Architecture ideas were informed by ChatterUI, CloverPal, HearthSocial,
LettuceAI and SillyTavern. Charaly is not a clone of any of them. Its own layer
is the deterministic living-world runtime: world clock, event engine, explicit
knowledge, and a scene director that selects context instead of guessing.
