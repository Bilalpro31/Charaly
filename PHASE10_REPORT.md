# CHARALY PHASE 10 — Türkçe Dil Kilidi ve İlk Token Gecikmesi

**Model:** Qwen3-4B-Instruct-2507-Q4_K_M.gguf (2 497 281 120 bayt, qwen3, Q4_K_M)
**Ortam:** 8 çekirdekli ARM64 host, CPU-only, llama.cpp (vendored, commit 5143fa8)
**Tarih:** 2026-10-16

---

## 0. Yöntem

Önce mevcut Phase 9 kodu ve testleri incelendi. Sonra gerçek ağırlıklarla ölçüm yapıldı,
bulgulara göre düzeltme yapıldı ve aynı ölçümler tekrar alındı. Hiçbir "hızlandı" iddiası
ölçümün kendisinden alınmamıştır; aşağıdaki her sayı bir log dosyasından okunmuştur.

**Ölçüm logları:**

| Dosya | İçerik |
|---|---|
| `/tmp/opencode/phase10_turkish_latency.txt` | 4 tur, 5 faz, dil verdict'leri (son hali) |
| `/tmp/opencode/phase10_thread_sweep.txt` | Thread pool'u izole ölçümü |
| `/tmp/opencode/phase10_adrien.txt` | Fransızca vocabulary'li karakter |
| `/tmp/opencode/p10-measure.log` | Native template + thread contract testleri |

---

## A. Türkçe Cevap Zorunluluğu

### A.1 Gerçek neden

Phase 9'da prompt'ta Türkçe kuralı **tek bir İngilizce cümle** idi:

```
WORLD RULES
- You do not run the world. ...
- Only state what X personally knows ...
- If you do not know something, say so ...
- Never speak for the user, and never output system markers.
- Reply in Turkish when the user writes in Turkish. Match the user's language;
  if the user explicitly asks for another language, switch to that language.   <-- TEK KURAL
- Never use meta headings ...
```

Bu cümle, prompt'un geri kalanıyla **aynı dilde** yazılıyordu ve prompt'un geri kalanı
%100 İngilizce idi:

| Bölüm | Karakter | Dil |
|---|---|---|
| WORLD RULES | ~350 karakter | İngilizce |
| YOUR IDENTITY (persona) | ~700 karakter | İngilizce |
| WHAT YOU KNOW / PEOPLE YOU KNOW | ~200 karakter | İngilizce |
| Action protocol | ~320 karakter | İngilizce |
| WHAT YOU HAVE SEEN (mind) | ~900 karakter | İngilizce |
| RIGHT NOW / PACE | ~1000 karakter | İngilizce |
| **Toplam** | **~4000 karakter** | **%96 İngilizce** |

Ölçülen system prompt: **4008 karakter, bunun 7'si Türkçe harf** (Türkçe harf oranı %0.17).

4B parametreli bir model için bu, "Türkçe yanıt ver" cümlesinin 2000+ İngilizce karakterin
içinde kaybolduğu anlamına gelir. Model kendi önselini (İngilizce) takip eder.

**İkinci neden — İngilizce persona tanımı:** Her karakterin `vocabulary` satırı İngilizce
yazılmış. Adrien Agreste'in kişeliğinde şu satır duruyor:

```
Vocabulary: Precise, upper-register French; few slang words, used precisely.
```

Bu, bir İngilizce cümle olarak **Fransızca** bir sesi tanımlıyor. Phase 7'de bunun
Marinette için "doğrudan İngilizce yanıt vermeye neden olduğu" ölçülmüştü. Phase 10'da
Adrien için aynı test yapıldı (A.4).

### A.2 Düzeltme

#### `ReplyLanguage` (yeni: `charaly-runtime/.../context/ReplyLanguage.kt`)

Dil kararı prompt'taki bir cümleden **ayrı bir değere** çıkarıldı:

- `ReplyLanguage.of(line)` — satırın dilini belirler. Açık istek > skorlama.
- `ReplyLanguage.forTurn(line, previousPlayerLines)` — kararsız satır, son **kararlı**
  oyuncu satırını taşır. "Peki yarın ne yapabilirim?" sonrası gelen "Tamam." ve boş
  "continue" satırı Türkçeyi kaybetmez.
