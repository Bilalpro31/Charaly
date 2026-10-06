# CHARALY — GGUF IMPORT CRASH P0 FORENSIC

Date: 2026-10-06
Tree: `/root/charaly`, HEAD `8cd4fbb Fix native JNI inference contract`
ADB / real-device: **NOT AVAILABLE** — `DEVICE CRASH NOT VERIFIED`.

---

## CONFIRMED

1. `importModel()` in `CharalyViewModel.kt` runs the full import chain and then, unconditionally, starts **one** extra coroutine that loads the model:

   `CharalyViewModel.kt:2093` `fun importModel(uri: Uri)` →
   `CharalyViewModel.kt:2128` `launch { loadModel(verified) }`

2. That coroutine is *not* the import coroutine. It is a second `viewModelScope.launch`
   nested inside the `onSuccess` of the SAF import. Callers of `loadModel` all go through
   `modelLoadLock.withLock`, so exactly one native load runs at a time.

3. `loadModelLocked` (`CharalyViewModel.kt:2330`) calls
   `runtime.loadModel(ModelLoadRequest)` (`CharalyRuntime.kt:966`), which delegates to
   `LocalLlamaInferenceEngine.loadModel` (`LocalLlamaInferenceEngine.kt:91`).

4. `LocalLlamaInferenceEngine.loadModel` does, on `Dispatchers.IO`:
   - Kotlin pre-checks: file exists, `>= 24` bytes, GGUF magic sniff, `LlamaNative.isAvailable()`
   - `LlamaNative.loadModel(path, contextSize=2048, threads=0, gpuLayers=0)`
   - then `LlamaNative.modelMetadata(handle)` and `LlamaNative.contextSize(handle)`

5. The JNI entry is `Java_dev_charaly_app_inference_LlamaNative_loadModel`
   (`charaly_jni.cpp:546`):
   - `release_model_locked()` (frees prior model/ctx)
   - `llama_backend_init()` (once)
   - `llama_model_load_from_file(model_path, params)` (`charaly_jni.cpp:633`)
   - `llama_init_from_model(model, ctx_params)` (`charaly_jni.cpp:646`) with
     `n_ctx=2048`, `n_threads=4`, `n_batch=512`
   - `collect_metadata(model)` (`charaly_jni.cpp:343`, invoked at :660)

6. Pinned llama.cpp is `5143fa895e7725c5bd2135daf7d8f793d98fa91c`
   (`llama.cpp/src` checkout, matches `EngineCapabilities.BUNDLED_COMMIT`).
   Architecture list in `EngineCapabilities` is test-locked to the vendored
   `llama-arch.cpp`.

7. There are **two** Kotlin GGUF header parsers:
   - `GgufReader` (`runtime/model/gguf/GgufReader.kt`) — used by download pipeline and Hub.
     Bounded, type-safe, catches `OutOfMemoryError`.
   - `GgufMetadataReader` (inside `runtime/compat/CharacterCardImporter.kt:125`) — used by
     `ModelManager.readMetadata` during import. Streams, bounds strings, but has a spec
     deviation: GGUF *array* counts are read as a 32-bit int (`readValue` array branch,
     line 243 reads `elementType = readIntLe()` then `count = readIntLe()`), whereas the
     GGUF spec encodes array length as **uint64** (spec: element type uint32, then count
     uint64). For headers whose array is preceded by any other consumer of uint64 alignment
     this can shift the parse. It never allocates unbounded memory and is wrapped in
     `runCatching`, so it fails soft — it does **not** crash the process by itself.

## MOST LIKELY ROOT CAUSE

Native `llama.cpp` load path dying in-process while `importModel`'s
`launch { loadModel(verified) }` triggers it on `Dispatchers.IO`.

Three mechanisms, in order of likelihood for a "closes the app, no Kotlin stack" report:

1. **Low-memory kill / LMKD** — model file + `n_ctx=2048` context + batch buffer 512 + KV
   cache + Android process RSS. `ModelManager.availableRamBytes()` (`ModelManager.kt:164`)
   is only used for *warnings*; nothing gates the load on it. A 2–4 GB GGUF on a low-RAM
   device is the most common way this app disappears from Recents with no crash dialog.

2. **Native SIGSEGV / SIGABRT from `llama_model_load_from_file` or `llama_init_from_model`**
   on an unsupported / corrupt / truncated GGUF that still passed the 4-byte magic check
   in `importInto` (`ModelManager.kt:481`) and the bounded Kotlin pre-check in the engine.
   `charaly_jni.cpp` has **no** signal handler, **no** C++ `try/catch` around llama.cpp,
   and llama.cpp itself `GGML_ASSERT`s/aborts on invariant failures — so an unsupported
   architecture or corrupt tensor table kills the process.

3. **Native context init failure surfacing as abort** — `llama_init_from_model` returning
   null is handled (`charaly_jni.cpp:647`), but a backend/allocation failure *inside* it
   is not recoverable.

## CRASH STAGE

`AUTO_LOAD` (started from import callback at `CharalyViewModel.kt:2128`)
→ `JNI` → `LLAMA_LOAD` / `CONTEXT_INIT`. The import copy, validation and registry
themselves complete and update UI state *before* the auto-load fires, so a crash here
presents as "app dies while importing" even though import finished.

## EVIDENCE

