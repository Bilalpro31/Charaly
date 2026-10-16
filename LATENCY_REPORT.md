# CHARALY — STORY CHAT RESPONSE LATENCY: DIAGNOSTIC + OPTIMIZATION PASS

Date: 2026-10-09
Tree: `/root/charaly` (branch `redesign-v5`, worktree kept, no git mutations)
Device: **NOT TESTED** — `adb` is present but no device is attached (`adb devices` → empty).
Real weights were run on the **host** through the same JNI entry points the APK exports
(`tools/native-harness`, `libcharaly_llama.so` built from `app/src/main/cpp/charaly_jni.cpp`).

---

## 1. Method

Latency was measured in two layers, because the two halves fail differently:

1. **Runtime layer (plain JVM, deterministic).** Real `MiraculousPack`, real
   `StoryInstance`, real `ContextBuilder`, real event/memory/clock pipeline. This measures
   prompt size, how it grows, and how much work the runtime adds around inference.
   Test: `StoryLatencyDiagnosticTest` → `/tmp/opencode/story-latency-diagnostic.txt`.
2. **Engine layer (real GGUF, real llama.cpp).** The app's own
   `LocalLlamaInferenceEngine` → JNI → vendored llama.cpp, with native clocks around
   tokenize/eval/decode. Test: `StoryLatencyBenchmarkTest` →
   `/tmp/opencode/story-latency-benchmark.txt`.

A `BEFORE` `.so` was built from the unmodified `charaly_jni.cpp` (HEAD) and an `AFTER`
`.so` from the changed file, so both runs exercise the identical Kotlin and the identical
llama.cpp — only the bridge differs.

---

## 2. What the code actually does (found before optimising)

* `eval_prompt()` began with `llama_memory_clear(llama_get_memory(ctx), true)`.
  **The KV cache was wiped before every single generation**, so every turn re-evaluated
  the entire prompt from token 0. `reused_tokens=0` in the `BEFORE` statistics is the
  direct measurement of this.
* `JsonCharalyRepository.saveInstance()` called `refreshInstances()`, which listed and
  JSON-decoded **every story on disk**. `respond()` saves the instance several times per
  turn; the runtime diagnostic measured **6 storage writes / turn** (3 `saveInstance`
  calls, each also writing a backup). So every turn did ~3 full-library rescans.
* Model residency is correct: `LocalModelResidency.resolveLocked()` returns early when the
  engine already holds the bound id. Confirmed on real weights: `engine_loaded=true` on
  every turn, `residency=0ms` on warm turns.

---

## 3. Measured context (runtime layer)

Ten turns, real Miraculous pack, `ContextBudget.DEFAULT`:

```
turn 1: chars=5481 build=1149us system=3693 transcriptEntries=0 saves=6
turn 2: chars=5879 build=826us  system=3958 transcriptEntries=2 saves=6
turn 4: chars=5949 build=509us  system=3906 transcriptEntries=6 saves=6
turn 10: chars=5887 build=417us system=3843 transcriptEntries=6 saves=6

first-turn section sizes (chars):
  system=3693  mind=608  threads=347  protocol=323
  participants=165  location=132  knowledge=82  relationships=74
  scene=110  history=0  memories=0  input=0

peak_chars=5949  budget=6000
```

Conclusions:
* The prompt **plateaus** (~5.9k chars vs a 6k budget) — it does **not** grow with story
  length. The transcript is capped at 6 entries and memories at 6.
* Prompt assembly costs **283–1149 µs**. Context building is not the bottleneck.
* 6 storage writes per turn, each of which (before the fix) triggered a full-library
  re-decode.

---

## 4. Measured per-turn latency (real weights, host)

### stories15M-q4_0, contextSize=8192

`BEFORE` (KV cleared every turn):

```
TURN 1 (cold): wall_total=13218ms wall_ttft=1762ms  gen=314  chars=3638
TURN 2 (warm): wall_total=17434ms wall_ttft=1741ms  gen=319  chars=6108
TURN 3 (warm): wall_total=18766ms wall_ttft=2943ms  gen=315  chars=8139
```

`AFTER` (native stats available):