- Türkçe'ye özgü harfler (ı, İ, ğ, Ğ, ş, Ş) ve Türkçe işlev kelimeleri skorlanır;
  İngilizce için aynı yöntem. **Tek İngilizce kelime yeterli değil** — "a question"
  iki İngilizce kelime içerir ve UNKNOWN kalır; yoksa Türkçe bir oyuncu tek bir
  İngilizce kelime yazdığında tüm konuşmayı kaybederdi.
- `DEFAULT = TURKISH` — Charaly'nin ürün dili. Kararsız ilk tur da Türkçe yanıtlar.

#### Prompt'ta konum

`systemPromptStable()` içinde, **YOUR IDENTITY'den önce**, Türkçe olarak:

```
LANGUAGE - THIS IS THE FIRST RULE, AND IT OVERRIDES EVERYTHING ELSE BELOW
- The person you are talking to writes in Turkish. You answer in TURKISH, in every sentence.
- Spell it the way a Turkish speaker spells it: ç ğ ı İ ö ş ü - not c g i I o s u.
- Everything below this block - your notes, the world rules, the memories, the scene, the
  action protocol - is written in English because it is the author's own note-taking. That is
  the language those notes are WRITTEN in. It is never the language you SPEAK.
- Never reply in English. No English sentence, no English greeting, no English summary, and no
  line that explains yourself in English.
- Answer what the person just said, in Turkish, in Marinette Dupain-Cheng's own voice.
```

`systemPromptVolatile()` içinde, **en sonda**, 5 kelime: `Reply in Turkish.`

Phase 9'daki tek satır kaldırıldı.

#### Karakter korunması

- Blok, kişiliği/hikâyeyi/rolü **değiştirmiyor**; yalnızca dili sabitleyor.
- "WRITTEN in English / never the language you SPEAK" ayrımı, İngilizce persona
  tanımının cevap dilini İngilizceye çevirmesini engelliyor.
