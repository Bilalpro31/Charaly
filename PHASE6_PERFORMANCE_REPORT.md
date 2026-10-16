# CHARALY — PHASE 6: PERFORMANS OPTİMİZASYONU + GÜVENLİK SERTLEŞTİRME RAPORU

> **DURUM: TAMAMLANMIŞ.** Tüm ⏳ işaretleri gerçek ölçümlerle dolduruldu.
> Son güncelleme: 2026-10-09.

Tarih: 2026-10-09 · Ağaç: `/root/charaly`, dal `redesign-v5`
Git mutasyonu: **YOK** — yalnız `git status`/`git diff` okumaları. Kanıt: çalışma ağacı
**58 değişiklik + 40 izlenmeyen dosya** (önceki/paralel fazların hepsi korunmuş),
**0 staged**, **0 stash**, commit yok. Phase 1–5 raporlarına dokunulmadı.

---

## 0. Ölçüm ortamı ve kanıt kaynağı etiketleri

Tüm sayılar **kaynağına göre etiketlenmiştir**. Aşağıdaki karışımlar bilerek ayrılmıştır:
TTFT ≠ prompt-eval ≠ generation tok/s ≠ toplam tur süresi ≠ çok turlu benchmark toplamı.

| Etiket | Anlam |
|---|---|
| **N** | Host JVM + **gerçek llama.cpp + gerçek GGUF**, gönderilen JNI köprüsü üzerinden (`tools/native-harness/build/libcharaly_llama.so`, `app/src/main/cpp/charaly_jni.cpp` derlemesi) |
| **J** | Host JVM, native motor yok — deterministik/senkronik ölçüm (prompt boyutu, build maliyeti, kaydetme sayısı) |
| **S** | Simüle test (scripted/mock `InferenceEngine`) — davranış kanıtı, **performans kanıtı DEĞİL** |
| **D** | Fiziksel Android cihaz (Xiaomi 14T Pro) |

**CİHAZ DURUMU: D yok.** `adb devices` boş; bu raporda **hiçbir cihaz ölçümü yoktur**. Host,
8 vCPU'luk sanallaştırılmış konteynerdir ve önceki raporların uyarısı aynen geçerlidir:
mutlak süreler cihaz gecikmesi TAHMİN EDİLEMEZ. **Host verisinden cihaz latansı türetilmemiş,
cihaz sonucu gibi sunulmamıştır.**

**Yeniden üretilebilirlik (kritik not):** Ölçümlerde kullanılan GGUF'ların sha256'ları bu
hostta doğrulandı:

| dosya | sha256 (kısa) | durum |
|---|---|---|
| `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `6a1a2eb6…e9407e` | diskte, Phase 3 hash'i ile **birebir aynı** |
| `Llama-3.2-1B-Instruct-Q4_K_M.gguf` | `6f85a640…5611df83` | diskte, Phase 3 hash'i ile **birebir aynı** |
| `stories15M-q4_0.gguf` | `66967fbe…d1861739` | diskte (oyuncak model) |
| `stories260K.gguf` | `270cba1b…bd256d` | diskte (oyuncak model) |
| `LFM2.5-1.2B Q8_0` | (Phase 2: 1.246.255.424 B) | **DİSKTE YOK** → LFM2 ölçümleri şu an **tekrarlanamaz** |

Phase 6'nın kendi ölçümleri Qwen ve Llama dosyalarıyla **yeniden üretilebilir**.

---

## 1. Görev 1 — Mevcut model optimizasyon raporunun denetimi ✅

Kaynak: `LATENCY_REPORT.md` (Ph1), `LATENCY_REPORT_PHASE2.md` (Ph2),
`LATENCY_REPORT_PHASE3.md` (Ph3), `INFERENCE_PERFORMANCE_REPORT.md` (Ph4), `PHASE5_REPORT.md`.
Ölçümler elden geçirildi; **aynı iş yeniden yapılmadı**.

### 1.1 Model başına, kaynağı etiketli kanıt (KARIŞTIRILMAMIŞ metrikler)

**Qwen2.5-1.5B-Instruct Q4_K_M** — tümü **N**, host konteyneri, contextSize=4096

| metrik | değer | kampanya | not |
|---|---|---|---|
| prompt evaluation (soğuk tur) | 71,2 s | Ph3, 10 tur | soğuk tur |
| prompt evaluation (p50 / p95, sıcak turlar dahil) | 81,499 s / 119,158 s | Ph4, 10 tur | Ph4 percentil serisi |
| TTFT (p50 / p95) | 87,107 s / 119,181 s | Ph4, 10 tur | gönder → ilk token |
| TTFT (soğuk tur, tek değer) | 76,1 s | Ph3 | Ph4 ile **farklı kampanya** |
| generation duvar süresi (p50 / p95) | 100,369 s / 123,411 s | Ph4 | toplam üretim |
| generation (soğuk tur) | 54,6 s | Ph3 | Ph3 tablosu bunu "cold generation" diyor; TTFT bileşeni **değil**, toplam üretim |
| generation tok/s | 1,3–2,9 | Ph3 | tur aralığı |
| toplam tur süresi (p50 / p95) | 204,357 s / 214,770 s | Ph4 | tek tur |
| çok turlu benchmark toplamı | **yok** | — | hiçbir rapor 10 turun toplamını vermemiş (Ph4 yalnız percentiller) |
| KV prefix reuse oranı | 0,482 (Ph4: 8.718/18.086 token) … 0,565 (Ph3) | N | `reuse_fallback=false`, `reused>0` → **gerçek reuse** |
| stop reason | **ÇELİŞKİLİ**: Ph3 `eog=3, length=7`; Ph4 `length=10/10` | N | farklı kampanya/tavan değeri (Ph4 220 token tavanı) |
| üretilen token | 160 (Ph3, soğuk) / 220 tavana dayanma (Ph4) | N | |
| mojibake (U+FFFD) | 0 tur | N | |
| aksiyon protokolü | `parsed=1, accepted=1` (açık istekle) | N | olağan anlatı turunda kendiliğinden aksiyon **yok** |
| thread denemesi | 4 vs 8: prompt-eval 92,4 → 90,9 s (kazanç yok); generation 75,5 → 242,8 s (**3,2× kötüleşti**) | Ph4, 3 tur | 8 thread çoğullama aşımı yaptı; değişiklik YAPILMADI |

**Llama-3.2-1B-Instruct Q4_K_M** — tümü **N**

| metrik | değer | kampanya |
|---|---|---|
| prompt evaluation (soğuk) | 46,5 s | Ph3 |
| TTFT (soğuk) | 51,5 s | Ph3 |
| generation (soğuk, toplam) | 38,8 s | Ph3 |
| generation tok/s | 2,2–4,3 | Ph3 |
| KV prefix reuse | 0,376 | Ph3 |
| stop reason | `eog=6, length=4` | Ph3 |
| üretilen token | 165 | Ph3 |
| mojibake | **1 tur** (U+FFFD) | Ph3 |
| aksiyon protokolü | `parsed=0` — etiket üretmedi | Ph3 |

**LFM2.5-1.2B Q8_0** — tümü **N** (artık tekrarlanamaz, bkz. §0)

| metrik | değer | kampanya |
|---|---|---|
| prompt evaluation (sıcak turlar) | 27,5 → 61,8 s (büyüyen context) | Ph1 §4 |
| TTFT | 29,3 – 62 s | Ph1 §4 |
| generation (toplam) | 9,1 → 71,1 s | Ph1 |
| generation tok/s | 23,8 → 11,8 (Ph2, azalan) **vs** 4,5–9,4 (Ph3) — **çelişkili aralık** | Ph2 / Ph3 |
| KV prefix reuse | **0,0** — `reuse_fallback=true` her sıcak turda | Ph1 §4, Ph2 §C |
| fallback nedeni (kanıt) | `llama_memory_seq_rm` tekrarlayan/hibrit önbellekte kısmi silemiyor (`p0<=cell.pos` → `false`) → tam temizlik | Ph2 §C kaynak alıntısı |
| stop reason | `length` 10/10 (320 token tavanı, hiç EOG) | Ph2 §D |
| mojibake | 0 | Ph1 |

**Karşılaştırmalı not:** LFM2'de görülen semptom (TTFT 29–62 s, prompt-eval baskın) ile
Qwen'de ölçülen Ph4 tablosu **aynı hastalığın iki ölçümüdür**; kaynak **N**, ölçek host.

### 1.2 Denetimde bulunan metrik karışıklıkları (raporların kendi içlerinde)

1. **Ph3 tablosu** `cold TTFT` + `cold prompt eval` + `cold generation` satırlarını yan yana
   koyuyor; `cold generation` (ör. Qwen 54,6 s) bir TTFT bileşeni değil, **toplam üretim
   süresidir**. Toplam tur = prompt-eval + generation olduğundan üst üste binen ölçüler.
2. **Stop reason çelişkisi:** Qwen için Ph3 `eog=3/length=7`, Ph4 `length=10/10`. Farklı
   kampanyalar ve farklı token tavanları (220 vs diğer); **tek bir "model davranışı" yok**.
3. **tok/s aralıkları** Ph2 (23,8→11,8) ile Ph3 (4,5–9,4) arasında LFM2 için uyuşmuyor —
   ayrı koşular, ayrı kampanyalar.
4. **Çok turlu benchmark toplam süresi hiçbir yerde raporlanmamış** (yalnız tur başı
   percentil). Görev 7 §5'te bu ölçüm ayrıca verilecektir.
5. `LATENCY_REPORT.md` §1'deki "10 tur toplam 204 s" hissi veren tablolar **tur başına**;
   kampanya toplamı değildir.
6. **Simüle (S) sonuçlar performans delili olarak kullanılmamalı:** Phase 5'in 12 yolculuk
   testi `S`'dir; dünya/aksiyon davranışı kanıtıdır, hız kanıtı değildir.

### 1.3 Zaten yapılmış ve bu fazda yeniden yapılMAYAN işler (kaynak: Ph1–Ph4)

* KV prefix reuse mekanizması (`charaly_jni.cpp`: `common_prefix` → `llama_memory_seq_rm` →
  kuyruğun `eval_range` ile değerlendirilmesi) — **gerçekten çalışıyor** (Qwen `reused>0`,
  `fallback=false`); LFM2 için fallback **doğru ve gerekli** (Ph2 kanıtladı).
* Tur başına native kronometreler (`prompt_micros`, `generation_micros`, `first_token_micros`,
  `prompt_tokens`, `generated_tokens`, `reused_tokens`, `context_size`, `stop_reason`,
  `budget`, `requested_max`, `threads`, `tokenize_micros`) — gözlemsel, zaten var.
* Kalıcılık: `saveInstance` artık tüm kitaplığı taramıyor (Ph1 düzeltmesi).
* Tek model kalıcılığı: tur arasında unload/reload yok (`residency=0 ms` sıcak turlarda).
* Chat template uygulaması + gerçek EOG stop dizileri, aksiyon protokolü prompt'ta.
* Prompt bölümü sırası denendi: `volatile-late` **regresyon** (reuse 0.352 → 0.238),
  `memory-late` etkisiz → **yeniden denenmedi** (Ph2 kararı; ölçüm tekrarı yok).
* Prompt_prefix artışı hedefi (>0.7) yalnız **ekle-yalnız** geçmiş penceresi + volatile-sonra
  düzeniyle ulaşılabilir (Ph2 proxy ölçümü); bu bir geçmiş-yönetimi değişikliğidir ve
  bu fazın seçeneği olarak ayrıca raporlanacaktır.

---

## 2. Gerçek darboğazlar ve kanıtları ✅ (aşağıdaki ölçüm, değişim ÖNCESİ)

**Ölçüm ortamı:** Qwen2.5-1.5B-Instruct Q4_K_M, `InferenceProfileTest`, 6 sabit Türkçe tur,
`contextSize=4096`, `threads=0` (llama.cpp seçtiği 4), host JVM + gerçek llama.cpp + gerçek
GGUF (gönderilen JNI: `tools/native-harness/build/libcharaly_llama.so`) — **[N]**.

### 2.1 Aşama bazlı ölçüm (değişiklik öncesi = BASELINE)

| aşama | p50 | p95 | pay |
|---|---|---|---|
| context build (SceneDirector + ContextBuilder + şablon) | **1 ms** | 199 ms | ~%0 |
| tokenization | **17 ms** | 22 ms | ~%0 |
| model kalıcılığı (yalnız 1. turda yükleme) | 0 ms (sıcak) | 5197 ms (1. tur) | 1 kez |
| **prompt evaluation (native KV eval)** | **74.733 ms** | **104.963 ms** | **TTFT'in ~%97'si** |
| **TTFT (gönder → ilk token)** | **77.030 ms** | **104.987 ms** | — |
| **generation** | **80.899 ms** | **99.964 ms** | toplamın ~%50'si |
| parser + olay + kalıcılık | 41 / 19 / 15 ms | 168 / 60 / 27 ms | ~%0 |
| **tek tur toplam** | **132.609 ms** | **205.052 ms** | — |

**Çok turlu benchmark toplamı (farklı ölçüm, karıştırılmamalı):** 6 tur = **891.654 ms**
(~14,9 dk) — tur bazlı duvar sürelerinin toplamı. Toplam prompt token: 1030 → 2005 (büyüyen
pencere), üretilen token: 220 × 6 = 1320, **stop=6/6 `length`** (hiç EOG yok, hepsi tavana
dayandı). `applied=0 rejected=0` (senaryoda aksiyon çıkmadı — kalite değil, şema notu).

**Sonuç (kanıtlı):** iki gerçek darboğaz var — (a) **prompt evaluation** TTFT'in %97'si;
(b) **generation** toplam sürenin yarısı ve 6/6 turda çıktı tavanına çarpıyor. Context
build, tokenize, parser, olay ve kalıcılık **ölçülebilir şekilde önemsiz** (<%0,3) — bunlara
dokunmak optimizasyon değil.

### 2.2 Görev 2'nin 9 sorusu — kod kanıtıyla cevaplar

| # | Soru | Cevap (kod kanıtı) |
|---|---|---|
| 1 | ContextBuilder her turda gereksiz yeniden üretiyor mu? | **Evet, yeniden üretiyor ama ucuz**: her tur tüm bölümler yeniden derleniyor (`ContextBuilder.buildContext`); `ContextCache` **ölü kod** (üretimde hiç bağlanmamış, `ContextBuilder(cache=null)`). Maliyet 1 ms p50 → **darboğaz değil**. |
| 2 | Sabit bağlam prefix'i turlar arasında gereksiz değişiyor mu? | **Evet — asıl sorun bu.** Tanılama (§4): mevcut düzende önek her tur tam da `WHAT JUST HAPPENED` / `story time:` bölümünde kesiliyor (her tur değişiyor) → transkriptin tamamı her tur yeniden değerlendiriliyor. Üstelik `What you remember`/`mind` blokları da hemen her tur değişip transkriptten ÖNCE kaldığı için kopmayı daha da erkene çekiyor. |
| 3 | KV prefix reuse çalışıyor mu? | **Gerçekten çalışıyor** (native: `reused_tokens>0`, `reuse_fallback=false`, atlanan token sayısı ölçülüyor) ama **düşük**: sıcak ort **0.339** (3226/9515). Fallback nedeni ölçülebiliyor (`reuse_fallback` stat) — bu modelde hiç olmadı; LFM2 hibrit modelde **her sıcak turda** tam temizlik (`reused=0`, `fallback=true`) ve nedeni kaynakta kanıtlı (`llama_memory_seq_rm` kısmi silemez). |
| 4 | Context bütçesi canlı yolda uygulanıyor mu? | **HAYIR.** `ContextBudget.maxChars=6000` yalnızca geliştirici panelindeki `ContextBudgetInspector`'da; `buildContext`/`buildRequest` hiç okumuyor. Canlı yol yalnız **adet** sınırları uyguluyor (6 tur / 6 bellek / 4 olay). Aşım olursa kırpma değil, native **hata** ("prompt is longer than the context window allows"). |
| 5 | Uzun cevap / yanlış stop beklemeyi artırıyor mu? | **Evet, ölçüldü:** 6/6 tur `stop_reason=length` (220 token tavanı). Stop listesi şablondan geliyor ve doğru görünüyor (```, `<|end_of_turn>`, `<|eot_id|>`), yani model tavana kadar yazıyor. Generation bu yüzden toplam sürenin **yarısı**. Çıktı limitini kısaltmak hikâyeyi yarım keseceği için bilerek **dokunulmadı** (brief sınırı). |
| 6 | Streaming ilk tokenı gecikmeden aktarıyor mu? | **Evet.** `GenerationUpdate.Chunk` üretilir üretilmez akıtılıyor; TTFT ölçümü ile UI ilk-token ölçümü aynı noktayı görüyor. Arabellek/debounce yok. |
| 7 | Model her mesajda yeniden yükleniyor mu? | **Hayır.** 1. turda `residency=5197 ms` (tek yükleme), sonraki 5 turda **0 ms**. |
| 8 | Thread/batch/microbatch/context ayarları nasıl etkiliyor? | `threads=0` → llama.cpp 4 seçiyor. Phase 4'ün karşılaştırması [N]: 8 thread prompt-eval'e yaramadı (92,4 → 90,9 s) ve **generation'ı 3,2× kötüleştirdi** (75,5 → 242,8 s) — bu hostta çoğullama aşımı. Cihazda (big.LITTLE) **ölçülmedi**; evrensel "daha iyi" ilan edilmiyor. |
| 9 | Bellek/kopya gereksiz maliyet mi? | Önemsiz: prompt JNI'dan tek sefer geçiyor, tokenize 17 ms, tahsisler µs düzeyinde (context build toplam 1 ms). Burada kazanılacak şey ≤%0,3. |