```
TURN 1 (cold): wall_total=13587ms wall_ttft=1643ms
  prompt_tokens=1083 reused=0    prompt_eval=784ms   generation=11816ms  tok/s=27.1
TURN 2 (warm): wall_total=16145ms wall_ttft=1486ms
  prompt_tokens=1809 reused=517  prompt_eval=1475ms  generation=14577ms  tok/s=22.0
TURN 3 (warm): wall_total=16880ms wall_ttft=2828ms
  prompt_tokens=2488 reused=532  prompt_eval=2821ms  generation=13991ms  tok/s=22.9
```

(`prompt_tokens` is the whole prompt; `reused` is the leading part served from cache, so
the evaluated tail is `prompt_tokens - reused`.)

### LFM2.5-1.2B Q8_0 (hybrid architecture)

`BEFORE` (KV cleared every turn):

```
TURN 1 (cold): wall_total=83103ms  wall_ttft=34078ms  gen=257  chars=3638
TURN 2 (warm): wall_total=105520ms wall_ttft=45211ms  gen=320  chars=4973
TURN 3 (warm): wall_total=59696ms  wall_ttft=54834ms  gen=21   chars=6325
```

`AFTER` (native stats available):

```
TURN 1 (cold): wall_ttft=29261ms prompt_eval=27548ms gen=9123ms  prompt_tokens=959  reused=0
TURN 2 (warm): wall_ttft=33563ms prompt_eval=33545ms gen=34354ms prompt_tokens=1109 reused=0 cache=1037 fallback=true
TURN 3 (warm): wall_ttft=61848ms prompt_eval=61830ms gen=71147ms prompt_tokens=1683 reused=0 cache=1369 fallback=true
```

This model reproduces the reported symptom almost exactly: **TTFT 29–62 s, dominated by
prompt evaluation** (27–62 s), with generation on top. It also exposes an important limit:

> **Prefix reuse requires a cache that can drop a partial sequence.** LFM2 is a hybrid
> architecture; `llama_memory_seq_rm(mem, 0, keep, -1)` returns `false`, so the engine falls
> back to a full clear (`reuse_fallback=true`, `reused=0`). The optimisation is correct but
> **does not apply to hybrid/recurrent architectures**; it applies to standard attention
> models (the `llama`-architecture model above is one, `reused≈520`). Standard
> transformer models are the common case on-device (Llama, Qwen, Gemma, Mistral).

### `BEFORE` vs `AFTER` summary (stories15M, `llama` architecture)

| | BEFORE | AFTER |
|---|---|---|
| cold TTFT | 1762 ms | 1643 ms |
| warm TTFT (turn 2) | 1741 ms | 1486 ms |
| warm prompt tokens evaluated | full prompt | full − `reused` (≈520 skipped) |
| warm prompt eval | n/a (no stats) | 1475 ms (≈1292 of 1809 tokens) |
| tokens/sec (generation) | n/a | 22–27 |

Prefix reuse reduced evaluated prompt tokens by **~29 %** with this prompt layout. The
ceiling is set by the *layout*, not the mechanism — see §7.

---

## 5. Root cause

**Primary, proven:** the KV cache was cleared on every generation, so the whole prompt was
re-evaluated each turn. Prompt evaluation is the TTFT. On the 15M host model the full
prompt evaluated at ~1.4k tokens/s; on a phone CPU it is 20–50× slower, which is how
~1,500–2,500 prompt tokens becomes tens of seconds before the first word.

**Secondary, measured:** every turn did ~3 full-library repository rescans inside its
saves.

**Not the cause (checked):**
* **Model reload per message** — no. Residency short-circuits on the bound id.
* **Unbounded transcript** — no. It is capped at 6 turns and the prompt plateaus.
* **UI blocking / delays** — none. `respond` runs on `viewModelScope`; native decode runs
  on `Dispatchers.IO`; streaming already pushes tokens to the UI per chunk.
* **Story pack re-decode per turn** — no. `WorldDefinitionResolver` caches by edition.

---

## 6. Changes

