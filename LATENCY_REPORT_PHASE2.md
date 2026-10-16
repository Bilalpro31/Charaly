# CHARALY — PHASE 2 LATENCY: PROMPT PREFIX REUSE + LFM2 HYBRID CACHE

Date: 2026-10-09
Tree: `/root/charaly`, branch `redesign-v5`. Worktree preserved; no git add/commit/push/
reset/rebase/merge/stash was run. `LATENCY_REPORT.md` (Phase 1) is untouched.

Device: **NOT TESTED** — `adb` present, no device attached (`adb devices` empty). All
real-weight numbers are **host** numbers through the shipping JNI
(`tools/native-harness/libcharaly_llama.so` built from `app/src/main/cpp/charaly_jni.cpp`).

---

## Phase A — reproduce and map

Both GGUFs from Phase 1 were available and re-used verbatim:

| file | architecture | size | engine |
|---|---|---|---|
| `stories15M-q4_0.gguf` | `llama` (attention) | 19,077,344 B | contextSize 8192, threads 4 |
| `LFM2.5-1.2B…Q8_0.gguf` | `lfm2` (hybrid) | 1,246,255,424 B | contextSize 8192, threads 4 |

`threads=4` is llama.cpp's own default (`llama_context_default_params()`); the engine
passes `0` and native reports the choice back through the new `threads` stat.

The benchmark now runs **10 turns** and reports prompt tokens, reused tokens, evaluated
tokens, prompt-eval time, first-token time, generation time, generated tokens, stop reason
and total wall time per turn. Outputs:
`/tmp/opencode/bench-{original,reorder}-15m.txt`, `/tmp/opencode/bench-*-12b.txt`.

---

## Phase B — safe prompt prefix optimization (investigated, NOT shipped)

### The design that was tested

`ContextBuilder` puts volatile state (memory, recent events, clock) *before* the transcript.
Three orderings were rendered and measured, on identical code paths:

* **original** — everything in the historical order (memory between world-facts and people).
* **memory-late** — memory moved to the end of the system block.
* **volatile-late** — memory/events/clock rendered *after* the transcript (the
  "instructions → history → state reminder" shape the brief describes).

### Native measurement (stories15M, 10 turns each)

| ordering | warm average `reused/prompt` |
|---|---|
| original | **0.352** |
| memory-late | **0.354** |
| volatile-late (after transcript) | **0.238** |

`volatile-late` is a **measurable regression**: the reusable prefix collapses to the stable
block (~775 tokens), because the transcript window *slides* and its first message changes
every turn once three exchanges exist. `reused` for turns 4–10 is pinned at 774–776.

The original and memory-late orderings are **statistically indistinguishable** (0.352 vs
0.354). Reordering the system sections does not move the needle.

### Why the target is not reachable here

A sliding history window guarantees that the transcript's first message changes every turn,
so a contiguous prefix can never include the transcript. The ceiling is
`stable_tokens / total_tokens`, ~0.2–0.5 on a mobile budget. A character-level proxy
(`PromptPrefixReuseTest`, deterministic, scripted replies) shows the only combination that
crosses the 0.7 target: volatile-late **plus an append-only (non-sliding) history window**,
which rises monotonically `0.41 → 0.73` over 10 turns. Character-level is a proxy; the native
token-level figure is authoritative and no native append-only run was taken.

### Decision

**No prompt reordering ships.** It does not improve reuse and one variant regresses. The
`systemPromptStable()`/`systemPromptVolatile()` split is kept purely as a documented,
concatenation-preserving decomposition of `systemPrompt()`; production renders the single
combined block exactly as before. Rendered-prompt parity is pinned by
`ContextBuilderTest.the stable and volatile blocks together are the whole system prompt`.

### Concrete evidence-based next option (not implemented)

Make the history window **append-only with chunked eviction** (keep turns until the entry
count reaches `2 × window`, then drop `window` turns) and render volatile state after the
transcript. Expected: `reused/prompt` > 0.7 on attention models and a corresponding prompt-
eval reduction. Risks to resolve first: it changes which entries the model sees (more
history), and on a 2048-token device context it can overflow the window. This is a history-
management change with its own review; it was deliberately not made here.

---

## Phase C — LFM2 hybrid cache

Vendored revision `5143fa895e7725c5bd2135daf7d8f793d98fa91c`. The fallback is **necessary
and correct**, proven from the source:

```
llama_memory_hybrid::seq_rm -> llama_memory_recurrent::seq_rm
  // models like Mamba or RWKV can't have a state partially erased
  if ((0 < p0 && p0 <= cell.pos) || (0 < p1 && p1 <= cell.pos)) return false;
```

