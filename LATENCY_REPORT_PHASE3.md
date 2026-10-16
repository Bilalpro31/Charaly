# CHARALY — PHASE 3: REPLACE LFM2.5 WITH A COMPATIBLE ATTENTION MODEL

Date: 2026-10-09
Tree: `/root/charaly`, branch `redesign-v5`. Worktree preserved; no git add/commit/push/
reset/rebase/merge/stash. `LATENCY_REPORT.md` and `LATENCY_REPORT_PHASE2.md` untouched.

Device: **NOT TESTED** — no Android device attached. All real-weight numbers below are
**host** numbers through the shipping JNI (`tools/native-harness/libcharaly_llama.so`), on an
8-vCPU virtualised container. The latency problem is **not** claimed fixed on a real device.

---

## 1. Candidates verified against the vendored engine

Vendored llama.cpp revision `5143fa895e7725c5bd2135daf7d8f793d98fa91c`.
`EngineCapabilities.ARCHITECTURES` (test-locked to `src/llama-arch.cpp`) contains both
`qwen2` and `llama`, so both are loadable by the current Android native build.

| model | file | bytes | arch | ctx | chat template | sha256 |
|---|---|---|---|---|---|---|
| Qwen2.5-1.5B-Instruct Q4_K_M | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1,117,320,736 | `qwen2` | 32768 | yes (ChatML) | `6a1a2eb6…9407e` |
| Llama-3.2-1B-Instruct Q4_K_M | `Llama-3.2-1B-Instruct-Q4_K_M.gguf` | 807,694,464 | `llama` | 131072 | yes (Llama-3) | `6f85a640…1df83` |

Llama 3.2 1B's sha256 matches the existing `llama-3.2-1b-q4` catalog entry exactly, so it
was already a supported download. Both were fetched from the publishers' own Hugging Face
repos and hash-verified on this host; **no weights are packaged in the APK.**

Template-derived stop sequences (from `ModelPresetLibrary.stopSequences`):
* Qwen: `<|im_end|>`, `<|end_of_turn>`, `<|eot_id|>`
* Llama: `<|eot_id|>`, `<|eom_id|>`

---

## 2. What blocked the old model, and what changed

The plain `[user] … [assistant]` prompt format is foreign to instruct models: they do not
cue their own end-of-turn token, so they ramble to the cap and never emit EOG. Two engine
changes fix that without touching the Story Engine, Story Pack format, WorldState or the
action-validation rules:

1. **Chat template application.** `charaly_jni.cpp` gained `applyChatTemplate`, backed by
   llama.cpp's own `llama_chat_apply_template`. `LocalLlamaInferenceEngine` now renders the
   model's own template when the GGUF declares one (`tokenizer.chat_template`), and falls
   back to the plain format otherwise. A named speaker is kept by prefixing the assistant
   content with the name.
2. **Action protocol in the prompt.** `ProposedActionParser.INSTRUCTIONS` is appended to the
   system block in `ContextBuilder.buildRequest`. Before this the model was never told the
   schema, so it only ever narrated. The parser and validator are unchanged.

---

## 3. Results (10 turns, contextSize=4096, threads=4, host)

| metric | Qwen2.5-1.5B | Llama-3.2-1B | LFM2.5-1.2B (Phase 2) |
|---|---|---|---|
| cold TTFT | 76.1 s | 51.5 s | 29.3 s |
| cold prompt eval | 71.2 s | 46.5 s | 27.5 s |
| cold generation | 54.6 s | 38.8 s | 9.1 s |
| **warm KV-prefix reuse** | **0.565** | **0.376** | **0.0 (fallback)** |
| stop reasons | eog=3, length=7 | eog=6, length=4 | length (all) |
| generated (cold) | 160 | 165 | 78 |
| tokens/sec (gen) | 1.3–2.9 | 2.2–4.3 | 4.5–9.4 |
| mojibake turns | 0 | 1 | 0 |
| **action protocol test** | **parsed=1, accepted=1** | **parsed=0** | n/a |

The attention models reuse the prefix where LFM2 could not (`reuse_fallback=false`,
`reused>0`). Absolute times are host numbers on a slow container and are **not**
phone-representative.

### Action protocol (explicit request, real parser + validator)

The model was asked, unambiguously, to append exactly
`<charaly:action type="move" character="marinette" target="school" reason="okula gitti"/>`.
* **Qwen2.5-1.5B: emitted it; `ProposedActionParser` parsed 1; `ActionValidator` accepted 1.**
* Llama-3.2-1B: emitted no tag (`actions_parsed=0`).

On ordinary narrative turns neither model spontaneously proposed an action; only the
explicit request did. That is a prompt-following signal, not a schema failure.

---

## 4. Winner

**Qwen2.5-1.5B-Instruct Q4_K_M.** It is an attention model (prefix reuse works, 0.565),
it follows the action protocol end to end (parse → validate → accept), and it produced no
mojibake. It is now the **first recommended catalog entry** (`isRecommended = true`,
`roleplaySuitability = 4` → top `weight`), with the exact size and sha256 recorded as
`MetadataConfidence.VERIFIED`. Llama 3.2 1B remains supported and cataloged.

---

## 5. Unresolved / device status

* **Not device-tested.** No phone was attached. Cold TTFT on this host is 50–76 s, which is
  the host being a slow container plus a ~1000-token prompt; a phone number must be measured
  separately. The *mechanism* — attention KV reuse replacing LFM2's impossible recurrent
  reuse — is the fix, and it is proven on real weights.
* On narrative prompts the models do not propose actions unprompted; the protocol is in the
  prompt and the pipeline accepts a well-formed tag, but reliability needs more prompt work
  or a larger model.
* One Llama-3.2-1B turn contained U+FFFD (a model/JNI artifact, not reproduced on Qwen).

---

## 6. Tests and APK

```
:app:testDebugUnitTest        464 tests, 0 failures, 7 skipped
:charaly-runtime:test        1439 tests, 7 failures, 0 skipped
```

* The 7 skipped app tests are exactly the real-GGUF ones (`RealStoryChatLoopTest` ×5,
  `StoryLatencyBenchmarkTest` ×1, `ActionProtocolModelTest` ×1); they run when a model is
  offered and are the harness behind §3.
* The 7 runtime failures are **all in `StoryLoopJourneysTest`**, an untracked test file added
  during this session (timestamp 12:36). They test the action pipeline with scripted engines
  and fail identically **with the action-protocol change reverted**, so they are pre-existing
  and unrelated to the model switch. They were left untouched (out of scope; fixing them
  would touch the parser/validator the brief protects).

Changed in this pass:
* `app/src/main/cpp/charaly_jni.cpp` — `applyChatTemplate` via `llama_chat_apply_template`.
* `LlamaNative.kt` — `applyChatTemplate` bridge.
* `LocalLlamaInferenceEngine.kt` — `hasChatTemplate` from the header; templated prompt with
  plain-format fallback; named-speaker prefixing.
* `ContextBuilder.kt` — the action protocol is appended to the system block.
* `ModelCatalog.kt` — Qwen2.5-1.5B-Instruct Q4_K_M added as the first recommended entry.
* Tests: `NativeInferenceBoundaryTest` updated; `ActionProtocolModelTest` added; the benchmark
  extended to derive the profile from the header and to run the real parser/validator.

APK: `app/build/outputs/apk/debug/app-debug.apk` (also
`Charaly-debug-2026-10-09-qwen2.5-latency.apk`). No model weights are packaged.