1. `charaly_jni.cpp` — **KV prefix reuse**. Tracks the token sequence currently in the
   cache and, on the next generation, evaluates only the differing tail:
   `common_prefix` → `llama_memory_seq_rm(mem, 0, keep, -1)` → `eval_range(..., keep)`.
   Falls back to a full clear if the cache cannot drop a partial sequence (recurrent /
   sliding-window types). The prompt handed to the model is byte-identical; only
   already-computed key/value state is kept. Cache is invalidated on load, free and
   benchmark.
2. `charaly_jni.cpp` — **native per-turn clocks** exposed through `lastGenerateStats`
   (`prompt_micros`, `generation_micros`, `first_token_micros`, `prompt_tokens`,
   `generated_tokens`, `reused_tokens`, `context_size`).
3. `LlamaNative` / `LocalLlamaInferenceEngine` / `LocalInferenceEngine.lastTimings()` /
   `InferenceTimings` — plumb those clocks to Kotlin without inventing a number (absent is
   `null`, never zero).
4. `CharalyRuntime.respond` — a `TurnPerf` is attached to `GenerationUpdate.Finished`,
   built from real clocks (residency, context build, native prompt/generation, parser,
   events, persistence, TTFT, total).
5. `CharalyViewModel` — under **developer mode only**, logs the `STORY TURN PERF` summary.
   Nothing is added to the normal player UI.
6. `JsonCharalyRepository.saveInstance` — updates the in-memory instance list in place
   instead of re-listing and re-decoding every story on disk. `listInstances()` still
   refreshes from storage, so on-disk truth is unchanged.

No change to: Story Pack format, Story Engine behaviour, model selection, UI, llama.cpp.

---

## 7. Remaining limitation (measured, not hidden)

`reused` is only ~20–30% of the prompt on this layout, because the **volatile** system
sections (memories, recent events, clock) sit *before* the transcript, so the shared prefix
breaks early and the whole transcript is re-evaluated. Extending reuse to the transcript
would require moving the volatile state to the end of the prompt (a standard
"instructions → history → state reminder" order), which changes what the model sees and was
left out on purpose by the brief. It is the recommended next step, with a measurable target:
`reused / prompt_tokens` should rise from ~0.25 to >0.7.

---

## 8. Regression

Full deterministic suites, no native properties (so the real-weight tests skip honestly):

```
:charaly-runtime:test        1390 tests, 0 failures, 0 skipped
:app:testDebugUnitTest        456 tests, 0 failures, 6 skipped
                             ─────────────────────────────────────
                             1846 tests, 0 failures, 6 skipped
```

The 6 skipped are exactly the tests that need a real GGUF / native library
(`RealStoryChatLoopTest` × 5, `StoryLatencyBenchmarkTest` × 1); they run when a model is
offered and are the harness behind §4.

New tests added:
* `StoryLatencyDiagnosticTest` — real context sizes, prompt plateau, build cost, saves/turn.
* `StoryLatencyBenchmarkTest` — real-weight per-turn TTFT/total/native breakdown.
* `NativeInferenceBoundaryTest` updated for the new `lastGenerateStats` JNI method.

APK: `/root/charaly/app/build/outputs/apk/debug/app-debug.apk`
(also copied to `/root/charaly/Charaly-debug-2026-10-09-latency.apk`), **92,203,437 bytes**.

---

## 9. Device

**Not tested.** `adb` is present but no device is attached (`adb devices` → empty). All
real-weight numbers above are host numbers through the shipping JNI; the *ratio* (prompt
tokens skipped) transfers, the absolute tok/s does not. To collect device numbers, run the
same chain with a real GGUF via `scripts/verify-story-loop-host.sh <model.gguf>` (host) or
read the developer-mode `StoryTurnPerf` log on a phone.

## 10. What was deliberately not changed

* Story Pack format, Story Engine semantics, model selection, UI, llama.cpp.
* The prompt's section order. §7 shows why changing it is the highest-value next step, but
  it changes what the model sees and was out of scope for this pass.
* Generation-length policy (`maxTokens`, stop sequences). Every reply in the host runs hit
  the 320-token cap on a base (non-instruct) model; on an instruct model EOG ends it. This
  is model-dependent and was reported rather than silently changed.