- `app/src/main/kotlin/dev/charaly/app/ui/CharalyViewModel.kt:2128` — `launch { loadModel(verified) }`
  after `syncModelState()`; the state was already READY at :2112-:2119.
- `app/src/main/kotlin/dev/charaly/app/inference/LocalLlamaInferenceEngine.kt:91-193`
  loadModel; native calls at :128 (`LlamaNative.loadModel`), :146 (`modelMetadata`), :170 (`contextSize`).
- `app/src/main/cpp/charaly_jni.cpp:633` — `llama_model_load_from_file`
- `app/src/main/cpp/charaly_jni.cpp:646` — `llama_init_from_model`
- `app/src/main/cpp/charaly_jni.cpp:660` — `collect_metadata`
- `app/src/main/cpp/CMakeLists.txt:30-46` — CPU-only build, `GGML_NATIVE OFF`, static llama/ggml linked into `charaly_llama`.
- `app/src/main/kotlin/dev/charaly/app/model/ModelManager.kt:164-169` — `availableRamBytes` exists but is only consulted for UI verdicts, not to block a load.
- `app/src/main/cpp/charaly_jni.cpp` — grep shows **no** `try {` / `catch` / `std::signal` / `sigaction` around llama.cpp calls, so native assertions/signals are fatal.

## DEVICE EVIDENCE

NOT COLLECTED. No `adb` on this machine and no device is attached. To verify:

```
adb logcat -c
adb shell am force-stop dev.charaly.app
adb logcat           # run import
adb logcat -d -b crash
adb shell dumpsys activity exit-info dev.charaly.app
adb logcat -d | grep -E "FATAL EXCEPTION|SIGSEGV|SIGABRT|libcharaly_llama|lmkd|lowmemory|OutOfMemory|AndroidRuntime"
```

Expected discriminator: `lmkd`/`lowmemory` ⇒ LMKD kill; `signal 11/6` + `libcharaly_llama.so` in the native stack ⇒ JNI/llama.cpp crash; `JNI DETECTED ERROR` ⇒ contract bug.

## MEMORY

- GGUF file size is on disk; llama.cpp `mmap`s it, so RSS pressure comes from the file
  mapping + `n_ctx=2048` KV cache + `n_batch=512` buffer + Android/JNI heap, not from
  "copying the file into RAM".
- Temporary `.part` copy doubles on-disk footprint during import, but is renamed before load.
- No measurement of the observed crash point was possible (no device).

## JNI

- `loadModel` resets prior state under `g_session_mutex` + `g_state_mutex` and returns
  handle `1` on success; `0` on handled errors. It does not protect the llama.cpp calls
  from signals/aborts.
- `collect_metadata` copies key/value strings into fixed 256/512 stack buffers and guards
  truncation via `llama_model_meta_*_by_index` return values — looks safe for sane
  metadata, but a corrupt `n` count from a bad model could still read OOB inside
  llama.cpp and crash natively.
- Dequantization kernel path for arm64 uses llamafile (CMakeLists :46). arm64-v8a keeps
  it; if the crashing device is armeabi-v7a, the crash would be in the generic GEMM path.

## NOT THE CAUSE

- Import copy itself: `importFrom` runs on `Dispatchers.IO`, validates (`isGguf`, size
  checks), and only then registers. A corrupt copy fails with `REASON_*` and never reaches native.
- The registry (`JsonModelRegistry`) is a JSON document write — cannot natively crash.
- `GgufMetadataReader` spec deviation (array count read as uint32 instead of uint64):
  would produce wrong metadata or a failed parse, not a SIGSEGV/LMKD.
- C++ `try/catch` around `llama_*` would not catch SIGSEGV/SIGABRT/`GGML_ASSERT` aborts;
  adding it is not a fix.
- `prepareHeading`/streaming paths are not on the import path.

## MINIMAL FIX (candidate, NOT APPLIED yet — needs device confirmation first)

No code change was made. The smallest change that addresses the confirmed mechanism once
the device log says which one it is:

- If LMKD: cap `n_ctx`/KV cache or refuse to auto-load when `availableRamBytes()` is below
  the model's derived estimate, and stop auto-loading from the import callback until the
  user explicitly opens the model.
- If native SIGSEGV/SIGABRT on unsupported/corrupt files: do not let an import auto-load;
  keep import → register → STOP, and only load on explicit user action. Longer-term,
  evaluate a separate `:llama` process or a llama.cpp bump (out of scope for P0).

## FOLLOW-UP (P1)

- Fix `GgufMetadataReader` array count to read uint64 to match the GGUF spec.
- `EngineConfig.threads = 0` is documented as "llama.cpp picks", but JNI maps 0→4
  (`charaly_jni.cpp:643`). Either document the real default or pass the native default.
- Cancellation from Kotlin (`invokeOnCompletion { requestStop(...) }`) only sets a flag
  checked between decoded tokens; it does not interrupt a blocking native decode — verify
  on device before calling it "cancelled".
- `quantization` metadata comes from `llama_model_desc()` in JNI and from
  `llama_model_ftype`-adjacent labels in Kotlin; they can disagree. Unify on the real
  file type / quant API, but do not fold this into the P0 crash fix.
- Device verification of the exact crash bucket (LMKD vs SIGSEGV vs JNI) is required
  before any architectural change (e.g. separate process) is justified.