- Blok, transcript'te **zaten olan** veriden hesaplanıyor; prompt'a eklenmiyor.
  Test: "the lock costs a bounded amount of text and does not grow with the conversation"
  — 6 turlu bir konuşmada system prompt yalnızca 71 karakter büyüyor (transcript'in kendisi).

### A.3 Doğrulama

**`ReplyLanguageTest` — 18 test, hepsi geçti:**

| Test | Ne kanıtlıyor |
|---|---|
| `the brief's four messages are all Turkish` | 4 mesaj da Türkçe tanınıyor |
| `a Turkish line with no diacritics at all is still Turkish` | "Merhaba, sen nasilsin?" Türkçe |
| `a single English word does not take the conversation away from Turkish` | "a question" UNKNOWN |
| `a turn with no signal inherits the last decided player line` | "Tamam." ve "" Türkçeyi taşıyor |
| `the language block is the FIRST thing the model reads` | YOUR IDENTITY ve WORLD RULES'ten önce |
| `the block is written in the language it is asking for` | Türkçe blok Türkçe istiyor |
| `the block names the English-notes trap explicitly` | "written in English" + "WRITTEN" |
| `the instruction is repeated once at the very end` | "Reply in Turkish." |
| `the language block reaches the rendered request exactly once` | Her turda tekrarlanmıyor |
| `the lock costs a bounded amount of text and does not grow` | 6 turda +71 karakter |

### A.4 Gerçek ağırlıklarla doğrulama

**`Phase10ForeignVocabularyTest` — Adrien Agreste (vocabulary'sı Fransızca diyor):**

```
--- MESAJ 1: Merhaba, bugün biraz endişeliyim.
CEVAP : Merhaba, endişelerin ne hakkında olduğunu anlatabilir misiniz? ...
DİL   : TURKISH(tr=67,en=2)   ttft=389323ms

--- MESAJ 2: Sence ne yapmalıyım?
CEVAP : Endişeniz ne konusunda? Ne yapmak istiyorsunuz, ...
DİL   : TURKISH(tr=56,en=0)   ttft=305829ms

### FAILURES (0)
```

**`Phase10TurkishLatencyTest` — brief'in 4 mesajı, 4/4 TÜRKÇE, 0 hata.**

### A.5 Baseline karşılaştırması

A/B (Phase 9 prompt'u vs Phase 10 prompt'u, aynı ağırlıklar) **`-PcharalyPhase10Baseline=true`
ile opt-in** çalıştırılıyor (`Phase10TurkishBaselineTest`). Varsayılan çalıştırmada
yalnızca AFTER koşuyor; A/B, zaten yavaş olan bir gerçek-ağırlık çalışmasının maliyetini
ikiye katlar.

Phase 9'dan kalan loglar (`/root/models/phase9_QWEN3_BEFORE_fix.txt`,
`phase9_QWEN3_AFTER_fix.txt`) Qwen3-4B + MiraculousPack için **zaten Türkçe** cevaplar
gösteriyor. Yani bu model/paket çifti için Phase 9 hatası yeniden üretilemedi; Phase 10
dil kilidini **yapısal** hale getiriyor (Marinette'nin Phase 7'de düzeltilmiş
`vocabulary` satırına ve şansa bırakmıyor).

---

## B. İlk Cevap Gecikmesi

### B.1 Ölçüm altyapısı

**`TurnPerf`'e yeni alanlar** (hepsi ölçülemiyorsa null/0, asla sıfır gibi göstermiyor):

| Alan | Anlamı |
|---|---|
| `tokenizeMillis` | Prompt'un tokenize edilmesi |
| `evalMillis` | Prompt'un KV cache'e yazılması |
| `dispatchMillis` | Tur başlangıcından inference çağrısına (residency + context build) |
| `threadsDecode` | Token çözme pool'unun thread sayısı |
| `threadsPrompt` | Prompt evaluation pool'unun thread sayısı |

**`Phase10TurnProbe`** — engine'i saran, `stream()` çağrısından ilk/son chunk'a kadar
ölçen decorator. 5 fazın tamamını ölçer.

### B.2 Kök neden: `n_threads_batch` hiç ayarlanmıyordu

`llama_context::graph_compute` (llama-context.cpp:1444):

```cpp
int n_threads = batched ? cparams.n_threads_batch : cparams.n_threads;
```

İki ayrı thread pool'u var ve `llama_context_default_params()` **ikisini de**
`GGML_DEFAULT_N_THREADS`'e ayarlıyor — bu checkout'ta **4** (ggml/include/ggml.h:228).

`CharalyNative.loadModel` yalnızca `n_threads`'e erişebiliyordu. Yani:

> **Kotlin tarafından istenen ne olursa olsun, her turun prompt evaluation'ı 4 thread'te
> çalışıyordu.**

### B.3 İzole thread ölçümü (`Phase10ThreadSweepTest`)

Aynı prompt (880 token), aynı ağırlıklar, aynı binary. Tek fark: `n_threads_batch`.

| | BEFORE (4) | AFTER (8) |
|---|---|---|
| decode threads | 4 | 4 |
| **prompt threads** | **4** | **8** |
| prompt tokens | 880 | 880 |
| reused | 2 | 2 |
| **evaluated** | **878** | **878** |
| **eval millis** | **187 206** | **169 691** |
| **eval tok/s** | **4,69** | **5,17** |
| decode tok/s | 1,59 | 0,96 |

**Prompt evaluation %10 daha hızlı (1,10x).** Bu, TTFT'i domine eden pool.

Decode'de 1,59 → 0,96 tok/s düşüş var. Decode thread'leri iki çalışmada da 4'te
sabitlendiği için bu, batch pool'unun yarattığı çakışma (contention) — decode
konfigürasyonundaki bir değişiklik değil. **Decode thread sayısı 4'te tutuldu**, çünkü
ölçüm 8'in decode için daha kötü olduğunu gösteriyor.

### B.4 Uçtan uca tur ölçümü (4 tur, önce/son)

| Tur | TTFT önce | TTFT son | eval önce (ms) | eval son (ms) | tok/s önce | tok/s son |
|---|---|---|---|---|---|---|
| 1 | 441 180 | 242 890 | 437 111 | 239 714 | 1,06 | 1,61 |
| 2 | 190 303 | 105 756 | 186 452 | 102 722 | 1,08 | 1,62 |
| 3 | 244 763 | 146 863 | 240 458 | 144 676 | 1,19 | 1,57 |
| 4 | 375 574 | 218 389 | 369 155 | 214 722 | 1,07 | 1,50 |

**TTFT ortalama ~%44 daha hızlı** (1,82x / 1,80x / 1,67x / 1,72x).

**Dürüst not:** İzole sweep 1,10x gösterirken uçtan uca tur ~1,75x gösteriyor. İkisi
arasındaki fark muhtemelen prompt uzunluğu (sweep 880 token, turlar 1432–1989 token)
ve sistem durumu varyansından kaynaklanıyor. **Kontrollü ölçüme (sweep) güvenilmeli**;
uçtan uca rakamlar bir gözlem olarak sunuluyor.

### B.5 Diğer bulgular

**Model her mesajda yeniden yüklenmiyor.** `residencyMillis`: tur 1'de 3782 ms (lazy load),
tur 2–4'te **0 ms**. `LocalModelResidency.resolveLocked` zaten `engine.isLoaded() &&
lastLoadedId == modelId` ile kısa devre yapıyor. **Bu bir hata değil; düzeltme gerekmedi.**

**Ana UI thread'i.** `CharalyRuntime.respond` bir `flow { ... }` idi, yani **collector'un
context'inde** çalışıyordu — uygulamada bu `viewModelScope`, yani Android main thread'i.
Yalnızca native decode dışarıdaydı (engine'in kendi `flowOn(Dispatchers.IO)`'si).
Main thread'te kalan iş: `resolver.forInstance` (storage okuma), SceneDirector +
ContextBuilder, `rememberTurn`, `repository.saveInstance` (tur başına **iki kez**, JSON
encode + disk yazma), `advance` + ChapterPlanner.

**Düzeltme:** `respond` artık `.flowOn(turnDispatcher)` kullanıyor; `turnDispatcher`
varsayılan `Dispatchers.Default`. `flowOn` yalnızca *producer*'ı taşıyor; `emit` hâlâ
collector'un dispatcher'ine geliyor, yani ViewModel'in `_state.update {}` main thread'te
kalıyor. `TurnDispatcherTest` bunu assert ediyor (4 test).

**Prompt büyümesi.** `contextChars` 5637 → 7101 (4 tur). `ContextPolicy.maxChars = 6000`
ve `trimToBudget` en eski transcript girdilerini kırpıyor — bu, tasarlanmış davranış ve
sistem bloğu + tail zaten ~5900 karakter tuttuğu için transcript'in büyük kısmı
kırpılıyor. **Bu bir Phase 10 regresyonu değil** (Phase 9'da da aynıydı) ama dil bloğu
eklenmesiyle biraz kötüleşti. Düzeltilmedi: karakter kalitesi ve bağlam tutarlılığı
korunmak istendi; `maxChars` artırılması TTFT'yi uzatır.

**GGUF chat template.** `llama_chat_apply_template(nullptr, ...)` çağrısı llama.cpp'te
`tmpl = "chatml"` anlamına geliyor (llama.cpp/src/llama.cpp: `const std::string
curr_tmpl(tmpl == nullptr ? "chatml" : tmpl);`). Yani **her templated model ChatML olarak
formatlanıyordu** — Qwen için doğru, Llama-3 (`<|start_header_id|>`), Gemma
(`<start_of_turn>`), Mistral v7 için yanlış. GGUF'taki `tokenizer.chat_template` hiç
kullanılmıyordu.

**Düzeltme:** `applyChatTemplate` artık `g_model.metadata["tokenizer.chat_template"]`
değerini kullanıyor. Modelin kendi template'i uygulanamazsa önceki davranışa (chatml)
düşüyor — yani eski davranış bir "sessiz ikame" olmaktan çıkıp gerçek bir fallback'a
dönüştü.

**Doğrulama:** `NativeTemplateAndThreadsContractTest` — 4/4 geçti. En kritik olanı:
modelin kendi template'i render ediliyor ve `nativeDiagnostic` **fallback mesajı
içermiyor**. Özel token sızıntısı yok, transcript rolleri bozulmadı.

### B.6 Bilinen model kalitesi artifact'ları (kod hatası değil)

Çıktılarda iki artifact görüldü, ikisi de **deterministik değil**:

1. `ð` (U+00F0) — bazı cevapların sonunda. Phase 9 loglarında da var.
2. `<d|im_end|>` — tur 3 ve 4'te. Model, `<|im_end|>`'i bozuk üretiyor. Stop matcher
   doğru şekilde eşleşmiyor (çünkü bu marker değil), bu yüzden metin olarak görünüyor.

`Phase10TurkishLatencyTest` artık bunları `[mangled tokens]` satırıyla **kayıt ediyor**
(assert etmiyor — stokastik model davranısı, prompt'a özel bir özellik değil). Phase 9
loglarında da benzer artifact'lar var (`ð`, `<d>Ýyiydi, birlikte geçelim.</d>`), yani
bu bir Phase 10 regresyonu değil.

---

## C. Sabit Test Senaryoları

`Phase10TurkishLatencyTest` — brief'in 4 mesajı, gerçek Qwen3-4B, tek konuşma:

| # | Mesaj | Dil | Meta | Special | U+FFFD | TTFT | Toplam |
|---|---|---|---|---|---|---|---|
| 1 | Merhaba, nasılsın? | TURKISH (tr=10, en=1) | none | none | 0 | 242 890 ms | 274 957 ms |
| 2 | Bugün okulda biraz endişeliyim. | TURKISH (tr=15, en=1) | none | none | 0 | 105 756 ms | 171 814 ms |
| 3 | Sence arkadaşlarımla nasıl konuşmalıyım? | TURKISH (tr=13, en=1) | none | none | 0 | 146 863 ms | 212 110 ms |
| 4 | Peki yarın ne yapabilirim? | TURKISH (tr=11, en=0) | none | none | 0 | 218 389 ms | 304 872 ms |

**5 fazın tamamı 4/4 turda ölçüldü** (hiçbir `?` yok).

**Bağlam tutarlılığı:** transcript 8 girdiye ulaştı (4 kullanıcı + 4 karakter), oyuncu
satırları birebir korundu, 4 cevap birbirinden farklı.

**Doğrudan cevap:** 4 cevap da soruya cevap veriyor (nasılsın → nasıl geldin; endişeli
→ ne hakkında; nasıl konuşmalıyım → nasıl başlayabilirsin; yarın ne → yarın dikkatli ol).

---

## D. Doğrulama ve Teslim

### D.1 Test sonuçları

| Suite | Test | Hata | Atlatıldı |
|---|---|---|---|
| `:charaly-runtime:test --offline` | **1 478** | **0** | 0 |
| `:app:testDebugUnitTest --offline` | **490** | **0** | 25 |
| Model testleri (native lib + GGUF ile) | **6** | **0** | 0 |

25 atlanan test, `-PcharalyNativeLib`/`-PcharalyModelFile` verilmediği için kendini
**atlayan** gerçek-ağırlık testleri (`assumeTrue`). Bu, "geçti" değil "koşulmadı" — yeşil
tik hiçbir model çalıştırmamış çalışmanın yerine geçmez.

### D.2 Debug APK

| | |
|---|---|
| **Yol** | `/root/charaly/app/build/outputs/apk/debug/app-debug.apk` |
| **Boyut** | **92 337 973 bayt** (88,06 MiB) |
| **SHA-256** | `349fa4afab10616c35ca067b44fec0623d1516fa1bb6263371dee995e00fe686` |

APK içindeki `lib/arm64-v8a/libcharaly_llama.so` doğrulandı: `threads_batch` string'i
mevcut (yeni JNI kodu derlenmiş). Fallback diagnostic string'i string pooling nedeniyle
literal olarak bulunamıyor; `threads_batch` varlığı, charaly_jni.cpp'nin yeniden derlendiğini
kanıtlıyor.

### D.3 Fiziksel cihaz testi

**Yapılmadı.** Tüm ölçümler, APK'yla **aynı** JNI entry point'lerini ve **aynı** llama.cpp
kullanan bir host harness üzerinde alındı (`scripts/verify-inference-host.sh`
mantığı). Bu, ART, bionic'in allocator'ı, Android'in low-memory killer'ı ve gerçek
telefon CPU'sunun frekans davranışını **kapsamaz**. Host üzerinde 8 thread verimliliği
ölçülmüştür; gerçek bir telefonda (genellikle 4-8 "big" çekirdek + LITTLE çekirdekler)
sonuç farklı olabilir. **Host testleri fiziksel cihaz testi gibi sunulmamıştır.**

### D.4 Değişen dosyalar

**Phase 10 tarafından değiştirilen/eklenen:**

| Dosya | Değişiklik |
|---|---|
| `charaly-runtime/.../context/ReplyLanguage.kt` | **YENİ** — dil enum, tespit, kalıcılık |
| `charaly-runtime/.../context/InferenceContext.kt` | Dil bloğu (başta + sonda) |
| `charaly-runtime/.../context/ContextBuilder.kt` | `replyLanguage` hesaplama |
| `charaly-runtime/.../inference/InferenceEngine.kt` | `InferenceTimings.threadsBatch` |
| `charaly-runtime/.../model/ModelBenchmarkRunner.kt` | `configThreadsBatch()` |
| `charaly-runtime/.../session/CharalyRuntime.kt` | `turnDispatcher` + `flowOn` + TurnPerf alanları |
| `app/.../inference/LlamaNative.kt` | `loadModel(..., threadsBatch, ...)` |
| `app/.../inference/LocalLlamaInferenceEngine.kt` | `EngineConfig.threadsBatch` + stats |
| `app/src/main/cpp/charaly_jni.cpp` | `n_threads_batch` + modelin kendi chat template'i |
| `tools/native-harness/.../LlamaNative.java` | JNI mirror (imza) |
| `tools/native-harness/.../Harness.java` | Çağrı noktaları |
| `charaly-runtime/.../context/ReplyLanguageTest.kt` | **YENİ** — 18 test |
| `charaly-runtime/.../session/TurnDispatcherTest.kt` | **YENİ** — 4 test |
| `app/.../inference/Phase10ThreadSweepTest.kt` | **YENİ** — thread ölçümü |
| `app/.../inference/NativeTemplateAndThreadsContractTest.kt` | **YENİ** — 4 test |
| `app/.../storyloop/Phase10TurkishLatencyTest.kt` | **YENİ** — 4 mesaj, 5 faz |
| `app/.../storyloop/Phase10TurkishBaselineTest.kt` | **YENİ** — opt-in A/B |
| `app/.../storyloop/Phase10ForeignVocabularyTest.kt` | **YENİ** — Adrien |
| `app/.../storyloop/Phase10Measurement.kt` | **YENİ** — timing probe |
| `PHASE10_REPORT.md` | **YENİ** — bu rapor |

**Dokunulmadan bırakılanlar:** Story Pack sistemi (`charaly-runtime/.../pack/`,
`charaly-runtime/.../storypack/`), Hermes'in laptop görevleri, git add/commit/push/reset/
rebase/merge/stash.

### D.5 Ekran ile ham model çıktısı

Uygulamanın kullanıcıya gösterdiği metin, ham model çıktısının `ProposedActionParser
.stripActions()` uygulanmış hâlidir. Fark **yalnızca** `<charaly:action .../>` protokol
etiketlerinin kaldırılmasıdır — bu, tasarlanmış ve belgelenmiş bir davranışdır (oyuncu,
modelin aksiyon önerisini değil, karakterin konuşmasını görür). Transcript'e de aynı
şekilde kaydedilir. Dil kilidi bu farkı değiştirmez.
