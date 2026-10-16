# CHARALY — INFERENCE PERFORMANCE & STORY CONSISTENCY (PHASE 4)

Date: 2026-10-09
Tree: `/root/charaly`, branch `redesign-v5`. Worktree preserved; no git add/commit/push/
reset/rebase/merge/stash. Prior reports kept: `LATENCY_REPORT.md`, `LATENCY_REPORT_PHASE2.md`,
`LATENCY_REPORT_PHASE3.md`.

**Device: NOT TESTED.** `adb` is present; no device attached. Every number below is a
**host** number, measured on an 8-vCPU virtualised container through the shipping JNI
(`tools/native-harness/libcharaly_llama.so`). The container's throughput is ~10–25× slower
than a modern phone SoC, so the host numbers bound the *ratios*, not the phone latencies.

---

## 1. Measured bottleneck (Phase 1)

One real 10-turn Turkish story, Qwen2.5-1.5B Q4_K_M, contextSize=4096, threads=llama.cpp
default (4). Percentiles over the 10 turns:

| stage | p50 | p95 | share of TTFT |
|---|---|---|---|
| context build (SceneDirector + ContextBuilder + template) | 2 ms | 265 ms | ~0 % |
| tokenization | 41 ms | 58 ms | ~0 % |
| **prompt evaluation (native, KV eval)** | **81,499 ms** | **119,158 ms** | **~99.9 %** |
| TTFT (send → first token) | 87,107 ms | 119,181 ms | — |
| generation | 100,369 ms | 123,411 ms | — |
| total | 204,357 ms | 214,770 ms | — |
| action parse / events / persistence | 34 / 21 / 14 ms | 129 / 84 / 33 ms | ~0 % |

Warm KV-prefix reuse ratio = **0.482** (reused 8,718 of 18,086 prompt tokens).
Stop reasons: **length = 10 / 10** — every turn hit the 220-token cap; no EOG.

**Conclusion:** the only meaningful costs are **prompt evaluation** (TTFT) and **token
generation** (total). Context building, tokenization, parsing, event application and
persistence are all sub-200 ms and not worth touching. Nothing here is an app-layer stall.

## 2. What was already optimised (verified, not re-done)

* KV prefix reuse is **really working** on attention models (`reused>0`, `reuse_fallback=false`).
* One model resident; no unload/reload between turns (`residency=0 ms` on warm turns).
* Persistence no longer rescans the whole library per save (Phase 1 fix).
* Chat template + real EOG stop sequences; action protocol in the prompt.
* Streaming delivers the first token to the UI as it is produced.

## 3. Optimisations attempted in this pass

Thread count (host, Qwen2.5-1.5B, 3 turns, otherwise identical):

| threads | prompt_eval p50 | generation p50 | TTFT p50 |
|---|---|---|---|
| 4 (llama.cpp default, current) | 92,362 ms | **75,510 ms** | 92,391 ms |
| 8 | 90,898 ms | **242,765 ms** | 90,931 ms |

8 threads does **not** help prompt evaluation and makes generation 3.2× worse (thread
oversubscription on this host). **No change** — the current `threads = 0` (llama.cpp picks 4)
is kept.

No other low-risk change was found to be both measurable and safe:
* context build, tokenize, parse, events and persistence are already sub-200 ms;
* prefix reuse already works, and the Phase 2 measurement showed section reordering does not
  improve it under the sliding transcript window;
* lowering `maxOutputTokens` would cut generation but risks truncating a reply, so it is left
  for a quality-gated decision;
* the action protocol is required for the model to propose actions and is reused by the KV
  cache, so it is not removed.

The inference path is therefore already at its measured floor for this layer: **prompt size ×
throughput**. The remaining latency is intrinsic to the model and the hardware.

## 4. Story consistency

Deterministic 10-turn Turkish test (`StoryConsistencyTenTurnsTest`, scripted transport):
6 actions applied, **rejected-only turns changed neither relationships nor knowledge**,
transcript bounded at 20 entries, save/reload reproduced world + transcript + knowledge,
secret visible to the learner, relationship persisted. Real-model profile: 0 turns changed
the world without a validated action, 0 mojibake, reload ok.

## 5. Known issues (root-caused, not hidden)

* The `StoryLoopJourneysTest` failures observed earlier this session were root-caused to
  `ActionValidator` resolving a **RELATE target by name only** (the source character has an id
  fallback, the target did not) plus `ProposedActionParser.stripActions` not matching
  `<charaly:action/>`. A concurrent edit to `ProposedAction.kt` has since landed and the whole
  suite is now green (see §7); I did not make that edit (the brief protects those files).
* The model reaches the max-token cap every turn (no EOG) → generation always runs to the
  profile's `maxOutputTokens`. Lowering it would cut generation proportionally but risks
  truncating a reply; left for a quality-gated decision.
* Absolute host latencies are not phone latencies.

## 7. Tests and APK

```
:app:testDebugUnitTest        465 tests, 0 failures, 8 skipped
:charaly-runtime:test        1440 tests, 0 failures, 0 skipped
                             ─────────────────────────────────────
                             1905 tests, 0 failures, 8 skipped
```

The 8 skipped app tests are the real-GGUF ones (`RealStoryChatLoopTest` ×5,
`StoryLatencyBenchmarkTest`, `ActionProtocolModelTest`, `InferenceProfileTest`); they run when
a model is offered and are the harness behind §1–§6.

APK: `app/build/outputs/apk/debug/app-debug.apk`
(also `Charaly-debug-2026-10-09-perf-analysis.apk`), **92,330,157 bytes**. No weights bundled.

## 8. Files touched this pass

* `app/src/main/cpp/charaly_jni.cpp` — split `tokenize_micros` from `eval_micros` in the
  per-turn stats (observational).
* `charaly-runtime/.../inference/InferenceEngine.kt`, `app/.../LocalLlamaInferenceEngine.kt` —
  plumb the two new timings.
* `app/build.gradle.kts` — test property relays (`charalyThreads`, `charalyContextSize`,
  `charalyTurns`).
* New tests: `app/.../InferenceProfileTest.kt`, `charaly-runtime/.../StoryConsistencyTenTurnsTest.kt`.
* Report: `INFERENCE_PERFORMANCE_REPORT.md`.

No production behaviour changed in this pass.

## 9. Physical-device tests required

On a Xiaomi 14T Pro (or any real device): cold/warm TTFT, prompt-eval tok/s, generation
tok/s, peak RAM (`VmHWM`), the same 10-turn Turkish consistency run, and thread scaling
(4 vs 6 vs 8) — the host already shows that more threads is not automatically better.


## 6. Model ranking (host, same scenario)

| model | arch | warm reuse | action protocol | mojibake |
|---|---|---|---|---|
| **Qwen2.5-1.5B Q4_K_M** | qwen2 | **0.482–0.565** | **pass (parsed+accepted)** | 0 |
| Llama-3.2-1B Q4_K_M | llama | 0.376 | fail (no tag even when asked) | 1 |
| LFM2.5-1.2B Q8_0 | lfm2 | 0.0 (hybrid fallback) | n/a | 0 |

**Best available: Qwen2.5-1.5B-Instruct Q4_K_M.** It is the only candidate that both reuses
the KV prefix and completes the action pipeline.