Our call `llama_memory_seq_rm(mem, 0, keep, -1)` has `p0 = keep > 0` on a warm turn and
`cell.pos` is the last processed position, so the recurrent cache refuses and the hybrid
`seq_rm` returns false. The engine then clears the whole cache and evaluates fully —
`reuse_fallback=true`, `reused=0` on every warm LFM2 turn. **No code path bypasses the
failed `seq_rm`.**

A supported alternative exists but is not safe to bolt on:

* `llama_state_seq_get_data` / `llama_state_seq_set_data` can snapshot/restore a whole
  sequence state. It only helps if the next prompt's token sequence *exactly* extends the
  saved one (a recurrent state cannot be rewound to an arbitrary position), so it needs the
  append-only history above, plus save/restore of the KV *and* recurrent state every turn.
* It is a substantial native feature with a real risk of restoring stale state. Left as the
  documented next option, not implemented.

**Unresolved limitation:** on LFM2, first-token latency remains 29–62 s and this pass does
not change that. Any claim otherwise would be false.

---

## Phase D — generation-time diagnostics

From the native stats (new: `stop_reason`, `budget`, `requested_max`, `threads`):

* **Every one of the 10 host turns ended `stop_reason=length`, `generated=320`,
  `budget=320`, `requested_max=320`.** The base model never emitted EOG. So generation is
  not "slow for the tokens it wrote"; it writes the full cap every turn. Generation is the
  dominant cost on this model: 13.4 s → 27 s per turn, `tokens/sec` falling 23.8 → 11.8 as
  the context grows.
* **Prompt growth is real and unbounded by the declared budget.** `context_chars` rose
  `3638 → 13697` while the profile declares `maxChars=6000`. `ContextBudget.maxChars` is
  enforced only by `ContextBudgetInspector`, which is **not** on the `respond` path; the
  real `ContextBuilder` ignores it. `prompt_tokens` reached 4080.
* Threads: `4` (llama.cpp default). Not tuned; on a phone the heterogeneous core layout
  makes 4 defensible, and changing it was out of scope.
* Thermal/throttle: not measurable on this host; the descending `tokens/sec` across a run
  and the 3–5× slower later turns in one run are consistent with host thermal/contention,
  which is why only the deterministic `reused/prompt` ratio is used for the ordering
  decision, not absolute wall times.

Fixes proposed, **not applied** (each is an explicit, measurable option, not a default):

1. Align the derived profile's stop sequences with the prompt's own turn markers
   (`[user]`, `[system]`) so a model that continues the transcript stops instead of rambling
   to the cap. (`CharalyRuntimeProfile.stopSequences()` currently lists `\nUSER:`/`\nSYSTEM:`,
   which the prompt format never emits.)
2. Enforce the existing `maxChars` budget on the real path (drop low-priority sections whole,
   as `ContextBudgetInspector` already does) — as an opt-in, with rendered-prompt diffing.

No global `maxTokens` was lowered and no response was cut.

---

## Phase E — regression and report

Full deterministic suites, no native properties (so the real-weight tests skip honestly):

```
:charaly-runtime:test        1393 tests, 0 failures, 0 skipped
:app:testDebugUnitTest        456 tests, 0 failures, 6 skipped
                             ─────────────────────────────────────
                             1849 tests, 0 failures, 6 skipped
```

The 6 skipped are exactly the tests that need a real GGUF / native library
(`RealStoryChatLoopTest` × 5, `StoryLatencyBenchmarkTest` × 1); they run when a model is
offered and are the harness behind Phases A/B/D.

Files changed in this pass (all observational or test-only; no model-visible prompt change):

* `app/src/main/cpp/charaly_jni.cpp` — added `stop_reason`, `budget`, `requested_max`,
  `threads` to the per-turn `GenerateStats` (read through `lastGenerateStats`). No behaviour
  change; the existing prefix-reuse path is untouched.
* `LlamaNative` / `LocalLlamaInferenceEngine` / `InferenceTimings` — plumb those four stats
  to Kotlin.
* `InferenceContext` — `systemPrompt()` is now factored into `systemPromptStable()` +
  `systemPromptVolatile()`; the concatenation is byte-identical to the previous single
  method, so the rendered prompt is unchanged.
* `ContextBuilder.buildRequest` — renders the single combined system block (as before).
* Tests: `StoryLatencyBenchmarkTest` (now 10 turns, reuse ratio + stop reason),
  `PromptPrefixReuseTest` (deterministic character-level prefix proxy),
  `ContextBuilderTest` (prompt parity + contract), `LocalLlamaInferenceEngineTest`.

APK: `/root/charaly/app/build/outputs/apk/debug/app-debug.apk`
(also `/root/charaly/Charaly-debug-2026-10-09-latency-phase2.apk`), **92,252,245 bytes**.

Device: **not tested.** No device attached. All real-weight numbers are host numbers through
the shipping JNI; the *ratio* (prompt tokens skipped) transfers, the absolute tok/s does not.