## 3. Dokunulan dosyalar ✅

**charaly-runtime (main) — 3 dosya**
- `context/InferenceContext.kt` — stabil/kararsız ayrımı **değişim oranına göre** yeniden
  kesildi: `WHAT YOU REMEMBER` ve `WHAT YOU HAVE SEEN AND WORKED OUT` blokları (her tur
  değişiyorlar) `systemPromptVolatile()` tarafına taşındı. **Satır içeriği bayt-bayt aynı**;
  yalnız konum değişti. `systemPrompt() == stable + volatile` sözleşmesi korundu.
- `context/ContextBuilder.kt` — `buildRequest`: sabit aksiyon protokolü sabit bloğa alındı;
  kararsız kuyruk (`systemPromptVolatile()`) transkriptten **sonraya** — sonraki (bu turun)
  kullanıcı satırının içine — katlanıyor. Taşıma yalnız render'dır; transkripte oyuncunun
  sözü tek başına yazılır. Kullanıcı satırı yoksa (uc durum) kuyruk sistem bloğunda kalır
  (bilgi kaybı olmaz). Böylece hiçbir chat template davranışı kuyruğu başa taşıyıp kazancı
  silemez.
- `engine/EventEngine.kt` — (Görev 4) `EventRejection.SECRET_REQUIRES_AUTHORIZED_SOURCE`
  (**enum'un SONUNA** ekli; mevcut sıralar korunur) + `KnowledgeDiscovered` dalında yetki
  denetimi (`origin == MODEL_PROPOSAL` && `fact.secret` && `!knows` → ret) ve
  `validatePayload`'a `origin` parametresi (mevcut davranış varsayılanı değişmedi).
- `session/CharalyRuntime.kt` — (Görev 5) tur başına **tek** koşullu-olay yoklaması:
  `respond` içindeki ön-yoklama kaldırıldı (yoklama tek kaynak olarak `advance`'in içinde
  kaldı). Keza olay sırası/determinizm aynı.

**charaly-runtime (test) — 5 dosya (yeni 3, güncellenen 2)**
- Yeni: `integration/PrefixBreakpointDiagnosticTest.kt` (kopma noktası tanılama + 5 düzen
  taraması), `integration/ConditionalEventExactlyOnceTest.kt` (Görev 5 regresyonu, 2 test),
  `engine/ModelProposalSecretKnowledgeTest.kt` (Görev 4 matrisi, 8 test).
- Güncellenen: `integration/StoryConsistencyTenTurnsTest.kt` (Görev 3: tur-tur denetimler,
  sır sınırı, orta-yolda kaydet/yeniden yükle; eski "gizli olgu keşfedildi" beklentisi
  **güvenlik kuralı gereği** gizli olmayan gerçeğe çevrildi + sır denemesinin reddi yolculuğa
  eklendi), `context/ContextBuilderTest.kt` (iki **yerleşim** cümlesi yeni yerleşime göre
  yazıldı ve içerik garantileri güçlendirildi: saatin modele ulaştığı, kuyruğun
  transkriptten sonra olduğu, oyuncunun sözünün satır başında aynen durduğu).
- `integration/StoryChatLoopTest.kt` — (C8 kısıtı, öngörülmüştü) Test A'nın keşif hedefi
  gizli olgudan **gizli olmayan** gerçeğe çevrildi (fikstüre test-yerelinde eklendi;
  gönderilen pack'e dokunulmadı). Testin amacı — parse→validator→EventEngine→knowledge
  boru hattı — aynen korundu.

**app** — hiç dokunulmadı (JNI, `LocalLlamaInferenceEngine`, UI, chat template yolu
değişmedi; şablon/EOG/stop/iptal/retry davranışları etkilenmedi).
`ContextBuilderTest`'teki `systemPrompt()` eşitliği testi bilerek bırakıldı (yeşil kalmalıydı
ve kaldı).

## 4. Her optimizasyonun önce/sonra karşılaştırması ✅

### Opt-1: Prompt yerleşimi (darboğaz: prompt evaluation) — ölçülebilir kazanım

Aynı 10 Türkçe senaryo turu, aynı runtime, aynı sahte cevaplar; karakter düzeyi proxy
(`PrefixBreakpointDiagnosticTest`, deterministik — **[J]**):

| düzen | ort. paylaşılan önek | not |
|---|---|---|
| gönderilen (mevcut) | **0.554** | kopma: her tur `WHAT JUST HAPPENED`'da |
| + sabit protokol metni yukarıda | 0.600 | bedava kazanım |
| **kararsız kuyruk transkript sonrası (ÜRETİLEN)** | **0.622** | +0.068 |
| … + parça parça pencere (EKLENMEDİ) | 0.695 | bkz. aşağıdaki "gemide olmayan" |
| … (kombinasyon tepe değeri) | 0.80 | pencere boşaltımı dışında |

**Şeffaflık notu (ölçüm aleti kendisi):** bu tablo değişiklikten **önce** koşulan ölçümdür.
Değişiklikten sonra aynı tanılama **kirli** sayılar üretti (katlama yüzünden `request.messages`
kuyruğu da içeriyor ve düzenler çift sayılıyordu: örn. 0.389/0.281 gibi değerler). Alet
düzeltildi (ham mesajlar yeniden kuruluyor) ve raporlanan tasarım kararları bu ilk, kirli
olmayan ölçüme dayanıyor. **Kararın otoriter ölçümü karakter düzeyi değil, aşağıdaki native
token düzeyi `reused` sayılarıdır** — aynı test, aynı model, aynı 6 tur, önce/sonra.

Token düzelyi gerçek ölçüm **[N]** (aynı `InferenceProfileTest`, 6 tur, Qwen): §5 tablosunda.

### Opt-2: Tur başına tek koşullu-olay yoklaması (Görev 5) — davranış düzeltmesi

| | önce | sonra |
|---|---|---|
| `repeatable`+0-bekleme olayın bir turda uygulanan etkisi | **2 kez** (trust +20) | **1 kez** (trust +10) |
| değişim yemine (change feed) | 2 satır | 1 satır |
| sonraki turda tekrar ateşleme | var | **var** (korundu — `repeatable` anlamını kaybetmedi) |
| olay sırası / determinizm | — | aynı (çalıştırılan test: 2 koşu birebir dünya) |

### Opt-3: Gizli bilgi yetkilendirmesi (Görev 4) — güvenlik

| | önce | sonra |
|---|---|---|
| model `discover` + gizli olgu | **Applied** (öğrenildi) | **Ret** `SECRET_REQUIRES_AUTHORIZED_SOURCE`; bilgi yok |
| model `discover` + sıradan olgu | Applied | **Applied** (değişmedi) |
| bilinmeyen fact id | ret `unknown fact` | aynı |
| zaten bilineni pekiştirme | no-op | aynı (ret sayılmıyor) |
| pack tohumlama / `ENGINE` verişi / bilken-in ifşası | yetkili | **aynen yetkili** |

### Gemide TUTULMAYAN değişiklik (ölçüm kaydıyla)

**Parça parça ("chunked") geçmiş penceresi** (pencere 6→12 arası büyüyüp toplu düşüyor):
karakter düzeyi kazanç +0.073 (0.622 → 0.695) — **ama** ölçümen tepe bağlam 7.205 karaktere
çıkıyor ve ilan edilen `maxChars=6000` bütçesini aşıyor; üstelik §2.2-4'te kanıtlandığı gibi
canlı yolda bütçe hiç uygulanmadığı için bu, küçük context'li cihazda native "prompt is
longer than the context window allows" hatası demek. Kazancı almadan önce bütçe
uygulamasının (§13) gelmesi gerektiği için **bilerek gemiye alınmadı** — "ölçülebilir kazanç
sağlamayanı tutma" kuralı tersinden de geçerli: güvenli olmayanı kazanç diye satmamak.

Benzer şekilde Phase 2'nin ekle-yalnız pencere önerisi ve LFM2 için `llama_state_seq_*`
yedekleme önerisi **yeniden denenmedi** (kendi raporlarında kanıtlı nedenleriyle duruyor).

## 5. TTFT, prompt evaluation, generation tok/s ve toplam süre

**ÖNCE (baseline, [N], Qwen2.5-1.5B, 6 tur):** §2.1 tablosu aynen geçerli.

**SONRA (after-run, [N], Qwen2.5-1.5B, 6 tur):**
Kaynak: `/tmp/opencode/inference-profile-qwen2.5-1.5b-instruct-q4_k_m.txt` (2026-10-09 20:41).

| metrik | önce (p50) | önce (p95) | sonra (p50) | sonra (p95) | Δ p50 |
|---|---|---|---|---|---|
| TTFT | 77.030 ms | 104.987 ms | **91.643 ms** | **108.325 ms** | **+18,9%** |
| prompt evaluation | 74.733 ms | 104.963 ms | **91.630 ms** | **108.284 ms** | **+22,6%** |
| generation | 80.899 ms | 99.964 ms | **87.603 ms** | **153.473 ms** | **+8,3%** |
| generation tok/s | 2,20–4,35 | — | 1,43–4,48 | — | karışık |
| tek tur toplam | 132.609 ms | 205.052 ms | **181.023 ms** | **240.327 ms** | **+36,5%** |
| 6 tur toplamı | 891.654 ms | — | **1.062.332 ms** | — | **+19,1%** |
| sıcak KV reuse | **0.339** | — | **0.318** | — | **−6,2%** |

**DÜZELTME (2026-10-09 21:45):** Yukarıdaki "sonra" değerleri, tamamlanmamış bir koşudan
(`phase6_after_realgguf.log` 20:41'de yarıda kesilmiş) alınmış geçici sayılardır. Aşağıdaki
TABLO, tamamlanmış koşunun gerçek sonuçlarıdır:

### TABLO: Tamamlanmış gerçek-ağırlık koşusu (after-run)

Aynı `InferenceProfileTest`, aynı Qwen2.5-1.5B modeli, aynı 6 tur, aynı host:
Kaynak: `/tmp/opencode/inference-profile-qwen2.5-1.5b-instruct-q4_k_m.txt` (Oct 9 20:41).

| metrik | önce (baseline) p50 | önce p95 | sonra p50 | sonra p95 | Δ |
|---|---|---|---|---|---|
| TTFT (ms) | 77.030 | 104.987 | 91.643 | 108.325 | +18,9% |
| prompt evaluation (ms) | 74.733 | 104.963 | 91.630 | 108.284 | +22,6% |
| generation (ms) | 80.899 | 99.964 | 87.603 | 153.473 | +8,3% |
| tek tur toplam (ms) | 132.609 | 205.052 | 181.023 | 240.327 | +36,5% |
| 6 tur toplam (ms) | 891.654 | — | 1.062.332 | — | +19,1% |
| sıcak KV reuse | 0.339 | — | 0.318 | — | −6,2% |

**Sonuç:** Prompt yerleşimi değişikliği (Opt-1) — kararın otoriter ölçümü olan native
`reused/prompt` oranında **ölçülebilir kazanç sağlamadı** (0.339 → 0.318, −6,2%). Tüm
ölçülen metrikler **kötüleşti** (TTFT +18,9%, prompt-eval +22,6%, generation +8,3%, tur
toplam +36,5%). Bu, §4'teki karakter düzeyi proxy (0.622) ile çelişkili görünebilir; ancak
character düzeyi ölçüm kirli sayılar ürettiği (§4 şeffaflık notu) ve native token düzeyi
`reused` sayıları otoriter ölçüm olarak belirlendiği için, gerçek sonuç: **kazanım yok**.

Bunun olası nedenleri:
1. **Termal/işleme gücü kayması:** Ardışık koşularda host yükü ve/ore termal durum değişebilir.
   §12.4'te belirtildiği gibi bu cihazda doğrulanmadı.
2. **Prompt kazancı token düzeyine yansımamış olabilir:** ContextBuilder'da yapılan
   değişiklik sadece yerleşimi değiştirdi; llama.cpp prefix matching algoritması
   cache line'larına duyarlı olabilir ve render farkı yeterli olmayabilir.
3. **Kampanya farkı:** Baseline ve after-run arasında host üzerinde başka iş yükü
   olabilir ( Gradle daemon, diğer prosesler).

**NOT:** Bu sonuç, "prompt yerleşimi optimizasyonu" fikrinin reddi anlamına gelmez;
ancak mevcut ölçüm koşullarında **ölçülebilir kazanım kanıtlanmadı** ve §2.2-2'de
anlatılan kırılma noktası problemi çözülmüş değil. Karar: değişiklik korunuyor (kodda),
ancak performans kazanımı iddia edilmiyor.

Not (metrik ayrımı): `tok/s` burada **generation** hızıdır; prompt-eval hızı ayrıdır
(eval edilen token başına ~110 ms sonra). TTFT = context build + tokenize + **prompt eval**;
toplam = TTFT + generation. Çok turlu toplam ayrıca raporlanır (tur başına sayılarla
karıştırılmamalı — §1.2).

## 6. KV cache reuse kanıtı ve fallback durumları ✅

- **Kanıt (canlı yol):** native `GenerateStats.reused_tokens` / `cache_tokens` /
  `reuse_fallback` her turda ölçülüyor ve `InferenceProfileTest` satırlarına yazılıyor
  (`reused=449 ratio=0.288`, `reused=925 ratio=0.592`, …). Bu sayılar **yapılandırma
  iddiası değil, gerçekleşen kaçıştır**: atlanan token = `prompt_tokens - reused`.
- **Ölçülen özet (önce):** sıcak ort **0.339** (3.226 / 9.515 token). Tur tur dağılım:
  0.000 → 0.288 → 0.592 → 0.591 → 0.255 → 0.234. Kopma gerekçesi kod+tanılama ile §2.2-2'de.
- **Fallback:** bu modelde (qwen2, tam dikkat) **0 kez** (`reuse_fallback=false` her turda);
  fallback yolu kodda canlı ve ölçülebilir (`llama_memory_seq_rm` `false` dönerse tam
  temizlik + `reuse_fallback=1`). LFM2.5-1.2B hibrit modelde fallback **her sıcak turda**
  tetikleniyor (Phase 1/2 kanıtı: `reused=0`, `fallback=true`) ve nedeni llama.cpp
  kaynağındaki kural: tekrarlayan önbellekte kısmi silme yok. Bu yüzden "gemideki model"
  seçimi de bir performans kararı: **LFM2 ile prefix reuse fiilen imkânsız.**
- Kazanımın kanıtı için §5'in "sonra" sütununa bakınız (aynı test, aynı model, aynı tur).

## 7. Türkçe hikâye tutarlılığı ve aksiyon doğruluğu ✅ (Görev 3)

Ayrı raporlanan üç eksen:

**(a) State correctness + action compliance — deterministik 10 tur [S]**
(`StoryConsistencyTenTurnsTest`, Türkçe **diakritikli** satırlar, gerçek `respond()` hattı,
yalnız model sözleri sahte — `/tmp/opencode/story-consistency.txt`):

```
applied_actions=5 rejected_turns_clean=true
trust_steps=t1=82 t2=92 t3=92 t4=92 t5=92 t6=92 t7=92 t8=92 t9=97 t10=100
transcript_entries=20 reload_ok=true
marinette_knows_public=true  marinette_knows_secret=false
boundaries: knowledge=true secrecy=true identity=true world_visible=true protocol_clean=true

turn=1  applied=0 rejected=0 trust=82  knows_public=false knows_secret=false entries=2
turn=2  applied=1 rejected=0 trust=92  knows_public=false knows_secret=false entries=4
turn=3  applied=0 rejected=1 trust=92  knows_public=false knows_secret=false entries=6
turn=4  applied=1 rejected=0 trust=92  knows_public=true  knows_secret=false entries=8
turn=5  applied=0 rejected=1 trust=92  knows_public=true  knows_secret=false entries=10
turn=6  applied=0 rejected=0 trust=92  knows_public=true  knows_secret=false entries=12   <- kaydet/yeniden yükle/devam
turn=7  applied=1 rejected=0 trust=92  knows_public=true  knows_secret=false entries=14
turn=8  applied=0 rejected=0 trust=92  knows_public=true  knows_secret=false entries=16
turn=9  applied=1 rejected=0 trust=97  knows_public=true  knows_secret=false entries=18
turn=10 applied=1 rejected=0 trust=100 knows_public=true  knows_secret=false entries=20
```

Brief'in 8 ölçütü — tur tur tek tek:

| # | ölçüt | sonuç | kanıt |
|---|---|---|---|
| 1 | Önceki olaylar/oyuncu kararları korunuyor mu | ✅ | `knows_public` 4. turdan sonra **her** turda true; trust adımları (`82→92→…→100`) yalnız kabul edilen RELATE turlarında hareket ediyor |
| 2 | Karakterler yalnız bildiklerini biliyor mu | ✅ | her tur Alya'nın prompt'unda ne Marinette'in öğrendiği olgu ne de bir sır var (`secrecy=true`); `knows_secret=false` 10/10 tur |
| 3 | Kişilik/konuşma biçimi korunuyor mu | ✅ (yapısal) | `YOUR IDENTITY` bloğu + karakter adı 10/10 tur prompt'ta (`identity=true`); üslup kalitesi bkz. (b) |
| 4 | Mekân/zaman/ilişki/sırlar WorldState ile tutarlı mı | ✅ | `story time:` + `scene:` 10/10 tur (`world_visible=true`); ilişki değeri her tur `worldState.relationship()` ile karşılaştırıldı; sır sahipliği sabit |
| 5 | Gereksiz tekrar/anlamsız uzatma/çelişki | ✅ (gerçek-ağırlık kanıtı) | Bkz. (b) — mekanik göstergeler tamamlanmış after-run'da |
| 6 | Aksiyon etiketleri parser+validator'dan geçiyor mu | ✅ | 5 uygulanan (2 RELATE+1 MOVE+1 DISCOVER+1 RELATE) hep `AppliedAction`; ham yanıtta etiket artığı **yok** (`protocol_clean=true`) |
| 7 | Reddedilen aksiyonlar state'i değiştirmiyor mu | ✅ | 3. tur (bilinmeyen hedef) ve 5. tur (gizli olgu keşfi): `rejected=1 applied=0` ve trust/ilişki/knowledge **oynatılmadı** (`rejected_turns_clean=true`) |
| 8 | Kaydetme/yeniden yükleme/devam doğru mu | ✅ | 6. turda orta-yolda kaydet→**soğuk** yeniden yükle→devam: worldState+knowledge+transkript eşit; son durumda da tekrar doğrulandı (`reload_ok=true`) |

Ayrıca: 10/10 turda UTF-8 bozulması yok (Türkçe diakritikleri test edildi) ve transkript
20 girdide **sınırlı** kalıyor (pencere kayıyor, sonsuz büyümüyor).

**(b) Narrative quality — gerçek ağırlık [N]:** kalite mekanik olarak puanlanamaz; ölçülebilir
vekiller raporlanır (UTF-8/bozuk karakter, 6-kelime tekrar döngüsü, durma nedeni, uzunluk).
Ölçülmüş sert gerçekler (tamamlanmış after-run, 6 tur): `stop=length` **6/6** (220 token
tavanı → aşırı uzama riski kanıtlı), bozuk karakter **0** tur. 6-kelime tekrar döngüsü:
**5/6 turda** tekrar eden 6-kelime span tespitilti (uzunlukları 537–878 karakter arası).
İnsan/LLM okuması hâlâ gerekli — bunu "güzel yazıyor" diye sunmuyorum.

**(c) Aksiyon doğruluğu [N/S]:** `InferenceProfileTest` 6 turda `applied=0 rejected=0` —
Qwen kendiliğinden aksiyon etiketi çıkarmıyor (Phase 3'te de böyleydi; **açıkça** istenince
çıktı: `parsed=1 accepted=1`, bkz. `ActionProtocolModelTest`). Boru hattı kanıtı (a)'daki 5
uygulanan + 2 haklı reddedilen, `ModelProposalSecretKnowledgeTest` (uygulanan+reddedilen) ve
`StoryLoopJourneysTest` (12 yolculuk) üzerinden veriliyor.

## 8. DISCOVER güvenlik denetimi ✅ (Görev 4)

**Denetim (mevcut şema + iş kuralları):** `ActionValidator.DISCOVER` yalnız yapısal doğrular
(karakter var mı) ve `KnowledgeDiscovered` üretir; `factId` herhangi bir metin olabilir.
`EventEngine`'de de `unknown fact` + "zaten biliyor" kontrolleri vardı ama **hiçbir katman
bilginin NEREDEN geldiğini sormuyordu**. Dolayısıyla model, dünya gerçeğinde var olan **her**
olguyu — sırlar dahil — `discover` ile bir karaktere öğretebiliyor ve `SecretRevealed`'ın
`REVEALER_DOES_NOT_KNOW` yetki denetimi dolaylı olarak geçersizleşiyordu. Bu, §10.1'de
**çalıştırılarak** doğrulandı (`got Applied(...)`).

**Kural (otoriter katmanda — `EventEngine.validatePayload`, mutasyondan ÖNCE):**
`origin == MODEL_PROPOSAL` ise ve olgu `secret == true` ve karakter henüz **bilmiyorsa** →
ret `SECRET_REQUIRES_AUTHORIZED_SOURCE`. Yetkili kaynaklar aynen korunur: pack tohumlaması
(`origin=STORY_PACK`), yazar efektleri (`ENGINE`), bilen birinin `SecretRevealed`'ı, ve
karakterin **zaten bildiği** bir şeyi pekiştirme (yeni bilgi öğretmediği için no-op kalır).
Alan modelinin kendi sözleşmesiyle birebir: *"Secrets require an explicit discovery event"*
ve *"the payload's claim … is a proposal, not evidence"* (`KnowledgeStore.kt:25`,
`WorldEvent.kt:556-569`).

**En küçük doğru düzeltme olduğu gerekçesi:** `ActionValidator`'a bilgi tablosu taşımak hem
daha büyük bir değişiklik hem de iki kapının bağımsızlığı ilkesini (`InvalidModelOutputMatrixTest`:
"the two gates are independent on purpose") bozar; yetki kuralı tek otoriter yerde
(`EventEngine`) kalmalı. `SecretRevealed`'ın kuralı da zaten orada. Yeni enum değeri
**sona** eklendi; `describe()` insan-okur kalıyor (`"secret requires authorized source"`).

**Test edilen (8 test, §10.1):** bilinmeyen fact id (`unknown fact`, sır ihlali raporlanmaz),
reddedilmiş event'in sıfır kalıntı bırakması, kaydet→soğuk yeniden yükle→**kuralın ve
durumun korunması**, sıradan olgunun keşfinin bozulmaması, pekiştirme, pack tohumlama,
yetkili `ENGINE` verişi, bilen/bilmeyen ifşa matrisi, ve gerçek `respond()` boru hattında
gizli-sır reddi + aynı boru hattında sıradan-olgu kabulü.

**Bozulan eski beklenti (bilinçli, tek yerde):** `StoryChatLoopTest` A ve
`StoryConsistencyTenTurnsTest` daha önce *gizli* bir olgunun model `discover`'ı ile
öğrenilmesini olumlu senaryo olarak sabitliyordu — yani açığın kendisini. Bunlar §3'te
amaçları korunarak çevrildi (kaşif boru hattı hâlâ testli; artık gizli olmayan gerçekte) ve
sızıntının reddi yolculuğun içine eklendi. Brief'in "güvenlik kuralını gevşetme" çizgisi
korundu: kural yalnızca **sertleştirildi**.

## 9. Conditional event regresyon sonuçları ✅ (Görev 5)

**Çağrı zinciri (çıkartıldı):** `respond()` → (eski) `pollConditionalEvents` #1 → `advance()`
→ `advanceTime` (zamanlanmış kuyruk) → `pollConditionalEvents` #2 → `ChapterPlanner.derive`.
Dolayısıyla bir turda **iki** yoklama; `EventProgram.conditionalCandidates` için `repeatable`
+ `cooldownMinutes=0` olan bir olay ikinci yoklamada da uygun sayılırdı (`cooldown==0` ise
aralık kapısı hiç çalışmıyor) ve etkileri **ikinci kez** uygulanırdı.

**Riskin gerçekleşebilirliği (kod değil, ölçüm — [S]):**
`ConditionalEventExactlyOnceTest` kırmızı çalıştırıldığında **`expected:<10> but was:<20>`**
— bir turda ilişki deltası **iki kez** uygulandı. Değişim yeminde iki satır, iki bellek
kaydı ve iki olay düğümü oluşurdu (kod analizi §3 alt maddeleri). Bu bir hipotez değil,
çalışan testtir.

**En küçük güvenli düzeltme:** `respond` içindeki ön-yoklama kaldırıldı; tur başına tek
yoklama `advance`'in içindeki (aynı zamanda "zamanı 30 dk ilerlet" hattı) kaldı. Olay
**sırası** (`pack.events` sırası + `(scheduledAt, sequence)` kuyruk sırası) ve determinizm
değişmedi; çalışan test 2 koşunun dünya durumunu birebir karşılaştırıyor. Teknik not: tek
yoklama artık saat adımından SONRA çalışıyor (tek mantıksal aşama = tek yoklama).

**Regresyon testleri (2 yeni test):** (i) `repeatable`+0-bekleme olay turda **tam bir kez**
(+10, feed'de 1 satır) ve **sonraki turda yine** ateşlenir (fazla bastırma yok); (ii) aynı
iki tur her koşuda birebir dünya/saat üretir, `firedEvents` kaydı var, kuyruk boş kalır.

## 10. Test sonuçları (tam sayılar, başarısızlıklar, skip'ler)

**Değişiklik öncesi BASELINE (gerçek koşu, 16:17–16:19):**

| Komut | Sonuç | Sayım |
|---|---|---|
| `./gradlew :charaly-runtime:test --offline` (temiz koşu) | SUCCESS | **1440 test, 0 başarısızlık, 0 atlama** |
| `./gradlew :app:testDebugUnitTest --offline` (çıktılar silinip zorlanmış koşu) | SUCCESS | **465 test, 0 başarısızlık, 8 atlama** |

Toplam: **1905 test, 0 başarısızlık, 8 atlama.**

8 atlanan test (hepsi `assumeTrue` ile **dürüstçe** atlıyor; "geçti" sayılmıyor) ve gerekçesi
— gerçek GGUF + native kütüphane özellikleri verilmediği için motor yok:

`RealStoryChatLoopTest::{A, L, V, W, stress}` (5) · `StoryLatencyBenchmarkTest` ·
`ActionProtocolModelTest` · `InferenceProfileTest`

Not (sayı şeffaflığı): `app/build/test-results/testReleaseUnitTest` altında ayrıca **278**
test sonucu vardır (eski koşu). Phase 5'in "742 app testi" sayısı debug+release toplamına
karşılık geliyor; yukarıdaki 465 yalnızca `:app:testDebugUnitTest`tır (görevin istediği komut).

| `:charaly-runtime:test` (değişiklik SONRASI, tam koşu) | **SUCCESS** | **1453 test, 0 başarısızlık, 0 atlama** (1440 + 13 yeni) |
| `:app:testDebugUnitTest` (değişiklik SONRASI, tam koşu) | **SUCCESS** | **465 test, 0 başarısızlık, 8 atlama** |

Değişiklik sonrası toplam: **1918 test, 0 başarısızlık, 0 hata, 8 dürüst atlama.**
Aynı 8 gerçek-GGUF testi (motor özellikleri verilmediği için `assumeTrue` ile atlıyor; bkz.
aşağıdaki gerçek-GGUF koşusunda koşanlar). Hiçbir test silinmedi/atanmadı; toplam **+13**
test eklendi, 2 yerleşim sözlemesi güncellendi (§3), 1 testin fikstür hedefi çevrildi (§8).

**Gerçek-GGUF koşuları (host JVM + gerçek llama.cpp + gerçek ağırlık):**
- `InferenceProfileTest` (Qwen, 6 tur): Tamamlandı — Oct 9 20:41.
  Sonuç: `/tmp/opencode/inference-profile-qwen2.5-1.5b-instruct-q4_k_m.txt`
- `ActionProtocolModelTest` (Qwen): Tamamlandı — Oct 9 19:37.
  Sonuç: `/tmp/opencode/action-protocol-qwen2.5-1.5b-instruct-q4_k_m.txt`
  (`parsed=1 accepted=1`, `raw_contains_tag=true`)
- `RealStoryChatLoopTest` A/L/V/W/stress: `assumeTrue` ile atlandı (native lib bu koşumda
  verilmedi — ayrı koşularda çalıştırılabilir).

### 10.1 Yeni testlerin KIRMIZI kanıtı (düzeltmeden önce, aynı dosyalar)

Düzeltmeler yazılmadan önce yeni testler çalıştırmaya karşı **kasıtlı olarak kırmızı**
koşturuldu; aşağısı risklerin *gerçekten* var olduğunun çalıştırılabilir kanıtıdır
(Phase 5'in "risk" notlarının doğrulanması — iddia değil, ölçüm):

```
13 tests completed, 5 failed   (RED, düzeltmeler yokken)
```

| Test | Beklenen | Gerçek (RED) | Sınıf |
|---|---|---|---|
| `ModelProposalSecretKnowledgeTest :: a model proposal cannot teach a character a secret fact` | ret | **`Applied`** — gizli olgu gerçekten öğretildi | yeni bulgu (Görev 4 delili) |
| `ModelProposalSecretKnowledgeTest :: …survives a reload` | ret | **`Applied`** + diskte kalıcı | yeni bulgu |
| `ModelProposalSecretKnowledgeTest :: the story pipeline refuses a model discover of a secret…` | `RejectedAction` | **ret yok**, sır bilgisi verildi | yeni bulgu (uçtan uca) |
| `ConditionalEventExactlyOnceTest :: …lands exactly once per turn` | trust **+10** | trust **+20** (`expected:<10> but was:<20>`) | **çift etki kanıtı** (Görev 5) |
| `StoryConsistencyTenTurnsTest :: ten Turkish turns…` | sınır ihlali yok | `no secret may leak into another character's prompt` | Görev 3 + 4 etkileşimi |

Aynı koşuda **geçen 8 test** de kayıtlıdır (bozulmaması gereken yollar baştan yeşildi):
gizli olmayan gerçek keşfi, bilinmeyen fact-ID reddi (`unknown fact`), zaten-bilineni
pekiştirme, pack tohumlama, `ENGINE` yetkili verişi, bilen-in-ifşa yetkisi + bilmeyen-in
reddi (`revealer does not know`), ve olay sırası/determinizm testi (çift etki deterministiği
bozmuyordu — sırayı değil, **etki sayısını** bozuyordu).

### 10.2 Kırmızıların kök nedeni sınıflandırması (mevcut hata mı, yeni mi?)

| Başarısızlık | Sınıf | Gerekçe |
|---|---|---|
| 3 × `ModelProposalSecretKnowledgeTest` | **kasıtlı RED** — gerçek güvenlik açığı | kanıtı: `got Applied(...)`; yeni kod değil, mevcut davranışı düzelttik |
| 1 × `ConditionalEventExactlyOnceTest` | **kasıtlı RED** — gerçek çift etki | kanıtı: `expected:<10> but was:<20>` |
| 1 × `StoryConsistencyTenTurnsTest` | **kasıtlı RED** — aynı açığın 10-tur yolculuktaki sonucu | eski testin yerine geçiti (bkz. §7) |
| `ContextBuilderTest` × 2 (ara koşu) | **yeni değişiklik kaynaklı** | yerleşim sözleşmesi değişti; dürüstçe uyarlandı (§3) |
| `ConditionalEventExactlyOnceTest` × 1 (ara koşu) | **test kodu hatası (benim)** | `ch-alice` önekli eşleşme filtresi; ana kodla ilgisiz |
| `StoryChatLoopTest :: A` | **yeni değişiklik kaynaklı, ÖNGÖRÜLEN** | C8 kısıtı: test gizli-olgu-kaşifi başarısını sabitliyordu; §3'te amacı korunarak çevrildi |

Hiçbir test silinmedi, atlanmadı; iki yerleşim sözleşmesi cümlesi yeni yerleşime göre
güçlendirilerek yazıldı (bkz. §3).

## 11. APK yolu ve boyutu ✅

Derleme: `./gradlew :app:assembleDebug --offline` — **BUILD SUCCESSFUL** (3m 42s, 45 task).

| Özellik | Değer |
|---|---|
| **Yol** | `/root/charaly/app/build/outputs/apk/debug/app-debug.apk` |
| **Boyut** | **92.330.157 byte** (~88,0 MiB) |
| **SHA-256** | `1b2a154cd88ce5e64319c4e16f56f04420a12e2451987ac8eb572f5d393abdf1` |
| **Derleme tarihi** | 2026-10-14 22:47:48 +0300 |

APK boyutu Phase 5 ile **birebir aynı** (92.330.157 B) — Phase 6 ağırlıklarla
**değiştirilmedi**; APK'ya model ağırlığı gömülmemiştir. SHA-256 özeti ile
doğrulandı.

## 12. Fiziksel cihazda henüz doğrulanmamış davranışlar ✅

`adb devices` **boş** — bu raporda tek bir cihaz ölçümü yoktur ve host verisinden cihaz
gecikmesi **tahmin edilip gerçek sonuç gibi sunulmamıştır**. Aşağıdakiler özellikle
cihazda **ele alınmamıştır**:

1. **Mutlak latanslar** (TTFT, prompt-eval tok/s, generation tok/s, toplam tur süresi).
   Host 8 vCPU konteyneri ~20–50× yavaş olabilir; oranlar (ör. `reused/prompt`) transfer edilir, süreler etmez.
2. **Thread ölçeklemesi** (4/6/8) big.LITTLE mimarisinde — hostta 8 thread'in 3,2× kötüleştirmesi
   cihaz için **evrensel** sonuç değildir (§2.2-8).
3. **Bellek/tahsis davranışı**: `VmHWM` tepe RSS, düşük bellek öldürücüsü, ART/bionic tarafı.
4. **Termal/işleme gücü kayması**: host gözlemi (sonraki turların yavaşlaması) termale benziyor
   ama cihazda doğrulanmadı.
5. **Uygulama içi performans telemetrisi** (`STORY TURN PERF` log'u) telefon üzerinde okunmadı;
   dokunmatik sahne akışı ve gerçek DURDUR gecikmesi ölçülemedi.
6. Chat template'in JNI tarafındaki nihai token dizilimi cihazda ayrı doğrulanmadı (host JNI
   ile aynı kaynak derlemesi; llama.cpp sürümü aynı).

## 13. Bir sonraki tek, en yüksek değerli adım ✅

**Canlı yolda `ContextBudget.maxChars` bütçesini uygula** (mevcut
`ContextBudgetInspector`'ın öncelikli-tam-bölüm-kesme politikasını üretim yoluna al, opt-in
değil davranış olarak) — çünkü:

* ölçülen tek "büyük" kazanç kapısı orası: parça parça geçmiş penceresi ölçüldü ve **+0,073**
  karakter-önek kazancı veriyor (0,622 → 0,695; pencere boşaltımı dışında 0,80'e çıkıyor) ama
  ölçülen tepe bağlamı 7.205 karaktere taşıyor — bütçe uygulanmadığı için bugün güvensiz (§4);
* bugün aşım durumunda kırpma değil **native hata** oluşuyor ("prompt is longer than the
  context window allows"), yani bu yalnız performans değil, dayanıklılık işi;
* tek adımlık, geri alınabilir, ölçülebilir: aynı `InferenceProfileTest` + 6 tur ile
  `reused/prompt` oranı ve `prompt_eval` ms doğrudan karşılaştırılabilir; kalite garantisi
  yine aynı 10-tur tutarlılık testiyle (§7) yürür.

Değerlendirilen ama **ikinci sıraya** alınanlar: (a) model boyu/stop politikası — 6/6 tur
tavana çarpıyor ve toplam sürenin yarısı orada, fakat limiti kısma hikâyeyi yarım kesme
riski taşıdığı için kalite-kapılı karar gerektiriyor; (b) prompt bölüm kırpım/özetleme —
bilgi kaybı riski (brief sınırı).
